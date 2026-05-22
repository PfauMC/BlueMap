/*
 * This file is part of BlueMap, licensed under the MIT License (MIT).
 *
 * Copyright (c) Blue (Lukas Rieger) <https://bluecolored.de>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package de.bluecolored.bluemap.core.storage.s3;

import com.github.benmanes.caffeine.cache.Cache;
import de.bluecolored.bluemap.core.logger.Logger;
import de.bluecolored.bluemap.core.storage.GridStorage;
import de.bluecolored.bluemap.core.storage.ItemStorage;
import de.bluecolored.bluemap.core.storage.KeyedMapStorage;
import de.bluecolored.bluemap.core.storage.cache.CoalescingGridStorage;
import de.bluecolored.bluemap.core.storage.cache.CoalescingItemStorage;
import de.bluecolored.bluemap.core.storage.compression.Compression;
import de.bluecolored.bluemap.core.util.Caches;
import de.bluecolored.bluemap.core.util.Key;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoublePredicate;

public class S3MapStorage extends KeyedMapStorage {

    private static final int DELETE_BATCH_SIZE = 1000;

    private final String mapId;
    private final S3HttpClient http;
    private final String prefix;

    private final Cache<Key, ItemStorage> itemStorages = Caches.build();
    private final Cache<Key, GridStorage> gridStorages = Caches.build();

    public S3MapStorage(String mapId, S3HttpClient http, String prefix, Compression compression) {
        super(compression);
        if (mapId == null || mapId.isEmpty()) throw new IllegalArgumentException("mapId must be non-empty");
        this.mapId = mapId;
        this.http = http;
        this.prefix = prefix;
    }

    @Override
    public ItemStorage item(Key key, Compression compression) {
        ItemStorage existing = itemStorages.getIfPresent(key);
        if (existing != null) return existing;
        return itemStorages.get(key, k -> buildItemStorage(k, compression));
    }

    @Override
    public GridStorage grid(Key key, Compression compression) {
        GridStorage existing = gridStorages.getIfPresent(key);
        if (existing != null) return existing;
        return gridStorages.get(key, k -> buildGridStorage(k, compression));
    }

    @Override
    public void delete(DoublePredicate onProgress) throws IOException {
        String mapPfx = S3GridStorage.mapPrefix(prefix, mapId);

        // First pass: count total keys
        int total = 0;
        String token = null;
        do {
            S3XmlParsing.ListPage page = http.listObjectsV2(mapPfx, token, null, 1000);
            total += page.keys().size();
            token = page.truncated() ? page.nextContinuationToken() : null;
        } while (token != null);

        if (total == 0) return;

        // Second pass: delete in batches
        int deleted = 0;
        token = null;
        List<String> batch = new ArrayList<>(DELETE_BATCH_SIZE);

        do {
            S3XmlParsing.ListPage page = http.listObjectsV2(mapPfx, token, null, 1000);
            for (String key : page.keys()) {
                batch.add(key);
                if (batch.size() >= DELETE_BATCH_SIZE) {
                    deleted += flushBatch(batch);
                    batch.clear();
                    if (!onProgress.test(Math.min((double) deleted / total, 1.0))) return;
                }
            }
            token = page.truncated() ? page.nextContinuationToken() : null;
        } while (token != null);

        if (!batch.isEmpty()) {
            deleted += flushBatch(batch);
            onProgress.test(Math.min((double) deleted / total, 1.0));
        }
    }

    private int flushBatch(List<String> keys) throws IOException {
        S3XmlParsing.DeleteResult result = http.deleteObjects(keys);
        if (!result.errors().isEmpty()) {
            S3XmlParsing.DeleteEntry first = result.errors().get(0);
            Logger.global.logWarning("S3 bulk-delete: " + result.errors().size() + " of " + keys.size()
                    + " keys failed (first: " + first.key() + " " + first.code() + ")");
        }
        return result.deleted().size();
    }

    @Override
    public boolean exists() throws IOException {
        S3XmlParsing.ListPage page = http.listObjectsV2(S3GridStorage.mapPrefix(prefix, mapId), null, null, 1);
        return !page.keys().isEmpty() || !page.commonPrefixes().isEmpty();
    }

    @Override
    public boolean isClosed() {
        return http.isClosed();
    }

    private ItemStorage buildItemStorage(Key key, Compression compression) {
        String name = key.getValue();
        // Key always ends with the file-format extension only. Compression is
        // signalled to clients via a Content-Encoding header on PUT, not via a
        // ".gz"-style key suffix, so a CDN sitting in front of the bucket can
        // serve the object under its plain name and let the browser decompress
        // transparently.
        String baseSuffix = name.startsWith("asset/") ? "" : ".json";
        String contentType = name.startsWith("asset/") ? "application/octet-stream" : "application/json";
        String fullKey = S3ItemStorage.itemKey(prefix, mapId, name, baseSuffix);
        Compression effectiveCompression = compression != null ? compression : Compression.NONE;
        ItemStorage bare = new S3ItemStorage(http, fullKey, effectiveCompression, contentType);
        return new CoalescingItemStorage(bare);
    }

    private GridStorage buildGridStorage(Key key, Compression compression) {
        String section = key.getValue();
        // Tile sections are remapped to the path layout the webapp constructs
        // its tile URLs from (TileLoader uses "tiles/0/", LowresTileLoader uses
        // "tiles/<lod>/" with lod=i+1). Doing the remap on the storage side
        // keeps the rest of BlueMap's render code untouched and lets a CDN in
        // front of the bucket serve tiles directly under the names the browser
        // requests.
        String storageSection = remapSectionForWebapp(section);
        String suffix = gridSuffix(section);
        String contentType = section.startsWith("lowres/") ? "image/png" : "application/octet-stream";
        Compression effectiveCompression = compression != null ? compression : Compression.NONE;
        GridStorage bare = new S3GridStorage(http, prefix, mapId, storageSection, suffix, effectiveCompression, contentType);
        String namespace = S3GridStorage.gridSectionPrefix(prefix, mapId, storageSection);
        return new CoalescingGridStorage(bare, namespace);
    }

    /**
     * Remaps render-side section names to the path segment the webapp expects.
     * Render code calls {@code KeyedMapStorage.lowresTiles(lod)} with {@code lod=1..N}
     * (and uses {@code hiresTiles()} for the hires layer), producing sections
     * {@code lowres/1}, {@code lowres/2}, ..., {@code hires}. The webapp's URL builder
     * uses the same index space but a flat {@code tiles/<lod>} path:
     * <ul>
     *   <li>{@code hires} → {@code tiles/0} (TileLoader.js → {@code tiles/0/...prbm})</li>
     *   <li>{@code lowres/<n>} → {@code tiles/<n>} (LowresTileLoader.js, {@code lod=n})</li>
     *   <li>everything else (e.g. {@code tile-state}, {@code chunk-state}) is left as-is —
     *       these are internal, never fetched by the webapp.</li>
     * </ul>
     */
    private static String remapSectionForWebapp(String section) {
        if ("hires".equals(section)) return "tiles/0";
        if (section.startsWith("lowres/")) {
            try {
                int lod = Integer.parseInt(section.substring("lowres/".length()));
                return "tiles/" + lod;
            } catch (NumberFormatException ignored) {
                // Fall through to unchanged section if the suffix isn't an int.
            }
        }
        return section;
    }

    /**
     * File-format extension for the given (render-side) grid section. Compression is no
     * longer baked into the key — see {@link #buildItemStorage} for rationale.
     */
    private static String gridSuffix(String section) {
        if (section.startsWith("lowres/")) {
            return ".png";
        }
        if ("hires".equals(section) || section.startsWith("hires/")) {
            return ".prbm";
        }
        return "";
    }
}
