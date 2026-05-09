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

import de.bluecolored.bluemap.core.logger.Logger;
import org.jetbrains.annotations.Nullable;

import javax.xml.stream.XMLStreamException;
import java.io.Closeable;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.random.RandomGenerator;

public class S3HttpClient implements Closeable {

    private static final boolean TRACE_REQUESTS = Boolean.getBoolean("bluemap.s3.traceRequests");

    private final HttpClient httpClient;
    private final SigV4Signer signer;
    private final URI endpoint;
    private final String bucket;
    private final boolean pathStyleAccess;
    private final RetryPolicy retryPolicy;
    private final Duration requestTimeout;
    private final RandomGenerator rng = RandomGenerator.getDefault();
    private volatile boolean closed = false;

    public S3HttpClient(HttpClient httpClient, SigV4Signer signer, URI endpoint, String bucket,
                        boolean pathStyleAccess, RetryPolicy retryPolicy, Duration requestTimeout) {
        this.httpClient = httpClient;
        this.signer = signer;
        this.endpoint = endpoint;
        this.bucket = bucket;
        this.pathStyleAccess = pathStyleAccess;
        this.retryPolicy = retryPolicy;
        this.requestTimeout = requestTimeout;

        if ("http".equals(endpoint.getScheme())) {
            String host = endpoint.getHost();
            if (!"localhost".equals(host) && !"127.0.0.1".equals(host)
                    && !"::1".equals(host) && !"[::1]".equals(host)) {
                Logger.global.logWarning("S3 endpoint is using plain HTTP with a non-localhost host (" + host
                        + "). Credentials will be sent in clear text.");
            }
        }
    }

