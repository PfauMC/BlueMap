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

import de.bluecolored.bluemap.core.storage.ItemStorage;
import de.bluecolored.bluemap.core.storage.compression.CompressedInputStream;
import de.bluecolored.bluemap.core.storage.compression.Compression;
import de.bluecolored.bluemap.core.util.stream.OnCloseOutputStream;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

public class S3ItemStorage implements ItemStorage {

    private final S3HttpClient http;
    private final String fullKey;
    private final Compression compression;
    private final String contentType;

    public S3ItemStorage(S3HttpClient http, String fullKey, Compression compression, String contentType) {
        this.http = http;
        this.fullKey = fullKey;
        this.compression = compression;
        this.contentType = contentType;
    }

    @Override
    public OutputStream write() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        return new OnCloseOutputStream(compression.compress(bytes),
                () -> http.putObject(fullKey, bytes.toByteArray(), contentType));
    }

    @Override
    public @Nullable CompressedInputStream read() throws IOException {
        byte[] data = http.getObject(fullKey);
        if (data == null) return null;
        return new CompressedInputStream(new ByteArrayInputStream(data), compression);
    }

    @Override
    public void delete() throws IOException {
        http.deleteObject(fullKey);
    }

    @Override
    public boolean exists() throws IOException {
        return http.headObject(fullKey);
    }

    @Override
    public boolean isClosed() {
        return http.isClosed();
    }

    /** Builds: [stripSlashes(prefix)/]mapId/itemName{suffix} */
    static String itemKey(String prefix, String mapId, String itemName, String suffix) {
        StringBuilder sb = new StringBuilder();
        String root = stripSlashes(prefix);
        if (!root.isEmpty()) {
            sb.append(root).append('/');
        }
        sb.append(mapId).append('/').append(itemName).append(suffix);
        return sb.toString();
    }

    private static String stripSlashes(String s) {
        if (s == null || s.isEmpty()) return "";
        int start = 0;
        int end = s.length();
        while (start < end && s.charAt(start) == '/') start++;
        while (end > start && s.charAt(end - 1) == '/') end--;
        return s.substring(start, end);
    }
}
