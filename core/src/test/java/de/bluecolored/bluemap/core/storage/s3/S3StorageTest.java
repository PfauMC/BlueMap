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

import de.bluecolored.bluemap.core.storage.Storage;
import de.bluecolored.bluemap.core.storage.StorageContract;
import de.bluecolored.bluemap.core.storage.compression.Compression;
import org.junit.jupiter.api.Tag;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;

@Testcontainers(disabledWithoutDocker = true)
@Tag("integration")
class S3StorageTest extends StorageContract {

    @Container
    private static final MinIOContainer MINIO = new MinIOContainer("minio/minio:RELEASE.2025-04-22T22-12-26Z")
            .withUserName("minioadmin")
            .withPassword("minioadmin");

    @Override
    protected Storage createStorage() throws Exception {
        String bucket = "bm-" + UUID.randomUUID().toString().substring(0, 8);
        bootstrapBucket(bucket);

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        S3Credentials credentials = new S3Credentials("minioadmin", "minioadmin", "us-east-1", "s3");
        SigV4Signer signer = new SigV4Signer(credentials);
        RetryPolicy retryPolicy = new RetryPolicy(3, 1000);

        S3HttpClient http = new S3HttpClient(
                httpClient,
                signer,
                URI.create(MINIO.getS3URL()),
                bucket,
                /*pathStyleAccess*/ true,
                retryPolicy,
                Duration.ofSeconds(15));

        return new S3Storage(http, "test-prefix", Compression.GZIP);
    }

    private void bootstrapBucket(String bucket) throws Exception {
        org.testcontainers.containers.Container.ExecResult aliasResult = MINIO.execInContainer(
                "mc", "alias", "set", "bm-alias",
                "http://localhost:9000", "minioadmin", "minioadmin");
        if (aliasResult.getExitCode() != 0) {
            throw new IllegalStateException("mc alias set failed: " + aliasResult.getStderr());
        }

        org.testcontainers.containers.Container.ExecResult mbResult = MINIO.execInContainer(
                "mc", "mb", "bm-alias/" + bucket);
        if (mbResult.getExitCode() != 0) {
            throw new IllegalStateException("mc mb failed for bucket '" + bucket + "': " + mbResult.getStderr());
        }
    }
}
