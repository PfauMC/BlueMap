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

import java.nio.charset.StandardCharsets;

/**
 * RFC 3986 strict percent-encoder for S3 object keys and query components.
 *
 * Encodes spaces as %20 (not +), preserves tilde, and uses upper-case hex for all
 * bytes outside the RFC 3986 unreserved set.
 */
public final class S3KeyEncoder {

    private S3KeyEncoder() {}

    /**
     * Encodes a path segment, preserving forward slashes.
     * All bytes outside the RFC 3986 unreserved set are percent-encoded as upper-case hex,
     * except that '/' is passed through unchanged.
     */
    public static String encodePath(String key) {
        return encode(key, true);
    }

    /**
     * Encodes a query string component (name or value).
     * All bytes outside the RFC 3986 unreserved set — including '/' — are percent-encoded.
     */
    public static String encodeQueryComponent(String value) {
        return encode(value, false);
    }

    private static String encode(String input, boolean preserveSlash) {
        byte[] bytes = input.getBytes(StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder(bytes.length + 16);
        for (byte b : bytes) {
            int c = b & 0xFF;
            if (isUnreserved(c) || (preserveSlash && c == '/')) {
                out.append((char) c);
            } else {
                out.append('%');
                out.append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xF, 16)));
                out.append(Character.toUpperCase(Character.forDigit(c & 0xF, 16)));
            }
        }
        return out.toString();
    }

    private static boolean isUnreserved(int c) {
        return (c >= 'A' && c <= 'Z')
                || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9')
                || c == '-' || c == '_' || c == '.' || c == '~';
    }
}
