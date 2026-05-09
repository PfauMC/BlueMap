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
package de.bluecolored.bluemap.common.web;

import de.bluecolored.bluemap.common.web.http.HttpHeader;
import de.bluecolored.bluemap.common.web.http.HttpRequest;
import de.bluecolored.bluemap.common.web.http.HttpResponse;
import de.bluecolored.bluemap.common.web.http.HttpStatusCode;
import de.bluecolored.bluemap.core.storage.GridStorage;
import de.bluecolored.bluemap.core.storage.ItemStorage;
import de.bluecolored.bluemap.core.storage.MapStorage;
import de.bluecolored.bluemap.core.storage.compression.CompressedInputStream;
import de.bluecolored.bluemap.core.storage.compression.Compression;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.DoublePredicate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

public class MapStorageRequestHandlerTest {

    private static final byte[] TILE_BYTES     = "tile-bytes".getBytes();
    private static final byte[] SETTINGS_BYTES = "{\"k\":\"settings\"}".getBytes();
    private static final byte[] TEXTURES_BYTES = "{\"k\":\"textures\"}".getBytes();
    private static final byte[] PLAYERS_BYTES  = "{\"k\":\"players\"}".getBytes();
    private static final byte[] MARKERS_BYTES  = "{\"k\":\"markers\"}".getBytes();
    private static final byte[] ASSET_BYTES    = ".foo {}".getBytes();

    private MapStorageRequestHandler newHandler() {
        return new MapStorageRequestHandler(new StubMapStorage(false));
    }

    // --- request helpers ---

    private static HttpRequest req(String path) {
        return req(path, new LinkedHashMap<>());
    }

    private static HttpRequest req(String path, Map<String, HttpHeader> headers) {
        HttpRequest r = new HttpRequest(InetAddress.getLoopbackAddress(), "GET", path);
        r.setHeaders(headers);
        return r;
    }

    private static HttpRequest reqWithHeader(String path, String name, String value) {
        Map<String, HttpHeader> h = new LinkedHashMap<>();
        h.put(name.toLowerCase(Locale.ROOT), new HttpHeader(name, value));
        return req(path, h);
    }

    // --- tests ---

    @Test
    public void testTileResponseEmitsMustRevalidateCacheControl() {
        HttpResponse r = newHandler().handle(req("/tiles/0/x0/z0.prbm"));
        assertEquals(HttpStatusCode.OK, r.getStatusCode());
        assertEquals("public, max-age=86400, must-revalidate", r.getHeader("Cache-Control").getValue());
    }

    @Test
    public void testTileResponseEmitsQuotedETag() {
        HttpResponse r = newHandler().handle(req("/tiles/0/x0/z0.prbm"));
        HttpHeader etag = r.getHeader("ETag");
        assertNotNull(etag);
        String v = etag.getValue();
        assertTrue(v.startsWith("\"") && v.endsWith("\""), "ETag must be DQUOTE-wrapped: " + v);
        assertEquals(18, v.length(), "ETag = quote + 16 hex + quote");
    }

    @Test
    public void testTileResponseEmitsVary() {
        HttpResponse r = newHandler().handle(req("/tiles/0/x0/z0.prbm"));
        assertNotNull(r.getHeader("Vary"));
        assertEquals("Accept-Encoding", r.getHeader("Vary").getValue());
    }

    @Test
    public void testIfNoneMatchExactReturns304() {
        String etag = MapStorageRequestHandler.computeStrongETag(TILE_BYTES);
        HttpResponse r = newHandler().handle(reqWithHeader("/tiles/0/x0/z0.prbm", "If-None-Match", etag));
        assertEquals(HttpStatusCode.NOT_MODIFIED, r.getStatusCode());
        assertNull(r.getBody());
        assertEquals(etag, r.getHeader("ETag").getValue());
        assertEquals("public, max-age=86400, must-revalidate", r.getHeader("Cache-Control").getValue());
        assertEquals("Accept-Encoding", r.getHeader("Vary").getValue());
    }

    @Test
    public void testIfNoneMatchWeakPrefixReturns304() {
        String etag = MapStorageRequestHandler.computeStrongETag(TILE_BYTES);
        HttpResponse r = newHandler().handle(reqWithHeader("/tiles/0/x0/z0.prbm", "If-None-Match", "W/" + etag));
        assertEquals(HttpStatusCode.NOT_MODIFIED, r.getStatusCode());
    }

    @Test
    public void testIfNoneMatchWildcardReturns304() {
        HttpResponse r = newHandler().handle(reqWithHeader("/tiles/0/x0/z0.prbm", "If-None-Match", "*"));
        assertEquals(HttpStatusCode.NOT_MODIFIED, r.getStatusCode());
    }

    @Test
    public void testIfNoneMatchMultiValueMatchReturns304() {
        String etag = MapStorageRequestHandler.computeStrongETag(TILE_BYTES);
        String hdr = "\"v1\", W/" + etag + ", \"v3\"";
        HttpResponse r = newHandler().handle(reqWithHeader("/tiles/0/x0/z0.prbm", "If-None-Match", hdr));
        assertEquals(HttpStatusCode.NOT_MODIFIED, r.getStatusCode());
    }

