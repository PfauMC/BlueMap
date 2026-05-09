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

import de.bluecolored.bluemap.core.BlueMap;
import de.bluecolored.bluemap.core.storage.GridStorage;
import de.bluecolored.bluemap.core.storage.ItemStorage;
import de.bluecolored.bluemap.core.storage.compression.CompressedInputStream;
import de.bluecolored.bluemap.core.storage.compression.Compression;
import lombok.RequiredArgsConstructor;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;

@RequiredArgsConstructor
public class CoalescingGridStorage implements GridStorage {

    private record Cached(byte[] bytes, Compression compression) {}

    private final GridStorage delegate;
    private final String namespace;
    private final ConcurrentHashMap<String, CompletableFuture<Cached>> inflight = new ConcurrentHashMap<>();

    @Override
    public @Nullable CompressedInputStream read(int x, int z) throws IOException {
        String key = namespace + ":" + x + ":" + z;
        CompletableFuture<Cached> future = inflight.computeIfAbsent(key, k ->
                CompletableFuture
                        .supplyAsync(() -> {
                            try (CompressedInputStream stream = delegate.read(x, z)) {
                                if (stream == null) return null;
                                return new Cached(stream.readAllBytes(), stream.getCompression());
                            } catch (IOException e) {
                                throw new UncheckedIOException(e);
                            }
                        }, BlueMap.THREAD_POOL)
        );
        try {
            Cached cached = future.get();
            inflight.remove(key, future);
            if (cached == null) return null;
            return new CompressedInputStream(new ByteArrayInputStream(cached.bytes()), cached.compression());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            inflight.remove(key, future);
            throw new IOException("Interrupted waiting for coalesced read", ie);
        } catch (ExecutionException ee) {
            inflight.remove(key, future);
            Throwable cause = ee.getCause();
            if (cause instanceof UncheckedIOException uie) throw uie.getCause();
            if (cause instanceof IOException ioe) throw ioe;
            throw new IOException("Coalesced read failed", cause);
        }
    }

    @Override public OutputStream write(int x, int z) throws IOException {
        inflight.remove(namespace + ":" + x + ":" + z);
        return delegate.write(x, z);
    }

    @Override public void delete(int x, int z) throws IOException {
        inflight.remove(namespace + ":" + x + ":" + z);
        delegate.delete(x, z);
    }
    @Override public boolean exists(int x, int z) throws IOException { return delegate.exists(x, z); }
    @Override public ItemStorage cell(int x, int z) { return new GridStorageCell(this, x, z); }
    @Override public Stream<Cell> stream() throws IOException { return delegate.stream(); }
    @Override public boolean isClosed() { return delegate.isClosed(); }
}
