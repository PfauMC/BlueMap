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
package de.bluecolored.bluemap.core.storage;

import de.bluecolored.bluemap.core.storage.compression.CompressedInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.*;

public abstract class StorageContract {

    private Storage storage;

    protected abstract Storage createStorage() throws Exception;

    protected void afterClose() throws Exception {}

    @BeforeEach
    void setUp() throws Exception {
        storage = createStorage();
        storage.initialize();
    }

    @AfterEach
    void tearDown() throws Exception {
        storage.close();
        afterClose();
    }

    // --- helpers ---

    private static void write(ItemStorage it, byte[] data) throws IOException {
        try (OutputStream out = it.write()) {
            out.write(data);
        }
    }

    private static byte[] read(ItemStorage it) throws IOException {
        try (CompressedInputStream cis = it.read()) {
            if (cis == null) return null;
            return cis.decompress().readAllBytes();
        }
    }

    private static byte[] read(GridStorage grid, int x, int z) throws IOException {
        try (CompressedInputStream cis = grid.read(x, z)) {
            if (cis == null) return null;
            return cis.decompress().readAllBytes();
        }
    }

    // ===================================================================
    // Lifecycle (7)
    // ===================================================================

    @Test
    void mapReturnsConsistentInstancePerMapId() {
        MapStorage a = storage.map("world");
        MapStorage b = storage.map("world");
        assertEquals(a, b);
    }

    @Test
    void mapIdsIsEmptyForFreshStorage() throws IOException {
        List<String> ids = storage.mapIds().collect(Collectors.toList());
        assertTrue(ids.isEmpty());
    }

    @Test
    void mapIdsListsCreatedMaps() throws IOException {
        write(storage.map("alpha").settings(), "{}".getBytes(UTF_8));
        write(storage.map("beta").settings(), "{}".getBytes(UTF_8));
        List<String> ids = storage.mapIds().collect(Collectors.toList());
        assertTrue(ids.contains("alpha"), "alpha missing from " + ids);
        assertTrue(ids.contains("beta"), "beta missing from " + ids);
    }

    @Test
    void mapExistsReflectsAtLeastOneWrite() throws IOException {
        MapStorage m = storage.map("world");
        assertFalse(m.exists());
        write(m.settings(), "{}".getBytes(UTF_8));
        assertTrue(m.exists());
    }

    @Test
    void mapDeleteRemovesAllArtifacts() throws IOException {
        MapStorage m = storage.map("world");
        write(m.settings(), "{}".getBytes(UTF_8));
        write(m.hiresTiles().cell(0, 0), new byte[]{1, 2, 3});
        assertTrue(m.exists());
        m.delete();
        assertFalse(m.exists());
    }

    @Test
    void mapDeleteAbortsCleanlyOnFalsePredicate() throws IOException {
        MapStorage m = storage.map("world");
        for (int i = 0; i < 5; i++) {
            write(m.hiresTiles().cell(i, 0), new byte[]{(byte) i});
        }
        m.delete(progress -> false);
        // no exception — method must return cleanly
    }

    @Test
    void mapDeleteOnEmptyIsNoOp() throws IOException {
        MapStorage m = storage.map("empty-world");
        assertFalse(m.exists());
        m.delete();
        assertFalse(m.exists());
    }

    // ===================================================================
    // Grid (8)
    // ===================================================================

    @Test
    void gridWriteReadRoundtripsBytes() throws IOException {
        GridStorage grid = storage.map("world").hiresTiles();
        byte[] data = "hello-grid".getBytes(UTF_8);
        write(grid.cell(3, 7), data);
        assertArrayEquals(data, read(grid, 3, 7));
    }

    @Test
    void gridReadAbsentReturnsNull() throws IOException {
        GridStorage grid = storage.map("world").hiresTiles();
        assertNull(grid.read(999, 999));
    }

    @Test
    void gridDeleteAbsentSucceedsIdempotently() throws IOException {
        GridStorage grid = storage.map("world").hiresTiles();
        grid.delete(42, 42);
        grid.delete(42, 42);
    }

    @Test
    void gridExistsReflectsWriteAndDelete() throws IOException {
        GridStorage grid = storage.map("world").hiresTiles();
        assertFalse(grid.exists(5, 5));
        write(grid.cell(5, 5), "x".getBytes(UTF_8));
        assertTrue(grid.exists(5, 5));
        grid.delete(5, 5);
        assertFalse(grid.exists(5, 5));
    }

    @Test
    void gridStreamEmptyForFreshSection() throws IOException {
        GridStorage grid = storage.map("world").hiresTiles();
        try (Stream<GridStorage.Cell> stream = grid.stream()) {
            assertEquals(0, stream.count());
        }
    }

