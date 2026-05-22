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

import java.io.IOException;
import java.util.random.RandomGenerator;

public final class RetryPolicy {

    public enum Decision { RETRY, FAIL_NON_RETRYABLE, SUCCESS }

    private final int maxAttempts;
    private final long retryCapMillis;

    public RetryPolicy(int maxAttempts, long retryCapMillis) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.retryCapMillis = retryCapMillis;
    }

    public int maxAttempts() { return maxAttempts; }

    public long backoffMillis(int attemptIndex, RandomGenerator rng) {
        long exp = Math.min(retryCapMillis, 200L * (1L << Math.min(20, attemptIndex)));
        double jitter = 1.0 + (rng.nextDouble() - 0.5) * 0.5;
        return (long) Math.max(0, exp * jitter);
    }

    public Decision classifyHttp(int statusCode, String xmlErrorCode) {
        if (statusCode >= 200 && statusCode < 300) return Decision.SUCCESS;
        if (statusCode == 503 && "SlowDown".equals(xmlErrorCode)) return Decision.RETRY;
        // AWS documents OperationAborted as transient — typically raised when concurrent
        // operations on the same key collide, or when the backend is internally rebalancing.
        // Prescribed handling is retry with exponential backoff. Scoped strictly to this
        // code so other 409s (e.g. PreconditionFailed from If-Match) still fail fast.
        if (statusCode == 409 && "OperationAborted".equals(xmlErrorCode)) return Decision.RETRY;
        if (statusCode >= 500 && statusCode < 600) return Decision.RETRY;
        return Decision.FAIL_NON_RETRYABLE;
    }

    public Decision classifyException(Throwable t) {
        if (t instanceof InterruptedException) return Decision.FAIL_NON_RETRYABLE;
        if (t instanceof IllegalArgumentException) return Decision.FAIL_NON_RETRYABLE;
        if (t instanceof IOException) return Decision.RETRY;
        return Decision.FAIL_NON_RETRYABLE;
    }
}
