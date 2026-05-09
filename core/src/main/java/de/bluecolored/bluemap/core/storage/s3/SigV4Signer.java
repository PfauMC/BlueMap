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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

public final class SigV4Signer {

    private static final DateTimeFormatter DATE_TIME_FMT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private final String accessKeyId;
    private final String secretAccessKey;
    private final String region;
    private final String service;

    public SigV4Signer(S3Credentials credentials) {
        this.accessKeyId = credentials.accessKeyId();
        this.secretAccessKey = credentials.secretAccessKey();
        this.region = credentials.region();
        this.service = credentials.service();
    }

    public SigV4Signer(String accessKeyId, String secretAccessKey, String region, String service) {
        this.accessKeyId = accessKeyId;
        this.secretAccessKey = secretAccessKey;
        this.region = region;
        this.service = service;
    }

    public record SignedRequest(
            String canonicalRequest,
            String stringToSign,
            String authorizationHeader,
            String amzDate,
            String dateStamp
    ) {}

    public SignedRequest sign(String method, URI uri, Map<String, List<String>> extraHeaders,
                             String payloadSha256Hex, Instant timestamp) {
        String amzDate = DATE_TIME_FMT.format(timestamp);
        String dateStamp = DATE_FMT.format(timestamp);

        // Build the ordered header map: lowercase name -> trimmed value(s)
        // Must include host (derived from URI) and any extra headers.
        TreeMap<String, List<String>> headerMap = new TreeMap<>();
        for (Map.Entry<String, List<String>> e : extraHeaders.entrySet()) {
            headerMap.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
        }
        // Ensure x-amz-date and x-amz-content-sha256 are present from extraHeaders
        // (callers are responsible for passing them)

        // Canonical URI: RFC 3986 strict path, with or without path normalization.
        // AWS S3 spec: do not normalize URI paths; keys may contain "..".
        String rawPath = uri.getRawPath();
        if (rawPath == null || rawPath.isEmpty()) rawPath = "/";
        String canonicalUri;
        if ("s3".equals(service)) {
            // No normalization for S3: re-encode each segment individually (already done by S3KeyEncoder)
            canonicalUri = rawPath;
        } else {
            // Normalize per RFC 3986 §6.2.2.3 for non-S3 services
            canonicalUri = uri.normalize().getRawPath();
            if (canonicalUri == null || canonicalUri.isEmpty()) canonicalUri = "/";
        }

        // Canonical query string: sort by encoded name then by encoded value
        String canonicalQueryString = buildCanonicalQueryString(uri.getRawQuery());

        // Canonical headers: lowercase name, trimmed/compacted value, sorted
        StringBuilder canonHeaders = new StringBuilder();
        StringBuilder signedHeadersList = new StringBuilder();
        boolean firstHeader = true;
        for (Map.Entry<String, List<String>> e : headerMap.entrySet()) {
            String name = e.getKey();
            List<String> values = e.getValue();
            canonHeaders.append(name).append(':');
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) canonHeaders.append(',');
                // Trim leading/trailing whitespace and collapse internal whitespace runs to single space
                canonHeaders.append(trimHeader(values.get(i)));
            }
            canonHeaders.append('\n');
            if (!firstHeader) signedHeadersList.append(';');
            signedHeadersList.append(name);
            firstHeader = false;
        }
        String signedHeaders = signedHeadersList.toString();

        String canonicalRequest =
                method + "\n" +
                canonicalUri + "\n" +
                canonicalQueryString + "\n" +
                canonHeaders + "\n" +
                signedHeaders + "\n" +
                payloadSha256Hex;

        // String-to-sign
        String credentialScope = dateStamp + "/" + region + "/" + service + "/aws4_request";
        String stringToSign =
                "AWS4-HMAC-SHA256\n" +
                amzDate + "\n" +
                credentialScope + "\n" +
                lowerHex(sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8)));

        // Signing key derivation
        byte[] kSecret = ("AWS4" + secretAccessKey).getBytes(StandardCharsets.UTF_8);
        byte[] kDate = hmacSha256(kSecret, dateStamp.getBytes(StandardCharsets.UTF_8));
        byte[] kRegion = hmacSha256(kDate, region.getBytes(StandardCharsets.UTF_8));
        byte[] kService = hmacSha256(kRegion, service.getBytes(StandardCharsets.UTF_8));
        byte[] kSigning = hmacSha256(kService, "aws4_request".getBytes(StandardCharsets.UTF_8));
        String signature = lowerHex(hmacSha256(kSigning, stringToSign.getBytes(StandardCharsets.UTF_8)));

        String authorizationHeader =
                "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/" + credentialScope +
                ", SignedHeaders=" + signedHeaders +
                ", Signature=" + signature;

        return new SignedRequest(canonicalRequest, stringToSign, authorizationHeader, amzDate, dateStamp);
    }

    public SignedRequest sign(String method, URI uri, Map<String, List<String>> extraHeaders,
                             byte[] payload, Instant timestamp) {
        String payloadHash = lowerHex(sha256(payload));
        return sign(method, uri, extraHeaders, payloadHash, timestamp);
    }

    /**
     * Builds the canonical query string component of a SigV4 canonical request.
     *
     * <p><strong>Encoding contract:</strong> callers must pass the raw (undecoded) query string
     * exactly as it appears in the URI, with values that are either unencoded or percent-encoded
     * but <em>not</em> double-encoded. This method decodes each name and value once (via
     * {@link #pctDecode}) and then re-encodes strictly per RFC 3986 before sorting and joining.
     * If callers pre-encode values (e.g. via {@link S3KeyEncoder#encodeQueryComponent}) before
     * assembling the query string, and those values happen to contain only unreserved characters
     * after encoding, the decode-and-re-encode round-trip is a no-op. If a caller pre-encodes
     * a value that already contained percent signs, those will be decoded and re-encoded,
     * producing the canonical form. Callers must therefore either pass raw (unencoded) values
     * or fully-encoded values — never partially-encoded ones.
     */
    private static String buildCanonicalQueryString(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) return "";

        // Parse query: split on & and =, then re-encode each name and value per RFC 3986,
        // sort by encoded name (then by encoded value if names are equal), rejoin.
        String[] pairs = rawQuery.split("&", -1);
        List<String[]> encoded = new ArrayList<>(pairs.length);
        for (String pair : pairs) {
            int eq = pair.indexOf('=');
            String rawName, rawValue;
            if (eq < 0) {
                rawName = pair;
                rawValue = "";
            } else {
                rawName = pair.substring(0, eq);
                rawValue = pair.substring(eq + 1);
            }
            // Decode then re-encode through our strict RFC 3986 encoder
            String encName = S3KeyEncoder.encodeQueryComponent(pctDecode(rawName));
            String encValue = S3KeyEncoder.encodeQueryComponent(pctDecode(rawValue));
            encoded.add(new String[]{encName, encValue});
        }
        encoded.sort((a, b) -> {
            int c = a[0].compareTo(b[0]);
            return c != 0 ? c : a[1].compareTo(b[1]);
        });
        StringBuilder sb = new StringBuilder();
        for (String[] kv : encoded) {
            if (sb.length() > 0) sb.append('&');
            sb.append(kv[0]).append('=').append(kv[1]);
        }
        return sb.toString();
    }

    /**
     * Decodes percent-encoded sequences in a query component.
     * Used to normalize encoding before re-encoding via S3KeyEncoder.
     */
    private static String pctDecode(String s) {
        if (!s.contains("%")) return s;
        byte[] bytes = s.getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[bytes.length];
        int outLen = 0;
        for (int i = 0; i < bytes.length; ) {
            if (bytes[i] == '%' && i + 2 < bytes.length) {
                int hi = hexVal(bytes[i + 1]);
                int lo = hexVal(bytes[i + 2]);
                if (hi >= 0 && lo >= 0) {
                    out[outLen++] = (byte) ((hi << 4) | lo);
                    i += 3;
                    continue;
                }
            }
            out[outLen++] = bytes[i++];
        }
        return new String(out, 0, outLen, StandardCharsets.UTF_8);
    }

    private static int hexVal(byte b) {
        if (b >= '0' && b <= '9') return b - '0';
        if (b >= 'A' && b <= 'F') return b - 'A' + 10;
        if (b >= 'a' && b <= 'f') return b - 'a' + 10;
        return -1;
    }

    /**
     * Trims leading/trailing whitespace from a header value and collapses internal
     * whitespace runs to a single space, per SigV4 canonical-headers specification.
     */
    private static String trimHeader(String value) {
        return value.trim().replaceAll("\\s+", " ");
    }

    static String lowerHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }

    static byte[] sha256(byte[] input) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static byte[] hmacSha256(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 not available", e);
        }
    }
}