    @Test
    public void testIfNoneMatchUnquotedReturns304() {
        String etag = MapStorageRequestHandler.computeStrongETag(TILE_BYTES);
        String stripped = etag.substring(1, etag.length() - 1);
        HttpResponse r = newHandler().handle(reqWithHeader("/tiles/0/x0/z0.prbm", "If-None-Match", stripped));
        assertEquals(HttpStatusCode.NOT_MODIFIED, r.getStatusCode());
    }

    @Test
    public void testIfNoneMatchNoMatchReturns200WithBody() throws IOException {
        HttpResponse r = newHandler().handle(reqWithHeader("/tiles/0/x0/z0.prbm", "If-None-Match", "\"different-tag\""));
        assertEquals(HttpStatusCode.OK, r.getStatusCode());
        assertNotNull(r.getBody());
        try (InputStream b = r.getBody()) {
            assertArrayEquals(TILE_BYTES, b.readAllBytes());
        }
    }

    @Test
    public void testSettingsJsonEmitsMustRevalidate() {
        HttpResponse r = newHandler().handle(req("/settings.json"));
        assertEquals(HttpStatusCode.OK, r.getStatusCode());
        assertEquals("public, max-age=300, must-revalidate", r.getHeader("Cache-Control").getValue());
    }

    @Test
    public void testTexturesJsonEmitsMustRevalidate() {
        HttpResponse r = newHandler().handle(req("/textures.json"));
        assertEquals("public, max-age=300, must-revalidate", r.getHeader("Cache-Control").getValue());
    }

    @Test
    public void testLivePlayersEmitsNoCache() {
        HttpResponse r = newHandler().handle(req("/live/players.json"));
        assertEquals(HttpStatusCode.OK, r.getStatusCode());
        assertEquals("no-cache", r.getHeader("Cache-Control").getValue());
        assertNull(r.getHeader("ETag"), "live data must not carry ETag");
    }

    @Test
    public void testLiveMarkersEmitsNoCache() {
        HttpResponse r = newHandler().handle(req("/live/markers.json"));
        assertEquals("no-cache", r.getHeader("Cache-Control").getValue());
        assertNull(r.getHeader("ETag"));
    }

    @Test
    public void testAssetsEmitsMustRevalidateCacheControl() {
        HttpResponse r = newHandler().handle(req("/assets/foo.css"));
        assertEquals(HttpStatusCode.OK, r.getStatusCode());
        assertEquals("public, max-age=86400, must-revalidate", r.getHeader("Cache-Control").getValue());
    }

    @Test
    public void testCacheControlIsExactlyOneHeaderValue() {
        // Regression: HttpHeaderCarrier.addHeader is put-not-merge so emitting the header twice
        // clobbers the first value. The handler must emit the full comma-separated string in a
        // single addHeader call.
        HttpResponse r = newHandler().handle(req("/tiles/0/x0/z0.prbm"));
        String v = r.getHeader("Cache-Control").getValue();
        assertTrue(v.contains("public"), "must contain 'public'");
        assertTrue(v.contains("max-age=86400"), "must contain 'max-age=86400'");
        assertTrue(v.contains("must-revalidate"), "must contain 'must-revalidate'");
    }

    @Test
    public void testTileMissReturns204() {
        MapStorageRequestHandler h = new MapStorageRequestHandler(new StubMapStorage(true));
        HttpResponse r = h.handle(req("/tiles/0/x0/z0.prbm"));
        assertEquals(HttpStatusCode.NO_CONTENT, r.getStatusCode());
    }

    @Test
    public void testMissingMetadataReturns404() {
        MapStorageRequestHandler h = new MapStorageRequestHandler(new StubMapStorage(true));
        HttpResponse r = h.handle(req("/settings.json"));
        assertEquals(HttpStatusCode.NOT_FOUND, r.getStatusCode());
    }

    @Test
    public void testIOExceptionReturns500() {
        MapStorageRequestHandler h = new MapStorageRequestHandler(new ThrowingMapStorage());
        HttpResponse r = h.handle(req("/tiles/0/x0/z0.prbm"));
        assertEquals(HttpStatusCode.INTERNAL_SERVER_ERROR, r.getStatusCode());
    }

    @Test
    public void testEtagDoesNotChangeAcrossEncodings() {
        HttpResponse plain = newHandler().handle(req("/tiles/0/x0/z0.prbm"));
        HttpResponse gzipped = newHandler().handle(reqWithHeader("/tiles/0/x0/z0.prbm", "Accept-Encoding", "gzip"));
        assertEquals(
            plain.getHeader("ETag").getValue(),
            gzipped.getHeader("ETag").getValue(),
            "ETag must be identical regardless of Accept-Encoding (hashes decompressed body)"
        );
    }

