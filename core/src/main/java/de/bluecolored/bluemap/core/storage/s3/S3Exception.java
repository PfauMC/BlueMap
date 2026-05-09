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

public class S3Exception extends IOException {

    private final String code;
    private final String requestId;
    private final int httpStatus;

    public S3Exception(String code, String message, String requestId, int httpStatus) {
        super(message);
        this.code = code;
        this.requestId = requestId;
        this.httpStatus = httpStatus;
    }

    public S3Exception(String code, String message, String requestId, int httpStatus, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.requestId = requestId;
        this.httpStatus = httpStatus;
    }

    public String getCode() { return code; }

    public String getRequestId() { return requestId; }

    public int getHttpStatus() { return httpStatus; }

    @Override
    public String getMessage() {
        return code + ": " + super.getMessage() + " (RequestId=" + requestId + ", status=" + httpStatus + ")";
    }
}
