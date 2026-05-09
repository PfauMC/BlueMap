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

import static org.junit.jupiter.api.Assertions.*;

class S3KeyEncodingTest {

    // ===================================================================
    // gridKey
    // ===================================================================

    @Test
    void gridKeyZeroZero() {
        assertEquals(
                "maps/world/hires/x0/z0.prbm.gz",
                S3GridStorage.gridKey("maps", "world", "hires", 0, 0, ".prbm.gz"));
    }

    @Test
    void gridKeyPositiveCoords() {
        assertEquals(
                "maps/world/hires/x1/2/7/z4/2.prbm.gz",
                S3GridStorage.gridKey("maps", "world", "hires", 127, 42, ".prbm.gz"));
    }

    @Test
    void gridKeyNegativeCoords() {
        // This is the load-bearing test: (127, -89) must mirror FileGridStorage.getItemPath
        assertEquals(
                "maps/world/hires/x1/2/7/z-8/9.prbm.gz",
                S3GridStorage.gridKey("maps", "world", "hires", 127, -89, ".prbm.gz"));
    }

    @Test
    void gridKeyEmptyPrefix() {
        assertEquals(
                "world/lowres/0/x4/2/z3/1.png",
                S3GridStorage.gridKey("", "world", "lowres/0", 42, 31, ".png"));
    }

    @Test
    void gridKeyStripsLeadingTrailingSlash() {
        assertEquals(
                "maps/world/hires/x0/z0",
                S3GridStorage.gridKey("/maps/", "world", "hires", 0, 0, ""));
    }

    // ===================================================================
    // itemKey
    // ===================================================================

    @Test
    void itemKeyShape() {
        assertEquals(
                "maps/world/settings.json",
                S3ItemStorage.itemKey("maps", "world", "settings", ".json"));
        assertEquals(
                "world/textures.json.gz",
                S3ItemStorage.itemKey("", "world", "textures", ".json.gz"));
    }

    // ===================================================================
    // mapPrefix
    // ===================================================================

    @Test
    void mapPrefixEndsInSlash() {
        assertEquals("maps/world/", S3GridStorage.mapPrefix("maps", "world"));
        assertEquals("world/", S3GridStorage.mapPrefix("", "world"));
    }

    // ===================================================================
    // decodeXZ
    // ===================================================================

    @Test
    void decodeXZRoundTrips() {
        String key = S3GridStorage.gridKey("maps", "world", "hires", 127, -89, ".prbm.gz");
        String sectionPrefix = S3GridStorage.gridSectionPrefix("maps", "world", "hires");
        int[] xz = S3GridStorage.decodeXZ(key, sectionPrefix, ".prbm.gz");
        assertNotNull(xz);
        assertArrayEquals(new int[]{127, -89}, xz);
    }

    @Test
    void decodeXZRejectsMismatchedSuffix() {
        String key = S3GridStorage.gridKey("maps", "world", "hires", 0, 0, ".prbm.gz");
        String sectionPrefix = S3GridStorage.gridSectionPrefix("maps", "world", "hires");
        assertNull(S3GridStorage.decodeXZ(key, sectionPrefix, ".wrong"));
    }

    @Test
    void decodeXZRejectsForeignPrefix() {
        String key = S3GridStorage.gridKey("maps", "world", "hires", 0, 0, ".prbm.gz");
        String otherPrefix = S3GridStorage.gridSectionPrefix("maps", "other", "hires");
        assertNull(S3GridStorage.decodeXZ(key, otherPrefix, ".prbm.gz"));
    }

    @Test
    void decodeXZHandlesZeroCoords() {
        String key = S3GridStorage.gridKey("maps", "world", "lowres/0", 0, 0, ".png");
        String sectionPrefix = S3GridStorage.gridSectionPrefix("maps", "world", "lowres/0");
        int[] xz = S3GridStorage.decodeXZ(key, sectionPrefix, ".png");
        assertNotNull(xz);
        assertArrayEquals(new int[]{0, 0}, xz);
    }
}