    @Test
    public void testEtagMatchesHelperHandlesEdgeCases() {
        String tag = "\"abcd1234\"";
        assertFalse(MapStorageRequestHandler.etagMatches(null, tag));
        assertFalse(MapStorageRequestHandler.etagMatches(tag, null));
        assertFalse(MapStorageRequestHandler.etagMatches("\"other\"", tag));
        assertTrue(MapStorageRequestHandler.etagMatches(tag, tag));
        assertTrue(MapStorageRequestHandler.etagMatches("W/" + tag, tag));
        assertTrue(MapStorageRequestHandler.etagMatches("w/" + tag, tag));
        assertTrue(MapStorageRequestHandler.etagMatches("*", tag));
        assertTrue(MapStorageRequestHandler.etagMatches("\"v1\", " + tag + ", \"v3\"", tag));
        assertTrue(MapStorageRequestHandler.etagMatches("abcd1234", tag), "browser tolerance: unquoted form");
    }

    // --- test doubles ---

    @FunctionalInterface
    private interface GridReadFn {
        CompressedInputStream read(int x, int z) throws IOException;
    }

    @FunctionalInterface
    private interface ItemReadFn {
        CompressedInputStream read() throws IOException;
    }

    private static class StubGridStorage implements GridStorage {
        private final GridReadFn readFn;
        StubGridStorage(GridReadFn readFn) { this.readFn = readFn; }

        @Override public CompressedInputStream read(int x, int z) throws IOException { return readFn.read(x, z); }
        @Override public OutputStream write(int x, int z) { throw new UnsupportedOperationException(); }
        @Override public void delete(int x, int z) { throw new UnsupportedOperationException(); }
        @Override public boolean exists(int x, int z) { throw new UnsupportedOperationException(); }
        @Override public ItemStorage cell(int x, int z) { throw new UnsupportedOperationException(); }
        @Override public Stream<Cell> stream() { throw new UnsupportedOperationException(); }
        @Override public boolean isClosed() { return false; }
    }

    private static class StubItemStorage implements ItemStorage {
        private final ItemReadFn readFn;
        StubItemStorage(ItemReadFn readFn) { this.readFn = readFn; }

        @Override public CompressedInputStream read() throws IOException { return readFn.read(); }
        @Override public OutputStream write() { throw new UnsupportedOperationException(); }
        @Override public void delete() { throw new UnsupportedOperationException(); }
        @Override public boolean exists() { throw new UnsupportedOperationException(); }
        @Override public boolean isClosed() { return false; }
    }

    private static CompressedInputStream wrap(byte[] bytes) {
        return new CompressedInputStream(new ByteArrayInputStream(bytes), Compression.NONE);
    }

    private static class StubMapStorage implements MapStorage {
        private final boolean returnNull;
        StubMapStorage(boolean returnNull) { this.returnNull = returnNull; }

        @Override public GridStorage hiresTiles() {
            return new StubGridStorage((x, z) -> returnNull ? null : wrap(TILE_BYTES));
        }
        @Override public GridStorage lowresTiles(int lod) {
            return new StubGridStorage((x, z) -> returnNull ? null : wrap(TILE_BYTES));
        }
        @Override public GridStorage tileState() { throw new UnsupportedOperationException(); }
        @Override public GridStorage chunkState() { throw new UnsupportedOperationException(); }
        @Override public ItemStorage settings() { return new StubItemStorage(() -> returnNull ? null : wrap(SETTINGS_BYTES)); }
        @Override public ItemStorage textures() { return new StubItemStorage(() -> returnNull ? null : wrap(TEXTURES_BYTES)); }
        @Override public ItemStorage markers() { return new StubItemStorage(() -> returnNull ? null : wrap(MARKERS_BYTES)); }
        @Override public ItemStorage players() { return new StubItemStorage(() -> returnNull ? null : wrap(PLAYERS_BYTES)); }
        @Override public ItemStorage asset(String name) { return new StubItemStorage(() -> returnNull ? null : wrap(ASSET_BYTES)); }
        @Override public void delete(DoublePredicate onProgress) { throw new UnsupportedOperationException(); }
        @Override public boolean exists() { return true; }
        @Override public boolean isClosed() { return false; }
    }

    private static class ThrowingMapStorage implements MapStorage {
        @Override public GridStorage hiresTiles() {
            return new StubGridStorage((x, z) -> { throw new IOException("simulated storage failure"); });
        }
        @Override public GridStorage lowresTiles(int lod) { return hiresTiles(); }
        @Override public GridStorage tileState() { throw new UnsupportedOperationException(); }
        @Override public GridStorage chunkState() { throw new UnsupportedOperationException(); }
        @Override public ItemStorage settings() { return new StubItemStorage(() -> { throw new IOException("simulated"); }); }
        @Override public ItemStorage textures() { return new StubItemStorage(() -> { throw new IOException("simulated"); }); }
        @Override public ItemStorage markers() { return new StubItemStorage(() -> { throw new IOException("simulated"); }); }
        @Override public ItemStorage players() { return new StubItemStorage(() -> { throw new IOException("simulated"); }); }
        @Override public ItemStorage asset(String name) { return new StubItemStorage(() -> { throw new IOException("simulated"); }); }
        @Override public void delete(DoublePredicate onProgress) { throw new UnsupportedOperationException(); }
        @Override public boolean exists() { return true; }
        @Override public boolean isClosed() { return false; }
    }

}
