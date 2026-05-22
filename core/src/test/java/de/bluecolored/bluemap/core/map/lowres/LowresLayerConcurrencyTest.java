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
package de.bluecolored.bluemap.core.map.lowres;

import com.flowpowered.math.vector.Vector2i;
import de.bluecolored.bluemap.core.storage.GridStorage;
import de.bluecolored.bluemap.core.storage.ItemStorage;
import de.bluecolored.bluemap.core.storage.compression.CompressedInputStream;
import de.bluecolored.bluemap.core.storage.compression.Compression;
import de.bluecolored.bluemap.core.util.Grid;
import de.bluecolored.bluemap.core.util.math.Color;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

public class LowresLayerConcurrencyTest {

    private static final int TILE_SIZE = 4;
    private static final int CELL_COUNT = 3;

    @Test
    public void concurrentSetsAndSavesNeverOverlapOnSameKey() throws Exception {
        TrackingGridStorage storage = new TrackingGridStorage(2);
        LowresLayer layer = new LowresLayer(storage, new Grid(TILE_SIZE), 2, 1, null);

        int writerThreads = 16;
        int saverThreads = 4;
        int iterationsPerWriter = 200;

        ExecutorService pool = Executors.newFixedThreadPool(writerThreads + saverThreads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch writersDone = new CountDownLatch(writerThreads);

        Color color = new Color().set(0xFFCC3344, false);

        for (int t = 0; t < writerThreads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < iterationsPerWriter; i++) {
                        int cellX = i % CELL_COUNT;
                        int cellZ = (i / CELL_COUNT) % CELL_COUNT;
                        int pixelX = i % TILE_SIZE;
                        int pixelZ = (i / TILE_SIZE) % TILE_SIZE;
                        layer.set(cellX, cellZ, pixelX, pixelZ, color, i, 0);
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } finally {
                    writersDone.countDown();
                }
            });
        }

        for (int t = 0; t < saverThreads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    while (writersDone.getCount() > 0) {
                        layer.save();
                        Thread.sleep(1);
                    }
                    layer.save();
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        start.countDown();
        assertTrue(writersDone.await(30, TimeUnit.SECONDS), "writers did not finish in time");
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "saver pool did not finish in time");
        layer.save();

        int maxOverlap = storage.maxConcurrentWrites();
        assertEquals(1, maxOverlap, "two PUTs raced on the same key — expected serialized writes");
    }

    @Test
    public void everyPixelWrittenIsPresentInFinalSnapshot() throws Exception {
        TrackingGridStorage storage = new TrackingGridStorage(1);
        LowresLayer layer = new LowresLayer(storage, new Grid(TILE_SIZE), 2, 1, null);

        // All writers share the SAME cell but own disjoint pixel positions (via stride),
        // so the race between save's eviction and concurrent markDirty is actually exercised.
        // Each pixel has exactly one expected value; loss of any markDirty means the
        // corresponding pixel ends up in the texture but never in a flushed snapshot.
        Map<PixelKey, Integer> expected = new HashMap<>();
        int writerThreads = 8;
        int pixelsPerTile = TILE_SIZE * TILE_SIZE;
        int totalPixels = pixelsPerTile;

        int sharedCellX = 0;
        int sharedCellZ = 0;

        for (int t = 0; t < writerThreads; t++) {
            for (int p = t; p < totalPixels; p += writerThreads) {
                int pixelX = p % TILE_SIZE;
                int pixelZ = p / TILE_SIZE;
                int rgb = 0xFF000000 | ((p * 0x010203) & 0xFFFFFF);
                expected.put(new PixelKey(sharedCellX, sharedCellZ, pixelX, pixelZ), rgb);
            }
        }

        ExecutorService pool = Executors.newFixedThreadPool(writerThreads + 2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch writersDone = new CountDownLatch(writerThreads);

        for (int tt = 0; tt < writerThreads; tt++) {
            final int t = tt;
            pool.submit(() -> {
                try {
                    start.await();
                    for (int p = t; p < totalPixels; p += writerThreads) {
                        int pixelX = p % TILE_SIZE;
                        int pixelZ = p / TILE_SIZE;
                        int rgb = 0xFF000000 | ((p * 0x010203) & 0xFFFFFF);
                        Color c = new Color().set(rgb, false);
                        layer.set(sharedCellX, sharedCellZ, pixelX, pixelZ, c, 0, 0);
                        Thread.yield();
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } finally {
                    writersDone.countDown();
                }
            });
        }

        for (int s = 0; s < 2; s++) {
            pool.submit(() -> {
                try {
                    start.await();
                    while (writersDone.getCount() > 0) {
                        layer.save();
                    }
                } catch (Exception ignored) {}
            });
        }

        start.countDown();
        assertTrue(writersDone.await(30, TimeUnit.SECONDS), "writers did not finish in time");
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "save pool did not finish in time");
        layer.save();

        byte[] bytes = storage.lastWritten(sharedCellX, sharedCellZ);
        assertNotNull(bytes, "no bytes stored for cell (" + sharedCellX + "," + sharedCellZ + ")");
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
        assertNotNull(img, "failed to decode PNG for cell");
        for (Map.Entry<PixelKey, Integer> e : expected.entrySet()) {
            PixelKey key = e.getKey();
            int actual = img.getRGB(key.pixelX, key.pixelZ) | 0xFF000000;
            assertEquals(e.getValue().intValue(), actual,
                    "pixel " + key + " not present in final snapshot");
        }
    }

    @Test
    public void cascadeAcrossTwoLayersAlsoSerializesWrites() throws Exception {
        TrackingGridStorage upperStorage = new TrackingGridStorage(1);
        TrackingGridStorage lowerStorage = new TrackingGridStorage(1);
        LowresLayer upper = new LowresLayer(upperStorage, new Grid(TILE_SIZE), 2, 2, null);
        LowresLayer lower = new LowresLayer(lowerStorage, new Grid(TILE_SIZE), 2, 1, upper);

        int writerThreads = 12;
        int iterationsPerWriter = 250;

        ExecutorService pool = Executors.newFixedThreadPool(writerThreads + 2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch writersDone = new CountDownLatch(writerThreads);

        for (int tt = 0; tt < writerThreads; tt++) {
            final int t = tt;
            pool.submit(() -> {
                try {
                    start.await();
                    Color c = new Color().set(0xFF000000 | (t * 0x010203 & 0xFFFFFF), false);
                    for (int i = 0; i < iterationsPerWriter; i++) {
                        int cellX = (t + i) % CELL_COUNT;
                        int cellZ = i % CELL_COUNT;
                        int pixelX = i % TILE_SIZE;
                        int pixelZ = (i / TILE_SIZE) % TILE_SIZE;
                        lower.set(cellX, cellZ, pixelX, pixelZ, c, i, 0);
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } finally {
                    writersDone.countDown();
                }
            });
        }

        for (int s = 0; s < 2; s++) {
            pool.submit(() -> {
                try {
                    start.await();
                    while (writersDone.getCount() > 0) {
                        lower.save();
                        upper.save();
                        Thread.sleep(1);
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        start.countDown();
        assertTrue(writersDone.await(30, TimeUnit.SECONDS), "writers did not finish");
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "save pool did not finish");
        lower.save();
        upper.save();

        assertEquals(1, lowerStorage.maxConcurrentWrites(), "lower layer PUTs raced");
        assertEquals(1, upperStorage.maxConcurrentWrites(), "upper (cascaded) layer PUTs raced");
    }

    private record PixelKey(int cellX, int cellZ, int pixelX, int pixelZ) {}

    private static final class TrackingGridStorage implements GridStorage {

        private final long putDelayMillis;
        private final ConcurrentHashMap<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, byte[]> lastBytes = new ConcurrentHashMap<>();
        private final AtomicInteger maxConcurrent = new AtomicInteger(0);

        TrackingGridStorage(long putDelayMillis) {
            this.putDelayMillis = putDelayMillis;
        }

        int maxConcurrentWrites() {
            return maxConcurrent.get();
        }

        @Nullable byte[] lastWritten(int x, int z) {
            return lastBytes.get(key(x, z));
        }

        private static String key(int x, int z) {
            return x + ":" + z;
        }

        @Override
        public OutputStream write(int x, int z) {
            String k = key(x, z);
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            AtomicInteger counter = inFlight.computeIfAbsent(k, kk -> new AtomicInteger());
            int now = counter.incrementAndGet();
            maxConcurrent.accumulateAndGet(now, Math::max);
            return new OutputStream() {
                private boolean closed = false;

                @Override public void write(int b) { buf.write(b); }
                @Override public void write(byte[] b, int off, int len) { buf.write(b, off, len); }

                @Override public void close() throws IOException {
                    if (closed) return;
                    closed = true;
                    try {
                        if (putDelayMillis > 0) Thread.sleep(putDelayMillis);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new IOException(ie);
                    }
                    lastBytes.put(k, buf.toByteArray());
                    counter.decrementAndGet();
                }
            };
        }

        @Override public @Nullable CompressedInputStream read(int x, int z) {
            byte[] b = lastBytes.get(key(x, z));
            if (b == null) return null;
            return new CompressedInputStream(new ByteArrayInputStream(b), Compression.NONE);
        }

        @Override public void delete(int x, int z) { lastBytes.remove(key(x, z)); }
        @Override public boolean exists(int x, int z) { return lastBytes.containsKey(key(x, z)); }
        @Override public ItemStorage cell(int x, int z) { return new GridStorageCell(this, x, z); }
        @Override public Stream<Cell> stream() { return Stream.empty(); }
        @Override public boolean isClosed() { return false; }
    }
}
