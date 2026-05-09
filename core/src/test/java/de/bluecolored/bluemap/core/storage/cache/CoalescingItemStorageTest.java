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

import static org.junit.jupiter.api.Assertions.*;

public class CoalescingItemStorageTest {

    private static byte[] gzip(byte[] input) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (OutputStream os = Compression.GZIP.compress(out)) { os.write(input); }
        return out.toByteArray();
    }

    @Test
    public void concurrentReadsCollapseToOneUnderlyingCall() throws Exception {
        byte[] payload = "item-payload".getBytes();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch entered = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();

        ItemStorage stub = new StubItemStorage(() -> {
            reads.incrementAndGet();
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw new IOException(ie); }
            return new CompressedInputStream(new ByteArrayInputStream(payload), Compression.NONE);
        });

        CoalescingItemStorage decorator = new CoalescingItemStorage(stub);
        ExecutorService pool = Executors.newFixedThreadPool(100);
        try {
            List<CompletableFuture<byte[]>> futures = IntStream.range(0, 100)
                    .mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                        try (CompressedInputStream s = decorator.read()) {
                            return s == null ? null : s.readAllBytes();
                        } catch (IOException e) { throw new RuntimeException(e); }
                    }, pool))
                    .toList();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            release.countDown();
            for (CompletableFuture<byte[]> f : futures) {
                assertArrayEquals(payload, f.get(5, TimeUnit.SECONDS));
            }
        } finally { pool.shutdownNow(); }
        assertEquals(1, reads.get());
    }

    @Test
    public void exceptionPropagatesAndEvictsInflightEntry() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        ItemStorage stub = new StubItemStorage(() -> { reads.incrementAndGet(); throw new IOException("boom"); });
        CoalescingItemStorage decorator = new CoalescingItemStorage(stub);
        assertThrows(IOException.class, decorator::read);
        assertThrows(IOException.class, decorator::read);
        assertEquals(2, reads.get());
    }

    @Test
    public void nullDelegateResultReturnsNullAndEvictsEntry() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        ItemStorage stub = new StubItemStorage(() -> { reads.incrementAndGet(); return null; });
        CoalescingItemStorage decorator = new CoalescingItemStorage(stub);
        assertNull(decorator.read());
        assertNull(decorator.read());
        assertEquals(2, reads.get());
    }

    @Test
    public void passThroughOperationsDoNotTouchInflightMap() throws Exception {
        AtomicInteger reads = new AtomicInteger(), writes = new AtomicInteger(), deletes = new AtomicInteger(), existsCalls = new AtomicInteger();
        ItemStorage stub = new ItemStorage() {
            @Override public OutputStream write() { writes.incrementAndGet(); return new ByteArrayOutputStream(); }
            @Override public CompressedInputStream read() { reads.incrementAndGet(); return null; }
            @Override public void delete() { deletes.incrementAndGet(); }
            @Override public boolean exists() { existsCalls.incrementAndGet(); return true; }
            @Override public boolean isClosed() { return false; }
        };
        CoalescingItemStorage decorator = new CoalescingItemStorage(stub);
        decorator.write().close();
        decorator.delete();
        assertTrue(decorator.exists());
        assertFalse(decorator.isClosed());
        assertEquals(1, writes.get());
        assertEquals(1, deletes.get());
        assertEquals(1, existsCalls.get());
        assertEquals(0, reads.get());
    }

    @Test
    public void gzipDelegateBytesArePreservedWithOriginalCompression() throws Exception {
        byte[] raw = "hello world".getBytes();
        byte[] compressed = gzip(raw);
        ItemStorage stub = new StubItemStorage(() ->
                new CompressedInputStream(new ByteArrayInputStream(compressed), Compression.GZIP));
        CoalescingItemStorage decorator = new CoalescingItemStorage(stub);
        try (CompressedInputStream s = decorator.read()) {
            assertEquals(Compression.GZIP, s.getCompression(),
                    "cache-hit path must preserve the upstream compression token");
            assertArrayEquals(compressed, s.readAllBytes(),
                    "cached bytes must be the original compressed payload");
        }
    }

    @Test
    public void writeEvictsInflightEntryForNextRead() throws Exception {
        byte[] v1 = "version-1".getBytes();
        byte[] v2 = "version-2".getBytes();
        AtomicInteger reads = new AtomicInteger();
        ItemStorage stub = new ItemStorage() {
            @Override public CompressedInputStream read() {
                byte[] payload = reads.incrementAndGet() == 1 ? v1 : v2;
                return new CompressedInputStream(new ByteArrayInputStream(payload), Compression.NONE);
            }
            @Override public OutputStream write() { return new ByteArrayOutputStream(); }
            @Override public void delete() {}
            @Override public boolean exists() { return true; }
            @Override public boolean isClosed() { return false; }
        };
        CoalescingItemStorage decorator = new CoalescingItemStorage(stub);
        try (CompressedInputStream s = decorator.read()) {
            assertArrayEquals(v1, s.readAllBytes());
        }
        decorator.write().close();
        try (CompressedInputStream s = decorator.read()) {
            assertArrayEquals(v2, s.readAllBytes(), "write must invalidate any cached future so next read sees fresh data");
        }
        assertEquals(2, reads.get());
    }

    @Test
    public void independentInstancesHaveIndependentInflightMaps() throws Exception {
        AtomicInteger readsA = new AtomicInteger(), readsB = new AtomicInteger();
        ItemStorage stubA = new StubItemStorage(() -> { readsA.incrementAndGet(); return new CompressedInputStream(new ByteArrayInputStream("A".getBytes()), Compression.NONE); });
        ItemStorage stubB = new StubItemStorage(() -> { readsB.incrementAndGet(); return new CompressedInputStream(new ByteArrayInputStream("B".getBytes()), Compression.NONE); });
        CoalescingItemStorage a = new CoalescingItemStorage(stubA);
        CoalescingItemStorage b = new CoalescingItemStorage(stubB);
        try (CompressedInputStream sa = a.read(); CompressedInputStream sb = b.read()) {
            assertArrayEquals("A".getBytes(), sa.readAllBytes());
            assertArrayEquals("B".getBytes(), sb.readAllBytes());
        }
        assertEquals(1, readsA.get());
        assertEquals(1, readsB.get());
    }

    @FunctionalInterface
    private interface ReadFn { CompressedInputStream read() throws IOException; }

    private static class StubItemStorage implements ItemStorage {
        private final ReadFn fn;
        StubItemStorage(ReadFn fn) { this.fn = fn; }
        @Override public OutputStream write() { return new ByteArrayOutputStream(); }
        @Override public CompressedInputStream read() throws IOException { return fn.read(); }
        @Override public void delete() {}
        @Override public boolean exists() { return false; }
        @Override public boolean isClosed() { return false; }
    }
}
