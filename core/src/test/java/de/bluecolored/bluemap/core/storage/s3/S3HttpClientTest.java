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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class S3HttpClientTest {

    private HttpServer server;
    private S3HttpClient client;

    private static final String TEST_BUCKET = "test-bucket";
    private static final S3Credentials CREDENTIALS = new S3Credentials(
            "AKIAIOSFODNN7EXAMPLE",
            "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY",
            "us-east-1",
            "s3"
    );

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 16);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();

        int port = server.getAddress().getPort();
        URI endpoint = URI.create("http://127.0.0.1:" + port);

        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();

        SigV4Signer signer = new SigV4Signer(CREDENTIALS);

        client = new S3HttpClient(httpClient, signer, endpoint, TEST_BUCKET,
                true, new RetryPolicy(3, 200), Duration.ofSeconds(2));
    }

    @AfterEach
    void tearDown() {
        client.close();
        server.stop(0);
    }

    @Test
    void putObjectSucceedsOnHttp200() throws IOException {
        AtomicReference<String> capturedAuth = new AtomicReference<>();
        server.createContext("/" + TEST_BUCKET + "/put-test.txt", exchange -> {
            capturedAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            sendResponse(exchange, 200, "");
        });

        assertDoesNotThrow(() -> client.putObject("put-test.txt", new byte[]{1, 2, 3}, "text/plain"));

        String auth = capturedAuth.get();
        assertNotNull(auth);
        assertTrue(auth.startsWith("AWS4-HMAC-SHA256 "),
                "Authorization header must begin with 'AWS4-HMAC-SHA256 '");
    }

    @Test
    void putObjectIncludesXAmzDateAndContentSha256Headers() throws IOException {
        AtomicReference<String> capturedDate = new AtomicReference<>();
        AtomicReference<String> capturedPayloadHash = new AtomicReference<>();
        server.createContext("/" + TEST_BUCKET + "/headers-test.txt", exchange -> {
            capturedDate.set(exchange.getRequestHeaders().getFirst("x-amz-date"));
            capturedPayloadHash.set(exchange.getRequestHeaders().getFirst("x-amz-content-sha256"));
            sendResponse(exchange, 200, "");
        });

        client.putObject("headers-test.txt", new byte[]{42}, "application/octet-stream");

        assertNotNull(capturedDate.get());
        assertTrue(capturedDate.get().matches("\\d{8}T\\d{6}Z"),
                "x-amz-date must match yyyyMMddTHHmmssZ format");
        assertEquals("UNSIGNED-PAYLOAD", capturedPayloadHash.get());
    }

    @Test
    void getObjectReturnsNullOn404NoSuchKey() throws IOException {
        server.createContext("/" + TEST_BUCKET + "/missing.txt", exchange ->
                sendResponse(exchange, 404, S3_ERROR_XML("NoSuchKey", "The specified key does not exist.", "rq1")));

        byte[] result = client.getObject("missing.txt");
        assertNull(result);
    }

    @Test
    void getObjectThrowsS3ExceptionOnNoSuchBucket() throws IOException {
        server.createContext("/" + TEST_BUCKET + "/any.txt", exchange ->
                sendResponse(exchange, 404, S3_ERROR_XML("NoSuchBucket", "The specified bucket does not exist.", "rq2")));

        S3Exception ex = assertThrows(S3Exception.class, () -> client.getObject("any.txt"));
        assertEquals("NoSuchBucket", ex.getCode());
        assertEquals(404, ex.getHttpStatus());
    }

    @Test
    void retriesOn5xxThenSucceeds() throws IOException {
        AtomicInteger callCount = new AtomicInteger();
        server.createContext("/" + TEST_BUCKET + "/retry-5xx.txt", exchange -> {
            int call = callCount.incrementAndGet();
            if (call <= 2) {
                sendResponse(exchange, 503, S3_ERROR_XML("InternalError", "We encountered an internal error.", "rq3"));
            } else {
                sendResponse(exchange, 200, "data");
            }
        });

        byte[] result = client.getObject("retry-5xx.txt");
        assertNotNull(result);
        assertEquals(3, callCount.get());
    }

    @Test
    void retriesOn503SlowDownThenSucceeds() throws IOException {
        AtomicInteger callCount = new AtomicInteger();
        server.createContext("/" + TEST_BUCKET + "/retry-slow.txt", exchange -> {
            int call = callCount.incrementAndGet();
            if (call <= 2) {
                sendResponse(exchange, 503, S3_ERROR_XML("SlowDown", "Please reduce your request rate.", "rq4"));
            } else {
                sendResponse(exchange, 200, "data");
            }
        });

        byte[] result = client.getObject("retry-slow.txt");
        assertNotNull(result);
        assertEquals(3, callCount.get());
    }

    @Test
    void retriesOn409OperationAbortedThenSucceeds() throws IOException {
        AtomicInteger callCount = new AtomicInteger();
        server.createContext("/" + TEST_BUCKET + "/conflict.txt", exchange -> {
            int call = callCount.incrementAndGet();
            if (call <= 2) {
                sendResponse(exchange, 409, S3_ERROR_XML("OperationAborted",
                        "A conflicting conditional operation is currently in progress against this resource.", "rqAbort"));
            } else {
                sendResponse(exchange, 200, "");
            }
        });

        assertDoesNotThrow(() -> client.putObject("conflict.txt", new byte[]{1, 2, 3}, "text/plain"));
        assertEquals(3, callCount.get());
    }

    @Test
    void doesNotRetry409PreconditionFailed() throws IOException {
        AtomicInteger callCount = new AtomicInteger();
        server.createContext("/" + TEST_BUCKET + "/precond.txt", exchange -> {
            callCount.incrementAndGet();
            sendResponse(exchange, 409, S3_ERROR_XML("PreconditionFailed",
                    "At least one of the preconditions you specified did not hold.", "rqPre"));
        });

        S3Exception ex = assertThrows(S3Exception.class,
                () -> client.putObject("precond.txt", new byte[]{1}, "text/plain"));
        assertEquals(409, ex.getHttpStatus());
        assertEquals("PreconditionFailed", ex.getCode());
        assertEquals(1, callCount.get());
    }

    @Test
    void failsAfterMaxAttemptsOn5xx() throws IOException {
        AtomicInteger callCount = new AtomicInteger();
        server.createContext("/" + TEST_BUCKET + "/always-500.txt", exchange -> {
            callCount.incrementAndGet();
            sendResponse(exchange, 500, S3_ERROR_XML("InternalError", "Server Error", "rq5"));
        });

        assertThrows(S3Exception.class, () -> client.getObject("always-500.txt"));
        assertEquals(3, callCount.get());
    }

    @Test
    void failsFastOn403WithoutRetry() throws IOException {
        AtomicInteger callCount = new AtomicInteger();
        server.createContext("/" + TEST_BUCKET + "/forbidden.txt", exchange -> {
            callCount.incrementAndGet();
            sendResponse(exchange, 403, S3_ERROR_XML("SignatureDoesNotMatch",
                    "The request signature we calculated does not match.", "rq6"));
        });

        S3Exception ex = assertThrows(S3Exception.class, () -> client.getObject("forbidden.txt"));
        assertEquals(403, ex.getHttpStatus());
        assertEquals("SignatureDoesNotMatch", ex.getCode());
        assertEquals(1, callCount.get());
    }

    @Test
    void headBucketThrowsOn404() throws IOException {
        server.createContext("/" + TEST_BUCKET, exchange ->
                sendResponse(exchange, 404, ""));

        S3Exception ex = assertThrows(S3Exception.class, () -> client.headBucket());
        assertEquals("NoSuchBucket", ex.getCode());
        assertEquals(404, ex.getHttpStatus());
    }

    @Test
    void headBucketThrowsOn403() throws IOException {
        server.createContext("/" + TEST_BUCKET, exchange ->
                sendResponse(exchange, 403, ""));

        S3Exception ex = assertThrows(S3Exception.class, () -> client.headBucket());
        assertEquals("AccessDenied", ex.getCode());
        assertEquals(403, ex.getHttpStatus());
    }

    @Test
    void interruptDuringRetryPreservesInterruptStatus() throws Exception {
        server.createContext("/" + TEST_BUCKET + "/hang.txt", exchange ->
                sendResponse(exchange, 503, S3_ERROR_XML("InternalError", "error", "rq7")));

        Thread[] workerThread = new Thread[1];
        Exception[] caughtException = new Exception[1];

        Thread worker = new Thread(() -> {
            try {
                client.getObject("hang.txt");
            } catch (InterruptedIOException e) {
                caughtException[0] = e;
                // preserve interrupt flag — don't clear it
            } catch (IOException e) {
                caughtException[0] = e;
            }
        });
        workerThread[0] = worker;
        worker.start();

        // Give the worker a moment to start retrying, then interrupt
        Thread.sleep(50);
        worker.interrupt();
        worker.join(3000);

        assertTrue(caughtException[0] instanceof InterruptedIOException,
                "Expected InterruptedIOException but got: " + caughtException[0]);
    }

    @Test
    void deleteObjectsSendsContentMd5() throws Exception {
        AtomicReference<byte[]> capturedBody = new AtomicReference<>();
        AtomicReference<String> capturedMd5 = new AtomicReference<>();

        server.createContext("/" + TEST_BUCKET, exchange -> {
            capturedMd5.set(exchange.getRequestHeaders().getFirst("Content-MD5"));
            capturedBody.set(exchange.getRequestBody().readAllBytes());
            sendResponse(exchange, 200, DELETE_RESULT_XML(List.of("a", "b")));
        });

        S3XmlParsing.DeleteResult result = client.deleteObjects(List.of("a", "b"));
        assertNotNull(result);

        // Verify the Content-MD5 matches the actual body
        byte[] body = capturedBody.get();
        assertNotNull(body);
        byte[] expectedMd5Bytes = MessageDigest.getInstance("MD5").digest(body);
        String expectedMd5 = Base64.getEncoder().encodeToString(expectedMd5Bytes);
        assertEquals(expectedMd5, capturedMd5.get());
    }

    @Test
    void listObjectsV2ParsesContents() throws IOException {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">" +
                "<IsTruncated>false</IsTruncated>" +
                "<Contents><Key>p/file1.txt</Key></Contents>" +
                "<Contents><Key>p/file2.txt</Key></Contents>" +
                "</ListBucketResult>";

        server.createContext("/" + TEST_BUCKET, exchange ->
                sendResponse(exchange, 200, xml));

        S3XmlParsing.ListPage page = client.listObjectsV2("p/", null, null, 1000);
        assertEquals(2, page.keys().size());
    }

    @Test
    void noUseOfSocketTimeoutException() throws IOException, java.net.URISyntaxException {
        // Resolve the source file relative to the compiled test-class location so the test
        // works regardless of the working directory (IDE, Gradle subproject, CI, etc.).
        // Test classes land at <module>/build/classes/java/test; main sources are at
        // <module>/src/main/java relative to the module root (4 levels up from classes/java/test).
        java.net.URL classRoot = S3HttpClientTest.class.getProtectionDomain().getCodeSource().getLocation();
        Path moduleRoot = Path.of(classRoot.toURI()) // .../build/classes/java/test
                .getParent()  // .../build/classes/java
                .getParent()  // .../build/classes
                .getParent()  // .../build
                .getParent(); // module root
        Path sourceFile = moduleRoot.resolve(
                "src/main/java/de/bluecolored/bluemap/core/storage/s3/S3HttpClient.java");
        String source = Files.readString(sourceFile);
        assertEquals(0, source.split("SocketTimeoutException").length - 1,
                "S3HttpClient.java must not reference SocketTimeoutException");
    }

    private static void sendResponse(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        } else {
            exchange.close();
        }
    }

    private static String S3_ERROR_XML(String code, String message, String requestId) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<Error><Code>" + code + "</Code><Message>" + message + "</Message>" +
                "<RequestId>" + requestId + "</RequestId></Error>";
    }

    private static String DELETE_RESULT_XML(List<String> keys) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?><DeleteResult>");
        for (String key : keys) {
            sb.append("<Deleted><Key>").append(key).append("</Key></Deleted>");
        }
        sb.append("</DeleteResult>");
        return sb.toString();
    }
}
