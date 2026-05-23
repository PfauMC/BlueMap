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
package de.bluecolored.bluemap.common.config.storage;

import de.bluecolored.bluemap.common.config.ConfigurationException;
import de.bluecolored.bluemap.common.debug.DebugDump;
import de.bluecolored.bluemap.core.logger.Logger;
import de.bluecolored.bluemap.core.storage.compression.Compression;
import de.bluecolored.bluemap.core.storage.s3.RetryPolicy;
import de.bluecolored.bluemap.core.storage.s3.S3Credentials;
import de.bluecolored.bluemap.core.storage.s3.S3Exception;
import de.bluecolored.bluemap.core.storage.s3.S3HttpClient;
import de.bluecolored.bluemap.core.storage.s3.S3Storage;
import de.bluecolored.bluemap.core.storage.s3.SigV4Signer;
import lombok.Getter;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.time.Duration;

@SuppressWarnings("FieldMayBeFinal")
@ConfigSerializable
@Getter
public class S3Config extends StorageConfig {

    private String endpoint = "https://s3.amazonaws.com";
    private String region = "us-east-1";
    private String bucket = "";
    private String prefix = "";
    @DebugDump(exclude = true) private String accessKey = "";
    @DebugDump(exclude = true) private String secretKey = "";
    private boolean pathStyleAccess = false;
    private int maxRetries = 3;
    private int retryCapMs = 10_000;
    private int connectTimeoutMs = 10_000;
    private int requestTimeoutMs = 30_000;
    private String compression = Compression.GZIP.getKey().getFormatted();

    public Compression getCompression() throws ConfigurationException {
        return parseKey(Compression.REGISTRY, compression, "compression");
    }

    @Override
    public S3Storage createStorage() throws ConfigurationException {
        if (bucket == null || bucket.isEmpty()) {
            throw new ConfigurationException("""
                    No S3 bucket configured!
                    Please set the 'bucket' field in your S3 storage configuration.
                    """.strip());
        }

        if (accessKey == null || accessKey.isEmpty()) {
            throw new ConfigurationException("""
                    No S3 access-key configured!
                    Please set the 'access-key' field in your S3 storage configuration.
                    Operators using environment-variable injection should substitute the value before BlueMap reads the config.
                    """.strip());
        }

        if (secretKey == null || secretKey.isEmpty()) {
            throw new ConfigurationException("""
                    No S3 secret-key configured!
                    Please set the 'secret-key' field in your S3 storage configuration.
                    Operators using environment-variable injection should substitute the value before BlueMap reads the config.
                    """.strip());
        }

        URI endpointUri;
        try {
            endpointUri = new URI(endpoint);
        } catch (URISyntaxException ex) {
            throw new ConfigurationException("""
                    The configured S3 endpoint is not a valid URI!
                    Please check your 'endpoint' setting in your S3 storage configuration.
                    """.strip(), ex);
        }

        String scheme = endpointUri.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new ConfigurationException("""
                    The configured S3 endpoint must use http or https!
                    Please check your 'endpoint' setting in your S3 storage configuration.
                    """.strip());
        }

        if ("http".equals(scheme)) {
            String host = endpointUri.getHost();
            if (!"localhost".equals(host) && !"127.0.0.1".equals(host) && !"::1".equals(host)) {
                Logger.global.logWarning(
                        "S3 storage endpoint uses plain HTTP and is not localhost — credentials will be sent in clear text. Use HTTPS in production.");
            }
        }

        // S3 (and S3-compatible servers) require a Content-Length header on uploads;
        // HTTP/1.1 always emits it for fixed-length bodies. Negotiating HTTP/2 via ALPN
        // against some endpoints/proxies leads to "MissingContentLength" rejections.
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        SigV4Signer signer = new SigV4Signer(new S3Credentials(accessKey, secretKey, region, "s3"));
        RetryPolicy retryPolicy = new RetryPolicy(maxRetries, retryCapMs);
        S3HttpClient s3HttpClient = new S3HttpClient(
                httpClient, signer, endpointUri, bucket, pathStyleAccess, retryPolicy,
                Duration.ofMillis(requestTimeoutMs));
        S3Storage storage = new S3Storage(s3HttpClient, prefix, getCompression());

        try {
            storage.initialize();
        } catch (S3Exception ex) {
            try { storage.close(); } catch (IOException ignored) {}
            throw new ConfigurationException("""
                    The configured S3 bucket was not found or BlueMap does not have permission to access it!
                    """.strip(), ex);
        } catch (IOException ex) {
            try { storage.close(); } catch (IOException ignored) {}
            throw new ConfigurationException("""
                    BlueMap could not connect to the configured S3 endpoint!
                    """.strip(), ex);
        }

        return storage;
    }
}