    public byte[] putObject(String key, byte[] body, String contentType) throws IOException {
        URI uri = buildUri(key, null);
        Map<String, List<String>> extraHeaders = new LinkedHashMap<>();
        extraHeaders.put("Content-Type", List.of(contentType != null ? contentType : "application/octet-stream"));
        HttpResponse<byte[]> resp = sendSigned("PUT", uri, extraHeaders, body, false);
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            classifyAndThrow(resp);
        }
        return resp.body();
    }

    public @Nullable byte[] getObject(String key) throws IOException {
        URI uri = buildUri(key, null);
        HttpResponse<byte[]> resp = sendSigned("GET", uri, Map.of(), new byte[0], false);
        if (resp.statusCode() == 200) return resp.body();
        if (resp.statusCode() == 404) {
            S3XmlParsing.ParsedError err = parseErrorSafe(resp.body());
            if ("NoSuchKey".equals(err.code())) return null;
            throw new S3Exception(err.code(), err.message(), err.requestId(), 404);
        }
        classifyAndThrow(resp);
        return null; // unreachable
    }

    public boolean headObject(String key) throws IOException {
        URI uri = buildUri(key, null);
        HttpResponse<byte[]> resp = sendSigned("HEAD", uri, Map.of(), new byte[0], false);
        if (resp.statusCode() == 200) return true;
        if (resp.statusCode() == 404) {
            // HEAD responses have no body; AWS sets x-amz-error-code header instead.
            // NoSuchKey means the key is absent (normal); NoSuchBucket means the bucket
            // itself is misconfigured and must not be silently treated as "key not found".
            String errorCode = resp.headers().firstValue("x-amz-error-code").orElse(null);
            if ("NoSuchBucket".equals(errorCode)) {
                String requestId = resp.headers().firstValue("x-amz-request-id").orElse(null);
                throw new S3Exception("NoSuchBucket", "bucket " + bucket + " does not exist", requestId, 404);
            }
            return false;
        }
        classifyAndThrow(resp);
        return false; // unreachable
    }

    public void deleteObject(String key) throws IOException {
        URI uri = buildUri(key, null);
        HttpResponse<byte[]> resp = sendSigned("DELETE", uri, Map.of(), new byte[0], false);
        // 204 and 404 both succeed (idempotent delete)
        if (resp.statusCode() == 204 || resp.statusCode() == 404) return;
        classifyAndThrow(resp);
    }

    public S3XmlParsing.ListPage listObjectsV2(String prefix, @Nullable String continuationToken,
                                               @Nullable String delimiter, int maxKeys) throws IOException {
        // Build sorted canonical query string.
        // Encoding contract: query values are percent-encoded here via S3KeyEncoder so that
        // URI.create() does not further encode the already-encoded characters. The SigV4 signer's
        // buildCanonicalQueryString method decodes each value once and re-encodes strictly; because
        // S3KeyEncoder produces fully percent-encoded output with no pre-existing percent signs in
        // the input, the decode-and-re-encode round-trip is always a no-op and no double-encoding
        // can occur. Raw (unencoded) values must NOT be placed in the query string after this point.
        List<String[]> params = new ArrayList<>();
        params.add(new String[]{"list-type", "2"});
        if (prefix != null && !prefix.isEmpty()) {
            params.add(new String[]{"prefix", S3KeyEncoder.encodeQueryComponent(prefix)});
        }
        params.add(new String[]{"max-keys", String.valueOf(maxKeys)});
        if (continuationToken != null) {
            params.add(new String[]{"continuation-token", S3KeyEncoder.encodeQueryComponent(continuationToken)});
        }
        if (delimiter != null) {
            params.add(new String[]{"delimiter", S3KeyEncoder.encodeQueryComponent(delimiter)});
        }
        params.sort(Comparator.comparing(a -> a[0]));
        StringBuilder rawQuery = new StringBuilder();
        for (String[] kv : params) {
            if (rawQuery.length() > 0) rawQuery.append('&');
            rawQuery.append(kv[0]).append('=').append(kv[1]);
        }

        URI uri = buildUri("", rawQuery.toString());
        HttpResponse<byte[]> resp = sendSigned("GET", uri, Map.of(), new byte[0], false);
        if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
            try {
                return S3XmlParsing.parseListObjectsV2(resp.body());
            } catch (XMLStreamException e) {
                throw new IOException("Failed to parse ListObjectsV2 response", e);
            }
        }
        classifyAndThrow(resp);
        return null; // unreachable
    }

    public S3XmlParsing.DeleteResult deleteObjects(List<String> keys) throws IOException {
        // Build XML request body
        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Delete>");
        for (String key : keys) {
            xml.append("<Object><Key>").append(escapeXmlText(key)).append("</Key></Object>");
        }
        xml.append("<Quiet>false</Quiet></Delete>");
        byte[] body = xml.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);

        // Compute Content-MD5 (must be set before signing)
        String contentMd5 = computeContentMd5(body);
        Map<String, List<String>> extraHeaders = new LinkedHashMap<>();
        extraHeaders.put("Content-MD5", List.of(contentMd5));
        extraHeaders.put("Content-Type", List.of("application/xml"));

        // DeleteObjects uses POST /?delete; sign the body payload
        URI uri = buildUri("", "delete");
        HttpResponse<byte[]> resp = sendSigned("POST", uri, extraHeaders, body, true);
        if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
            try {
                return S3XmlParsing.parseDeleteObjects(resp.body());
            } catch (XMLStreamException e) {
                throw new IOException("Failed to parse DeleteObjects response", e);
            }
        }
        classifyAndThrow(resp);
        return null; // unreachable
    }

    public void headBucket() throws IOException {
        URI uri = buildUri("", null);
        HttpResponse<byte[]> resp = sendSigned("HEAD", uri, Map.of(), new byte[0], false);
        if (resp.statusCode() == 200) return;
        String requestId = resp.headers().firstValue("x-amz-request-id").orElse(null);
        if (resp.statusCode() == 404) {
            throw new S3Exception("NoSuchBucket", "bucket " + bucket + " does not exist", requestId, 404);
        }
        if (resp.statusCode() == 403) {
            throw new S3Exception("AccessDenied", "credentials lack access to bucket " + bucket, requestId, 403);
        }
        if (resp.statusCode() == 301) {
            throw new S3Exception("PermanentRedirect",
                    "bucket " + bucket + " is in a different region — fix `region` in config", requestId, 301);
        }
        classifyAndThrow(resp);
    }

    public boolean isClosed() { return closed; }

    @Override
    public void close() {
        closed = true;
        httpClient.close();
    }

    private URI buildUri(String key, @Nullable String rawQuery) {
        String encodedKey = key.isEmpty() ? "" : S3KeyEncoder.encodePath(key);
        String path;
        String authority;

        if (pathStyleAccess) {
            // virtual-hosted-style on a port-bearing endpoint (e.g. minio.local:9000) is rarely DNS-resolvable;
            // operator must set path-style-access: true for MinIO.
            path = "/" + bucket + (encodedKey.isEmpty() ? "" : "/" + encodedKey);
            authority = endpoint.getAuthority();
        } else {
            path = encodedKey.isEmpty() ? "/" : "/" + encodedKey;
            authority = bucket + "." + endpoint.getAuthority();
        }

        String uriStr = endpoint.getScheme() + "://" + authority + path;
        if (rawQuery != null && !rawQuery.isEmpty()) {
            uriStr = uriStr + "?" + rawQuery;
        }
        return URI.create(uriStr);
    }

    private HttpResponse<byte[]> sendSigned(String method, URI uri,
                                             Map<String, List<String>> extraHeaders,
                                             byte[] body, boolean signPayload) throws IOException {
        IOException lastException = null;

        for (int attempt = 0; attempt < retryPolicy.maxAttempts(); attempt++) {
            if (attempt > 0) {
                long delay = retryPolicy.backoffMillis(attempt - 1, rng);
                Logger.global.logWarning("S3 request retry attempt " + (attempt + 1)
                        + "/" + retryPolicy.maxAttempts() + " after " + delay + "ms for " + method + " " + uri.getRawPath());
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    InterruptedIOException ex = new InterruptedIOException("Interrupted during S3 retry backoff");
                    ex.initCause(ie);
                    throw ex;
                }
            }

            try {
                // Compute payload hash
                String payloadHash = signPayload
                        ? SigV4Signer.lowerHex(SigV4Signer.sha256(body))
                        : "UNSIGNED-PAYLOAD";

                Instant now = Instant.now();
                // Build signing headers: include host, x-amz-date, x-amz-content-sha256,
                // and all extra headers. The signer orders them alphabetically by name.
                // java.net.http adds Host automatically, so we add it explicitly for signing.
                Map<String, List<String>> signHeaders = new TreeMap<>();
                signHeaders.put("host", List.of(uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "")));
                // Extra headers (Content-Type, Content-MD5, etc.) must be in the signed set
                for (Map.Entry<String, List<String>> e : extraHeaders.entrySet()) {
                    signHeaders.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
                }
                // Compute amzDate first so we can add it to signed headers
                String amzDate = DateTimeFormatter
                        .ofPattern("yyyyMMdd'T'HHmmss'Z'")
                        .withZone(ZoneOffset.UTC)
                        .format(now);
                signHeaders.put("x-amz-content-sha256", List.of(payloadHash));
                signHeaders.put("x-amz-date", List.of(amzDate));

                SigV4Signer.SignedRequest signed = signer.sign(method, uri, signHeaders, payloadHash, now);

                // Build the HTTP request with the signed headers
                HttpRequest.Builder reqBuilder = HttpRequest.newBuilder(uri)
                        .timeout(requestTimeout)
                        .header("x-amz-date", signed.amzDate())
                        .header("x-amz-content-sha256", payloadHash)
                        .header("Authorization", signed.authorizationHeader());

                // Add extra headers to the actual request
                for (Map.Entry<String, List<String>> e : extraHeaders.entrySet()) {
                    for (String val : e.getValue()) {
                        reqBuilder.header(e.getKey(), val);
                    }
                }

                // Set method and body
                HttpRequest.BodyPublisher publisher = body.length > 0
                        ? HttpRequest.BodyPublishers.ofByteArray(body)
                        : HttpRequest.BodyPublishers.noBody();

                if ("HEAD".equals(method)) {
                    reqBuilder.method("HEAD", HttpRequest.BodyPublishers.noBody());
                } else {
                    reqBuilder.method(method, publisher);
                }

                HttpRequest request = reqBuilder.build();

                if (TRACE_REQUESTS) {
                    Logger.global.logDebug("S3 -> " + method + " " + uri.getRawPath()
                            + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()));
                }

                HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

                // Classify response
                String xmlCode = null;
                if (response.statusCode() >= 300) {
                    S3XmlParsing.ParsedError err = parseErrorSafe(response.body());
                    xmlCode = err.code();
                }

                RetryPolicy.Decision decision = retryPolicy.classifyHttp(response.statusCode(), xmlCode);
                if (decision == RetryPolicy.Decision.SUCCESS || decision == RetryPolicy.Decision.FAIL_NON_RETRYABLE) {
                    return response;
                }
                // RETRY — loop
                lastException = new S3Exception(xmlCode, "HTTP " + response.statusCode(),
                        response.headers().firstValue("x-amz-request-id").orElse(null),
                        response.statusCode());

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                InterruptedIOException ex = new InterruptedIOException("S3 request interrupted");
                ex.initCause(e);
                throw ex;
            } catch (IOException e) {
                RetryPolicy.Decision decision = retryPolicy.classifyException(e);
                if (decision != RetryPolicy.Decision.RETRY) {
                    throw e;
                }
                lastException = e;
            }
        }

        if (lastException != null) throw lastException;
        throw new IOException("S3 request failed after " + retryPolicy.maxAttempts() + " attempts");
    }

    private void classifyAndThrow(HttpResponse<byte[]> resp) throws IOException {
        S3XmlParsing.ParsedError err = parseErrorSafe(resp.body());
        String requestId = resp.headers().firstValue("x-amz-request-id")
                .orElse(err.requestId());
        throw new S3Exception(err.code(), err.message(), requestId, resp.statusCode());
    }

    private S3XmlParsing.ParsedError parseErrorSafe(byte[] body) {
        if (body == null || body.length == 0) return new S3XmlParsing.ParsedError(null, null, null);
        try {
            return S3XmlParsing.parseError(body);
        } catch (XMLStreamException e) {
            return new S3XmlParsing.ParsedError(null, null, null);
        }
    }

    private static String computeContentMd5(byte[] body) {
        try {
            byte[] md5 = MessageDigest.getInstance("MD5").digest(body);
            return Base64.getEncoder().encodeToString(md5);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 not available", e);
        }
    }

    private static String escapeXmlText(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
