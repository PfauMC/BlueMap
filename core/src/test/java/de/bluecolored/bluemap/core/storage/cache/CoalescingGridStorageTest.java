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
package de.bluecolored.bluemap.core.storage.cache;

import de.bluecolored.bluemap.core.storage.GridStorage;
import de.bluecolored.bluemap.core.storage.ItemStorage;
import de.bluecolored.bluemap.core.storage.compression.CompressedInputStream;
import de.bluecolored.bluemap.core.storage.compression.Compression;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

public class CoalescingGridStorageTest {

    private static byte[] gzip(byte[] input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (OutputStream os = Compression.GZIP.compress(out)) {
            os.write(input);
        }
        return out.toByteArray();
    }

    @Test
    public void concurrentReadsCollapseToOneUnderlyingCall() throws Exception {
        byte[] payload = "tile-payload".getBytes();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();

        GridStorage stub = new StubGridStorage((x, z) -> {
            reads.incrementAndGet();
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw new IOException(ie); }
            return new CompressedInputStream(new ByteArrayInputStream(payload), Compression.NONE);
        });

        CoalescingGridStorage decorator = new CoalescingGridStorage(stub, "ns");

        ExecutorService pool = Executors.newFixedThreadPool(100);
        try {
            List<CompletableFuture<byte[]>> futures = IntStream.range(0, 100)
                    .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                        try (CompressedInputStream s = decorator.read(0, 0)) {
                            return s == null ? null : s.readAllBytes();
                        } catch (IOException e) { throw new RuntimeException(e); }
                    }, pool))
                    .toList();

            assertTrue(entered.await(5, TimeUnit.SECONDS), "underlying read should have started");
            release.countDown();

            for (CompletableFuture<byte[]> f : futures) {
                assertArrayEquals(payload, f.get(5, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, reads.get(), "100 concurrent readers should share one upstream call");
    }

    @Test
    public void exceptionPropagatesAndEvictsInflightEntry() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        GridStorage stub = new StubGridStorage((x, z) -> {
            reads.incrementAndGet();
            throw new IOException("boom");
        });
        CoalescingGridStorage decorator = new CoalescingGridStorage(stub, "ns");

        IOException first = assertThrows(IOException.class, () -> decorator.read(0, 0));
        assertTrue(first.getMessage().contains("boom") || (first.getCause() != null && String.valueOf(first.getCause()).contains("boom")),
                "exception should surface the underlying cause");

        assertThrows(IOException.class, () -> decorator.read(0, 0));
        assertEquals(2, reads.get(), "in-flight entry must be evicted on exceptional completion");
    }

    @Test
    public void nullDelegateResultReturnsNullAndEvictsEntry() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        GridStorage stub = new StubGridStorage((x, z) -> { reads.incrementAndGet(); return null; });
        CoalescingGridStorage decorator = new CoalescingGridStorage(stub, "ns");

        assertNull(decorator.read(0, 0));
        assertNull(decorator.read(0, 0));
        assertEquals(2, reads.get(), "null result must evict the entry so the next call re-invokes delegate");
    }

    @Test
    public void differentKeysDoNotShareFutures() throws Exception {
        byte[] a = "a".getBytes();
        byte[] b = "b".getBytes();
        AtomicInteger reads = new AtomicInteger();
        GridStorage stub = new StubGridStorage((x, z) -> {
            reads.incrementAndGet();
            return new CompressedInputStream(new ByteArrayInputStream(x == 0 ? a : b), Compression.NONE);
        });
        CoalescingGridStorage decorator = new CoalescingGridStorage(stub, "ns");

        try (CompressedInputStream sa = decorator.read(0, 0); CompressedInputStream sb = decorator.read(1, 1)) {
            assertArrayEquals(a, sa.readAllBytes());
            assertArrayEquals(b, sb.readAllBytes());
        }
        assertEquals(2, reads.get(), "different (x,z) coordinates yield different keys -> separate calls");
    }

    @Test
    public void passThroughOperationsDoNotTouchInflightMap() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger writes = new AtomicInteger();
        AtomicInteger deletes = new AtomicInteger();
        AtomicInteger existsCalls = new AtomicInteger();
        AtomicInteger streamCalls = new AtomicInteger();

        GridStorage stub = new GridStorage() {
            @Override public OutputStream write(int x, int z) { writes.incrementAndGet(); return new ByteArrayOutputStream(); }
            @Override public CompressedInputStream read(int x, int z) { reads.incrementAndGet(); return null; }
            @Override public void delete(int x, int z) { deletes.incrementAndGet(); }
            @Override public boolean exists(int x, int z) { existsCalls.incrementAndGet(); return true; }
            @Override public ItemStorage cell(int x, int z) { return new GridStorageCell(this, x, z); }
            @Override public Stream<Cell> stream() { streamCalls.incrementAndGet(); return Stream.empty(); }
            @Override public boolean isClosed() { return false; }
        };

        CoalescingGridStorage decorator = new CoalescingGridStorage(stub, "ns");

        decorator.write(0, 0).close();
        decorator.delete(0, 0);
        assertTrue(decorator.exists(0, 0));
        try (Stream<GridStorage.Cell> s = decorator.stream()) { s.count(); }
        assertFalse(decorator.isClosed());

        assertEquals(1, writes.get());
        assertEquals(1, deletes.get());
        assertEquals(1, existsCalls.get());
        assertEquals(1, streamCalls.get());
        assertEquals(0, reads.get());
    }

    @Test
    public void cellReadsFlowThroughCoalescing() throws Exception {
        byte[] payload = "via-cell".getBytes();
        AtomicInteger reads = new AtomicInteger();
        GridStorage stub = new StubGridStorage((x, z) -> {
            reads.incrementAndGet();
            return new CompressedInputStream(new ByteArrayInputStream(payload), Compression.NONE);
        });
        CoalescingGridStorage decorator = new CoalescingGridStorage(stub, "ns");

        ItemStorage cell = decorator.cell(0, 0);
        try (CompressedInputStream s1 = cell.read(); CompressedInputStream s2 = decorator.read(0, 0)) {
            assertArrayEquals(payload, s1.readAllBytes());
            assertArrayEquals(payload, s2.readAllBytes());
        }
        assertEquals(2, reads.get());
    }

    @Test
    public void gzipDelegateBytesArePreservedWithOriginalCompression() throws Exception {
        byte[] raw = "hello world".getBytes();
        byte[] compressed = gzip(raw);

        GridStorage stub = new StubGridStorage((x, z) ->
                new CompressedInputStream(new ByteArrayInputStream(compressed), Compression.GZIP));
        CoalescingGridStorage decorator = new CoalescingGridStorage(stub, "ns");

        try (CompressedInputStream s = decorator.read(0, 0)) {
            assertEquals(Compression.GZIP, s.getCompression(),
                    "cache-hit path must preserve the upstream compression token");
            assertArrayEquals(compressed, s.readAllBytes(),
                    "cached bytes must be the original compressed payload");
        }
    }

    @Test
    public void writeEvictsInflightEntryForSameKey() throws Exception {
        byte[] v1 = "version-1".getBytes();
        byte[] v2 = "version-2".getBytes();
        AtomicInteger reads = new AtomicInteger();
        GridStorage stub = new GridStorage() {
            @Override public CompressedInputStream read(int x, int z) {
                reads.incrementAndGet();
                byte[] payload = reads.get() == 1 ? v1 : v2;
                return new CompressedInputStream(new ByteArrayInputStream(payload), Compression.NONE);
            }
            @Override public OutputStream write(int x, int z) { return new ByteArrayOutputStream(); }
            @Override public void delete(int x, int z) {}
            @Override public boolean exists(int x, int z) { return true; }
            @Override public ItemStorage cell(int x, int z) { return new GridStorageCell(this, x, z); }
            @Override public Stream<Cell> stream() { return Stream.empty(); }
            @Override public boolean isClosed() { return false; }
        };
        CoalescingGridStorage decorator = new CoalescingGridStorage(stub, "ns");

        try (CompressedInputStream s = decorator.read(0, 0)) {
            assertArrayEquals(v1, s.readAllBytes());
        }
        decorator.write(0, 0).close();
        try (CompressedInputStream s = decorator.read(0, 0)) {
            assertArrayEquals(v2, s.readAllBytes(), "write must invalidate any cached future so next read sees fresh data");
        }
        assertEquals(2, reads.get());
    }

    // --- test double ---

    @FunctionalInterface
    private interface ReadFn { CompressedInputStream read(int x, int z) throws IOException; }

    private static class StubGridStorage implements GridStorage {
        private final ReadFn fn;
        StubGridStorage(ReadFn fn) { this.fn = fn; }
        @Override public OutputStream write(int x, int z) { return new ByteArrayOutputStream(); }
        @Override public CompressedInputStream read(int x, int z) throws IOException { return fn.read(x, z); }
        @Override public void delete(int x, int z) {}
        @Override public boolean exists(int x, int z) { return false; }
        @Override public ItemStorage cell(int x, int z) { return new GridStorageCell(this, x, z); }
        @Override public Stream<Cell> stream() { return Stream.empty(); }
        @Override public boolean isClosed() { return false; }
    }
}
