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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class DependencyAuditTest {

    @Test
    void noForbiddenSdkClassesOnRuntimeClasspath() {
        ClassLoader classLoader = getClass().getClassLoader();
        String[] forbidden = {
            "software.amazon.awssdk.services.s3.S3Client",
            "software.amazon.awssdk.core.SdkClient",
            "com.amazonaws.services.s3.AmazonS3",
            "com.amazonaws.AmazonClientException",
            "io.minio.MinioClient",
            "io.minio.S3Base",
            "okhttp3.OkHttpClient",
            "okhttp3.Call",
            "org.apache.http.client.HttpClient",
            "org.apache.hc.client5.http.classic.HttpClient"
        };
        for (String className : forbidden) {
            assertThrows(ClassNotFoundException.class,
                    () -> Class.forName(className, false, classLoader),
                    "Expected " + className + " to be absent from the runtime classpath");
        }
    }
}
