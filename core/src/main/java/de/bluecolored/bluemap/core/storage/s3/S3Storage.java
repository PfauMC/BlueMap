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

import com.github.benmanes.caffeine.cache.LoadingCache;
import de.bluecolored.bluemap.core.storage.MapStorage;
import de.bluecolored.bluemap.core.storage.Storage;
import de.bluecolored.bluemap.core.storage.compression.Compression;
import de.bluecolored.bluemap.core.util.Caches;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

public class S3Storage implements Storage {

    private final S3HttpClient http;
    private final String prefix;
    private final Compression compression;
    private final LoadingCache<String, S3MapStorage> mapStorages = Caches.build(this::create);

    public S3Storage(S3HttpClient http, String prefix, Compression compression) {
        this.http = http;
        this.prefix = prefix;
        this.compression = compression;
    }

    @Override
    public void initialize() throws IOException {
        http.headBucket();
    }

    private S3MapStorage create(String mapId) {
        return new S3MapStorage(mapId, http, prefix, compression);
    }

    @Override
    public MapStorage map(String mapId) {
        return mapStorages.get(mapId);
    }

    @Override
    public Stream<String> mapIds() throws IOException {
        String rootPrefix = rootPrefix(prefix);
        List<String> ids = new ArrayList<>();
        String token = null;

        do {
            S3XmlParsing.ListPage page = http.listObjectsV2(rootPrefix, token, "/", 1000);
            for (String cp : page.commonPrefixes()) {
                String id = stripMapId(cp, rootPrefix);
                if (id != null) ids.add(id);
            }
            token = page.truncated() ? page.nextContinuationToken() : null;
        } while (token != null);

        return ids.stream();
    }

    @Override
    public boolean isClosed() {
        return http.isClosed();
    }

    @Override
    public void close() throws IOException {
        http.close();
    }

    private static String rootPrefix(String p) {
        if (p == null || p.isEmpty()) return "";
        String stripped = p;
        int start = 0;
        int end = stripped.length();
        while (start < end && stripped.charAt(start) == '/') start++;
        while (end > start && stripped.charAt(end - 1) == '/') end--;
        stripped = stripped.substring(start, end);
        return stripped.isEmpty() ? "" : stripped + "/";
    }

    private static String stripMapId(String commonPrefix, String rootPrefix) {
        if (!commonPrefix.startsWith(rootPrefix)) return null;
        String rest = commonPrefix.substring(rootPrefix.length());
        if (rest.endsWith("/")) rest = rest.substring(0, rest.length() - 1);
        return rest.isEmpty() ? null : rest;
    }
}