    @Test
    void gridStreamEnumeratesAllWritten() throws IOException {
        GridStorage grid = storage.map("world").hiresTiles();
        int[][] coords = {{0, 0}, {1, 1}, {-2, -3}, {127, -89}};
        for (int[] xz : coords) {
            write(grid.cell(xz[0], xz[1]), ("tile" + xz[0] + "," + xz[1]).getBytes(UTF_8));
        }
        Set<String> found;
        try (Stream<GridStorage.Cell> stream = grid.stream()) {
            found = stream
                    .map(c -> c.getX() + "," + c.getZ())
                    .collect(Collectors.toSet());
        }
        for (int[] xz : coords) {
            assertTrue(found.contains(xz[0] + "," + xz[1]),
                    "Missing cell " + xz[0] + "," + xz[1] + " from stream; found=" + found);
        }
    }

    @Test
    void gridCoordsCanBeNegative() throws IOException {
        GridStorage grid = storage.map("world").hiresTiles();
        byte[] data = "negative".getBytes(UTF_8);
        write(grid.cell(-1, -1), data);
        assertArrayEquals(data, read(grid, -1, -1));
    }

    @Test
    void gridLargeCoordsRoundTrip() throws IOException {
        GridStorage grid = storage.map("world").hiresTiles();
        byte[] data = "large".getBytes(UTF_8);
        write(grid.cell(123456, -987654), data);
        assertArrayEquals(data, read(grid, 123456, -987654));
    }

    @Test
    void gridCellViewMatchesGridOps() throws IOException {
        GridStorage grid = storage.map("world").hiresTiles();
        ItemStorage cell = grid.cell(10, 20);
        byte[] data = "cell-view".getBytes(UTF_8);
        try (OutputStream out = cell.write()) {
            out.write(data);
        }
        assertTrue(cell.exists());
        try (CompressedInputStream in = cell.read()) {
            assertNotNull(in);
            assertArrayEquals(data, in.decompress().readAllBytes());
        }
        cell.delete();
        assertFalse(cell.exists());
    }

    // ===================================================================
    // Item (5)
    // ===================================================================

    @Test
    void itemWriteReadRoundtripsBytes() throws IOException {
        ItemStorage item = storage.map("world").settings();
        byte[] data = "{\"version\":1}".getBytes(UTF_8);
        write(item, data);
        assertArrayEquals(data, read(item));
    }

    @Test
    void itemReadAbsentReturnsNull() throws IOException {
        ItemStorage item = storage.map("world").settings();
        assertNull(item.read());
    }

    @Test
    void itemOverwriteReplacesPriorBytes() throws IOException {
        ItemStorage item = storage.map("world").settings();
        write(item, "first".getBytes(UTF_8));
        write(item, "second".getBytes(UTF_8));
        assertArrayEquals("second".getBytes(UTF_8), read(item));
    }

    @Test
    void itemDeleteAbsentSucceedsIdempotently() throws IOException {
        ItemStorage item = storage.map("world").settings();
        item.delete();
        item.delete();
    }

    @Test
    void itemDeleteRemovesData() throws IOException {
        ItemStorage item = storage.map("world").settings();
        write(item, "data".getBytes(UTF_8));
        assertTrue(item.exists());
        item.delete();
        assertFalse(item.exists());
        assertNull(item.read());
    }

    // ===================================================================
    // Asset escape (1)
    // ===================================================================

    @Test
    void assetEscapesPathSeparators() throws IOException {
        MapStorage m = storage.map("world");
        String unsafeName = "../etc/secret.bin";
        ItemStorage asset = m.asset(unsafeName);
        byte[] data = "secret-content".getBytes(UTF_8);
        write(asset, data);
        // The asset is stored under the map prefix with the name sanitised by MapStorage.escapeAssetName
        assertArrayEquals(data, read(asset));
        // Retrieving via the unsanitised name must return the same data (not a different key)
        assertArrayEquals(data, read(m.asset(unsafeName)));
    }

    // ===================================================================
    // Compression (1)
    // ===================================================================

    @Test
    void hiresGzipCompressionRoundtripsBytes() throws IOException {
        GridStorage grid = storage.map("world").hiresTiles();
        // Generate a payload large enough to exercise GZIP compression
        byte[] large = new byte[64 * 1024];
        for (int i = 0; i < large.length; i++) {
            large[i] = (byte) (i % 251);
        }
        write(grid.cell(0, 0), large);
        assertArrayEquals(large, read(grid, 0, 0));
    }
}
