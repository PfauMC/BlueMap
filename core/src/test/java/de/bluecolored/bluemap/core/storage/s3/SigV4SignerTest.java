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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SigV4SignerTest {

    private static final DateTimeFormatter AMZ_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter HTTP_DATE_FMT = DateTimeFormatter.RFC_1123_DATE_TIME;

    private static final String ACCESS_KEY = "AKIDEXAMPLE";
    private static final String SECRET_KEY = "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY";
    private static final String REGION = "us-east-1";
    private static final String SERVICE = "service";

    @ParameterizedTest(name = "{0}")
    @MethodSource("testCases")
    void signerMatchesAwsVectors(Path testDir) throws IOException {
        String name = testDir.getFileName().toString();

        Path reqFile = testDir.resolve(name + ".req");
        Path creqFile = testDir.resolve(name + ".creq");
        Path stsFile = testDir.resolve(name + ".sts");
        Path authzFile = testDir.resolve(name + ".authz");

        if (!Files.exists(reqFile) || !Files.exists(creqFile) || !Files.exists(stsFile) || !Files.exists(authzFile)) {
            return;
        }

        String reqText = Files.readString(reqFile, StandardCharsets.UTF_8);
        String expectedCreq = Files.readString(creqFile, StandardCharsets.UTF_8);
        String expectedSts = Files.readString(stsFile, StandardCharsets.UTF_8);
        String expectedAuthz = Files.readString(authzFile, StandardCharsets.UTF_8);

        ParsedRequest parsed = parseHttpRequest(reqText);

        SigV4Signer signer = new SigV4Signer(ACCESS_KEY, SECRET_KEY, REGION, SERVICE);

        // Determine the signing time from the X-Amz-Date header (or Date header)
        Instant timestamp = resolveTimestamp(parsed.headers);

        // Build extra headers map (all headers from the request except the HTTP line)
        // The signer receives pre-lowercased keys mapped to their value lists
        Map<String, List<String>> extraHeaders = new TreeMap<>();
        for (Map.Entry<String, List<String>> e : parsed.headers.entrySet()) {
            extraHeaders.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
        }

        // Encode the raw path+query per RFC 3986 before constructing the URI.
        // The .req files contain literal UTF-8 characters in paths (e.g. /ሴ) that
        // must be percent-encoded before the canonical request is formed.
        String encodedPathAndQuery = encodeRawRequestTarget(parsed.pathAndQuery);
        URI uri = URI.create("https://example.amazonaws.com" + encodedPathAndQuery);

        // Payload hash: SHA-256 of body bytes
        byte[] body = parsed.body;
        String payloadHash = SigV4Signer.lowerHex(SigV4Signer.sha256(body));

        SigV4Signer.SignedRequest result = signer.sign(parsed.method, uri, extraHeaders, payloadHash, timestamp);

        assertEquals(expectedCreq, result.canonicalRequest(),
                "Canonical request mismatch for " + name);
        assertEquals(expectedSts, result.stringToSign(),
                "String-to-sign mismatch for " + name);
        assertEquals("Authorization: " + expectedAuthz, "Authorization: " + result.authorizationHeader(),
                "Authorization header mismatch for " + name);
    }

    static Stream<Path> testCases() throws IOException, java.net.URISyntaxException {
        java.net.URL url = SigV4SignerTest.class.getResource("/aws-sig-v4-test-suite");
        if (url == null) throw new IOException("aws-sig-v4-test-suite not found on test classpath");
        Path root = Path.of(url.toURI());
        return Files.list(root).filter(Files::isDirectory).sorted();
    }

    private static Instant resolveTimestamp(Map<String, List<String>> headers) {
        List<String> amzDate = headers.get("X-Amz-Date");
        if (amzDate == null) amzDate = headers.get("x-amz-date");
        if (amzDate != null && !amzDate.isEmpty()) {
            return Instant.from(AMZ_DATE_FMT.parse(amzDate.get(0).trim()));
        }
        List<String> date = headers.get("Date");
        if (date == null) date = headers.get("date");
        if (date != null && !date.isEmpty()) {
            return Instant.from(HTTP_DATE_FMT.parse(date.get(0).trim()));
        }
        throw new IllegalArgumentException("No date header found in test vector request");
    }

    /**
     * Parses a raw HTTP/1.1 request text (as found in .req vector files) into method,
     * path+query string, header map, and body.
     *
     * The .req format is:
     *   METHOD PATH HTTP/1.1\r?\n
     *   Header: value\r?\n
     *   ...continuation lines start with whitespace (line-folding per RFC 2616)...
     *   \r?\n
     *   body
     *
     * Line-folded continuation lines are treated as additional comma-separated values of
     * the same header, matching the SigV4 canonical-header specification.
     */
    private static ParsedRequest parseHttpRequest(String raw) {
        // Normalise line endings
        String text = raw.replace("\r\n", "\n");

        int headerEnd = text.indexOf("\n\n");
        String headerSection;
        byte[] body;
        if (headerEnd < 0) {
            headerSection = text;
            body = new byte[0];
        } else {
            headerSection = text.substring(0, headerEnd);
            String bodyStr = text.substring(headerEnd + 2);
            body = bodyStr.getBytes(StandardCharsets.UTF_8);
        }

        String[] lines = headerSection.split("\n", -1);
        String requestLine = lines[0];
        String[] parts = requestLine.split(" ", 3);
        String method = parts[0];
        String pathAndQuery = parts[1];

        // Parse headers, handling line-folding (continuation lines start with whitespace).
        // SigV4 treats each continuation line as a separate comma-separated value.
        Map<String, List<String>> headers = new LinkedHashMap<>();
        String currentKey = null;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.isEmpty()) break;
            if ((line.charAt(0) == ' ' || line.charAt(0) == '\t') && currentKey != null) {
                // Continuation line — add as a new value element (joined with comma in canonical form)
                headers.computeIfAbsent(currentKey, k -> new ArrayList<>()).add(line.trim());
            } else {
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                currentKey = line.substring(0, colon);
                String value = line.substring(colon + 1);
                headers.computeIfAbsent(currentKey, k -> new ArrayList<>()).add(value);
            }
        }

        return new ParsedRequest(method, pathAndQuery, headers, body);
    }

    /**
     * Encodes a raw HTTP request-target (path + optional query) such that any non-ASCII
     * bytes are percent-encoded, leaving already-encoded %XX sequences and ASCII chars intact.
     * This converts literal UTF-8 characters in .req files to percent-encoded form.
     */
    private static String encodeRawRequestTarget(String target) {
        int queryStart = target.indexOf('?');
        String rawPath = queryStart < 0 ? target : target.substring(0, queryStart);
        String rawQuery = queryStart < 0 ? null : target.substring(queryStart + 1);

        String encPath = encodeNonAscii(rawPath);
        if (rawQuery == null) return encPath;
        return encPath + "?" + encodeNonAscii(rawQuery);
    }

    private static String encodeNonAscii(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder(bytes.length + 16);
        for (int i = 0; i < bytes.length; ) {
            int b = bytes[i] & 0xFF;
            if (b < 0x80) {
                sb.append((char) b);
                i++;
            } else {
                // Multi-byte UTF-8 sequence — percent-encode each byte
                sb.append('%').append(String.format("%02X", b));
                i++;
            }
        }
        return sb.toString();
    }

    private record ParsedRequest(
            String method,
            String pathAndQuery,
            Map<String, List<String>> headers,
            byte[] body
    ) {}
}
