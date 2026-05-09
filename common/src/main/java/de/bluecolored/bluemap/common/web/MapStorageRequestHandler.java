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

import de.bluecolored.bluemap.api.ContentTypeRegistry;
import de.bluecolored.bluemap.common.web.http.HttpHeader;
import de.bluecolored.bluemap.common.web.http.HttpRequest;
import de.bluecolored.bluemap.common.web.http.HttpRequestHandler;
import de.bluecolored.bluemap.common.web.http.HttpResponse;
import de.bluecolored.bluemap.common.web.http.HttpStatusCode;
import de.bluecolored.bluemap.core.logger.Logger;
import de.bluecolored.bluemap.core.storage.GridStorage;
import de.bluecolored.bluemap.core.storage.MapStorage;
import de.bluecolored.bluemap.core.storage.compression.CompressedInputStream;
import de.bluecolored.bluemap.core.storage.compression.Compression;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.NoSuchElementException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RequiredArgsConstructor
@Getter @Setter
public class MapStorageRequestHandler implements HttpRequestHandler {

    private static final Pattern TILE_PATTERN = Pattern.compile("tiles/([\\d/]+)/x(-?[\\d/]+)z(-?[\\d/]+).*");

    private static final String CACHE_CONTROL_TILE       = "public, max-age=86400, must-revalidate";
    private static final String CACHE_CONTROL_METADATA   = "public, max-age=300, must-revalidate";
    private static final String CACHE_CONTROL_LIVE       = "no-cache";
    private static final String CACHE_CONTROL_ASSET      = "public, max-age=86400, must-revalidate";

    private @NonNull MapStorage mapStorage;

    @Override
    public HttpResponse handle(HttpRequest request) {
        String path = request.getPath();
        if (path.startsWith("/")) path = path.substring(1);
        if (path.endsWith("/")) path = path.substring(0, path.length() - 1);

        try {
            Matcher tileMatcher = TILE_PATTERN.matcher(path);
            if (tileMatcher.matches()) {
                int lod = Integer.parseInt(tileMatcher.group(1));
                int x = Integer.parseInt(tileMatcher.group(2).replace("/", ""));
                int z = Integer.parseInt(tileMatcher.group(3).replace("/", ""));

                GridStorage gridStorage = lod == 0 ? mapStorage.hiresTiles() : mapStorage.lowresTiles(lod);
                try (CompressedInputStream in = gridStorage.read(x, z)) {
                    if (in == null) return new HttpResponse(HttpStatusCode.NO_CONTENT);
                    String contentType = lod == 0 ? "application/octet-stream" : "image/png";
                    return buildResponse(request, in, CACHE_CONTROL_TILE, contentType, true);
                }
            }

            // Metadata, live data, and assets dispatch
            String cacheControl;
            boolean useEtag;
            CompressedInputStream in;
            switch (path) {
                case "settings.json" -> {
                    in = mapStorage.settings().read();
                    cacheControl = CACHE_CONTROL_METADATA;
                    useEtag = true;
                }
                case "textures.json" -> {
                    in = mapStorage.textures().read();
                    cacheControl = CACHE_CONTROL_METADATA;
                    useEtag = true;
                }
                case "live/markers.json" -> {
                    in = mapStorage.markers().read();
                    cacheControl = CACHE_CONTROL_LIVE;
                    useEtag = false;
                }
                case "live/players.json" -> {
                    in = mapStorage.players().read();
                    cacheControl = CACHE_CONTROL_LIVE;
                    useEtag = false;
                }
                default -> {
                    if (path.startsWith("assets/")) {
                        in = mapStorage.asset(path.substring(7)).read();
                        cacheControl = CACHE_CONTROL_ASSET;
                        useEtag = true;
                    } else {
                        in = null;
                        cacheControl = null;
                        useEtag = false;
                    }
                }
            }

            if (in != null) {
                try (CompressedInputStream src = in) {
                    String contentType = ContentTypeRegistry.fromFileName(path);
                    return buildResponse(request, src, cacheControl, contentType, useEtag);
                }
            }
        } catch (NumberFormatException | NoSuchElementException ignore) {
        } catch (IOException ex) {
            Logger.global.logError("Failed to read map storage entry for web request '" + path + "'.", ex);
            return new HttpResponse(HttpStatusCode.INTERNAL_SERVER_ERROR);
        }

        return new HttpResponse(HttpStatusCode.NOT_FOUND);
    }

