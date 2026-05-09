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

import de.bluecolored.bluemap.core.storage.GridStorage;
import de.bluecolored.bluemap.core.storage.ItemStorage;
import de.bluecolored.bluemap.core.storage.compression.CompressedInputStream;
import de.bluecolored.bluemap.core.storage.compression.Compression;
import de.bluecolored.bluemap.core.util.stream.OnCloseOutputStream;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class S3GridStorage implements GridStorage {

    private static final Pattern XZ_PATTERN = Pattern.compile("x(-?\\d+)z(-?\\d+)");

    private final S3HttpClient http;
    private final String prefix;
    private final String mapId;
    private final String section;
    private final String suffix;
    private final Compression compression;
    private final String contentType;

    public S3GridStorage(S3HttpClient http, String prefix, String mapId, String section,
                         String suffix, Compression compression, String contentType) {
        this.http = http;
        this.prefix = prefix;
        this.mapId = mapId;
        this.section = section;
        this.suffix = suffix;
        this.compression = compression;
        this.contentType = contentType;
    }

    @Override
    public OutputStream write(int x, int z) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        return new OnCloseOutputStream(compression.compress(bytes),
                () -> http.putObject(gridKey(prefix, mapId, section, x, z, suffix), bytes.toByteArray(), contentType));
    }

    @Override
    public @Nullable CompressedInputStream read(int x, int z) throws IOException {
        byte[] data = http.getObject(gridKey(prefix, mapId, section, x, z, suffix));
        if (data == null) return null;
        return new CompressedInputStream(new ByteArrayInputStream(data), compression);
    }

    @Override
    public void delete(int x, int z) throws IOException {
        http.deleteObject(gridKey(prefix, mapId, section, x, z, suffix));
    }

    @Override
    public boolean exists(int x, int z) throws IOException {
        return http.headObject(gridKey(prefix, mapId, section, x, z, suffix));
    }

    @Override
    public ItemStorage cell(int x, int z) {
        return new GridStorageCell(this, x, z);
    }

    @Override
    public Stream<Cell> stream() throws IOException {
        String sectionPrefix = gridSectionPrefix(prefix, mapId, section);
        Spliterator<Cell> spliterator = new GridSpliterator(http, sectionPrefix, suffix, this);
        return StreamSupport.stream(spliterator, false);
    }

    @Override
    public boolean isClosed() {
        return http.isClosed();
    }

    static String gridKey(String prefix, String mapId, String section, int x, int z, String suffix) {
        StringBuilder sb = new StringBuilder();
        String root = stripSlashes(prefix);
        if (!root.isEmpty()) {
            sb.append(root).append('/');
        }
        sb.append(mapId).append('/').append(section).append('/');
        appendDigitGrouped(sb, "x" + x);
        sb.append('/');
        appendDigitGrouped(sb, "z" + z);
        sb.append(suffix);
        return sb.toString();
    }

    static String gridSectionPrefix(String prefix, String mapId, String section) {
        StringBuilder sb = new StringBuilder();
        String root = stripSlashes(prefix);
        if (!root.isEmpty()) {
            sb.append(root).append('/');
        }
        sb.append(mapId).append('/').append(section).append('/');
        return sb.toString();
    }

    static String mapPrefix(String prefix, String mapId) {
        StringBuilder sb = new StringBuilder();
        String root = stripSlashes(prefix);
        if (!root.isEmpty()) {
            sb.append(root).append('/');
        }
        sb.append(mapId).append('/');
        return sb.toString();
    }

    static @Nullable int[] decodeXZ(String fullKey, String sectionPrefix, String suffix) {
        if (!fullKey.startsWith(sectionPrefix)) return null;
        if (!fullKey.endsWith(suffix)) return null;
        String middle = fullKey.substring(sectionPrefix.length(), fullKey.length() - suffix.length());
        String flat = middle.replace("/", "");
        Matcher m = XZ_PATTERN.matcher(flat);
        if (!m.matches()) return null;
        return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
    }

    private static String stripSlashes(String s) {
        if (s == null || s.isEmpty()) return "";
        int start = 0;
        int end = s.length();
        while (start < end && s.charAt(start) == '/') start++;
        while (end > start && s.charAt(end - 1) == '/') end--;
        return s.substring(start, end);
    }

    private static void appendDigitGrouped(StringBuilder out, String token) {
        // Mirrors FileGridStorage.getItemPath: each character is appended; whenever a digit
        // is seen, it forms the end of a segment and a separator is inserted before the next char.
        StringBuilder segment = new StringBuilder();
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            segment.append(c);
            if (c >= '0' && c <= '9') {
                out.append(segment);
                segment.setLength(0);
                if (i < token.length() - 1) {
                    out.append('/');
                }
            }
        }
        if (segment.length() > 0) {
            out.append(segment);
        }
    }

    // --- Spliterator for stream() ---

    private static final class GridSpliterator implements Spliterator<Cell> {

        private final S3HttpClient http;
        private final String sectionPrefix;
        private final String suffix;
        private final S3GridStorage storage;

        private java.util.List<String> currentPage = null;
        private int pageIndex = 0;
        private String continuationToken = null;
        private boolean exhausted = false;

        GridSpliterator(S3HttpClient http, String sectionPrefix, String suffix, S3GridStorage storage) {
            this.http = http;
            this.sectionPrefix = sectionPrefix;
            this.suffix = suffix;
            this.storage = storage;
        }

        @Override
        public boolean tryAdvance(Consumer<? super Cell> action) {
            while (true) {
                if (currentPage != null && pageIndex < currentPage.size()) {
                    String key = currentPage.get(pageIndex++);
                    int[] xz = decodeXZ(key, sectionPrefix, suffix);
                    if (xz == null) continue;
                    action.accept(new GridStorageCell(storage, xz[0], xz[1]));
                    return true;
                }
                if (exhausted) return false;
                try {
                    S3XmlParsing.ListPage page = http.listObjectsV2(sectionPrefix, continuationToken, null, 1000);
                    currentPage = page.keys();
                    pageIndex = 0;
                    if (page.truncated()) {
                        if (page.nextContinuationToken() == null) {
                            throw new UncheckedIOException(new IOException(
                                    "Malformed S3 ListObjectsV2 response: IsTruncated=true but NextContinuationToken is absent"));
                        }
                        continuationToken = page.nextContinuationToken();
                    } else {
                        exhausted = true;
                    }
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }

        @Override
        public @Nullable Spliterator<Cell> trySplit() {
            return null;
        }

        @Override
        public long estimateSize() {
            return Long.MAX_VALUE;
        }

        @Override
        public int characteristics() {
            return NONNULL | ORDERED;
        }
    }
}
