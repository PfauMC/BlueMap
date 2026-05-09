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

import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class S3KeyEncoderTest {

    @Test
    void encodePathSpaceIsPercentTwenty() {
        assertEquals("a%20b", S3KeyEncoder.encodePath("a b"));
        // URLEncoder uses + instead of %20 — our encoder intentionally diverges
        assertEquals("a+b", URLEncoder.encode("a b", StandardCharsets.UTF_8));
    }

    @Test
    void encodePathTildePreserved() {
        assertEquals("~user", S3KeyEncoder.encodePath("~user"));
    }

    @Test
    void encodePathSlashPreserved() {
        assertEquals("a/b/c.txt", S3KeyEncoder.encodePath("a/b/c.txt"));
    }

    @Test
    void encodePathPlusEncoded() {
        assertEquals("a%2Bb", S3KeyEncoder.encodePath("a+b"));
    }

    @Test
    void encodePathUtf8Multibyte() {
        assertEquals("%C3%BCmlaut", S3KeyEncoder.encodePath("ümlaut"));
    }

    @Test
    void encodeQueryComponentSlashEncoded() {
        assertEquals("a%2Fb", S3KeyEncoder.encodeQueryComponent("a/b"));
    }

    @Test
    void encodeQueryComponentSpecialChars() {
        assertEquals("a%3Db%26c", S3KeyEncoder.encodeQueryComponent("a=b&c"));
    }
}