    // Builds either a 200 response (with body, ETag, Cache-Control, Vary) or a 304 response
    // (ETag, Cache-Control, NO body) depending on whether the request's If-None-Match matches.
    // useEtag=false skips the validator (live data — revalidation is meaningless).
    // src is consumed (closed) by this method for the 200 path; the caller's try-with-resources
    // also closes it on the 304 path, so double-close is safe (InputStream.close is idempotent).
    private HttpResponse buildResponse(HttpRequest request, CompressedInputStream src,
                                       String cacheControl, String contentType,
                                       boolean useEtag) throws IOException {
        // Buffer the raw (possibly compressed) bytes once so we can both hash the decompressed
        // representation for the ETag and pass the original encoding to writeToResponse.
        // Memory cost: ~1x body length per concurrent request.
        byte[] rawBytes = src.readAllBytes();
        Compression compression = src.getCompression();

        String etag = null;
        if (useEtag) {
            // ETag must hash the decoded representation so it is encoding-stable (RFC 9110 §8.8.3).
            byte[] decoded;
            try (InputStream dec = new CompressedInputStream(new ByteArrayInputStream(rawBytes), compression).decompress()) {
                decoded = dec.readAllBytes();
            }
            etag = computeStrongETag(decoded);

            HttpHeader inm = request.getHeader("If-None-Match");
            if (inm != null && etagMatches(inm.getValue(), etag)) {
                HttpResponse notModified = new HttpResponse(HttpStatusCode.NOT_MODIFIED);
                notModified.addHeader("ETag", etag);
                notModified.addHeader("Cache-Control", cacheControl);
                notModified.addHeader("Vary", "Accept-Encoding");
                return notModified;
            }
        }

        HttpResponse response = new HttpResponse(HttpStatusCode.OK);
        response.addHeader("Cache-Control", cacheControl);
        if (etag != null) response.addHeader("ETag", etag);
        response.addHeader("Vary", "Accept-Encoding");
        response.addHeader("Content-Type", contentType);
        // Reconstruct a fresh CompressedInputStream from the buffered raw bytes so writeToResponse
        // can pass through the original encoding for compression-capable clients.
        writeToResponse(new CompressedInputStream(new ByteArrayInputStream(rawBytes), compression), response, request);
        return response;
    }

    private void writeToResponse(CompressedInputStream data, HttpResponse response, HttpRequest request) throws IOException {
        Compression compression = data.getCompression();
        if (
                compression != Compression.NONE &&
                request.hasHeaderValue("Accept-Encoding", compression.getId())
        ) {
            response.addHeader("Content-Encoding", compression.getId());
            response.setBody(data);
        } else if (
                compression != Compression.GZIP &&
                !response.hasHeaderValue("Content-Type", "image/png") &&
                request.hasHeaderValue("Accept-Encoding", Compression.GZIP.getId())
        ) {
            response.addHeader("Content-Encoding", Compression.GZIP.getId());
            ByteArrayOutputStream byteOut = new ByteArrayOutputStream();
            try (data; OutputStream os = Compression.GZIP.compress(byteOut)) {
                try (InputStream dec = data.decompress()) {
                    dec.transferTo(os);
                }
            }
            byte[] compressedData = byteOut.toByteArray();
            response.setBody(new ByteArrayInputStream(compressedData));
        } else {
            response.setBody(data.decompress());
        }
    }

    // First 8 bytes (16 hex chars) of SHA-256(body), wrapped in DQUOTE per RFC 9110 §8.8.3.
    // 64-bit truncation: birthday collisions become likely around 2^32 distinct payloads;
    // a single map has at most ~10^6 tiles so collisions are vanishingly improbable.
    static String computeStrongETag(byte[] body) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(body);
            StringBuilder hex = new StringBuilder(18);
            hex.append('"');
            for (int i = 0; i < 8; i++) {
                hex.append(Character.forDigit((hash[i] >> 4) & 0xf, 16));
                hex.append(Character.forDigit(hash[i] & 0xf, 16));
            }
            hex.append('"');
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 not available", e);
        }
    }

    // RFC 9110 §13.1.2: If-None-Match uses weak comparison. Strip optional W/ prefix and
    // surrounding DQUOTEs from each token; compare to the same-stripped serverEtag.
    // Handles single value, multi-value (comma-separated), W/ weak prefix, wildcard *.
    static boolean etagMatches(String headerValue, String serverEtag) {
        if (headerValue == null || serverEtag == null) return false;
        String trimmed = headerValue.trim();
        if (trimmed.equals("*")) return true;
        String serverCore = stripWeakPrefixAndQuotes(serverEtag);
        for (String token : trimmed.split(",")) {
            String candidate = stripWeakPrefixAndQuotes(token.trim());
            if (candidate.equals(serverCore)) return true;
        }
        return false;
    }

    private static String stripWeakPrefixAndQuotes(String s) {
        String x = s;
        if (x.startsWith("W/") || x.startsWith("w/")) x = x.substring(2);
        if (x.length() >= 2 && x.charAt(0) == '"' && x.charAt(x.length() - 1) == '"') {
            x = x.substring(1, x.length() - 1);
        }
        return x;
    }

}
