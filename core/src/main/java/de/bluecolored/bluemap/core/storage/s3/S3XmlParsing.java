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

import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

public final class S3XmlParsing {

    public record ParsedError(String code, String message, String requestId) {}

    public record ListPage(
            List<String> keys,
            List<String> commonPrefixes,
            String nextContinuationToken,
            boolean truncated
    ) {}

    public record DeleteEntry(String key, String code, String message, boolean failed) {}

    public record DeleteResult(List<DeleteEntry> deleted, List<DeleteEntry> errors) {}

    private static final XMLInputFactory STAX_FACTORY = createSecureFactory();

    private static XMLInputFactory createSecureFactory() {
        XMLInputFactory f = XMLInputFactory.newDefaultFactory();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, Boolean.FALSE);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, Boolean.FALSE);
        f.setProperty(XMLInputFactory.IS_COALESCING, Boolean.TRUE);
        try {
            f.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        } catch (IllegalArgumentException ignored) {
            // Not all StAX implementations expose JAXP property names; the SUPPORT_DTD
            // and IS_SUPPORTING_EXTERNAL_ENTITIES flags above are the primary XXE mitigations.
        }
        return f;
    }

    private S3XmlParsing() {}

    public static ParsedError parseError(byte[] body) throws XMLStreamException {
        if (body == null || body.length == 0) return new ParsedError(null, null, null);

        String code = null;
        String message = null;
        String requestId = null;

        XMLStreamReader reader = STAX_FACTORY.createXMLStreamReader(new ByteArrayInputStream(body));
        try {
            String currentElement = null;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    currentElement = reader.getLocalName();
                } else if (event == XMLStreamConstants.CHARACTERS && currentElement != null) {
                    String text = reader.getText();
                    switch (currentElement) {
                        case "Code" -> code = truncate(text, 1024);
                        case "Message" -> message = truncate(text, 1024);
                        case "RequestId" -> requestId = text;
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    currentElement = null;
                }
            }
        } finally {
            reader.close();
        }

        return new ParsedError(code, message, requestId);
    }

    public static ListPage parseListObjectsV2(byte[] body) throws XMLStreamException {
        List<String> keys = new ArrayList<>();
        List<String> commonPrefixes = new ArrayList<>();
        String nextContinuationToken = null;
        boolean truncated = false;

        XMLStreamReader reader = STAX_FACTORY.createXMLStreamReader(new ByteArrayInputStream(body));
        try {
            String currentElement = null;
            boolean inContents = false;
            boolean inCommonPrefixes = false;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    currentElement = reader.getLocalName();
                    if ("Contents".equals(currentElement)) inContents = true;
                    if ("CommonPrefixes".equals(currentElement)) inCommonPrefixes = true;
                } else if (event == XMLStreamConstants.CHARACTERS && currentElement != null) {
                    String text = reader.getText().trim();
                    if (text.isEmpty()) continue;
                    if (inContents && "Key".equals(currentElement)) {
                        keys.add(text);
                    } else if (inCommonPrefixes && "Prefix".equals(currentElement)) {
                        commonPrefixes.add(text);
                    } else if ("NextContinuationToken".equals(currentElement)) {
                        nextContinuationToken = text;
                    } else if ("IsTruncated".equals(currentElement)) {
                        truncated = "true".equalsIgnoreCase(text);
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String name = reader.getLocalName();
                    if ("Contents".equals(name)) inContents = false;
                    if ("CommonPrefixes".equals(name)) inCommonPrefixes = false;
                    currentElement = null;
                }
            }
        } finally {
            reader.close();
        }

        return new ListPage(keys, commonPrefixes, nextContinuationToken, truncated);
    }

    public static DeleteResult parseDeleteObjects(byte[] body) throws XMLStreamException {
        List<DeleteEntry> deleted = new ArrayList<>();
        List<DeleteEntry> errors = new ArrayList<>();

        XMLStreamReader reader = STAX_FACTORY.createXMLStreamReader(new ByteArrayInputStream(body));
        try {
            String currentElement = null;
            boolean inDeleted = false;
            boolean inError = false;
            String currentKey = null;
            String currentCode = null;
            String currentMessage = null;

            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    currentElement = reader.getLocalName();
                    if ("Deleted".equals(currentElement)) { inDeleted = true; currentKey = null; currentCode = null; currentMessage = null; }
                    if ("Error".equals(currentElement)) { inError = true; currentKey = null; currentCode = null; currentMessage = null; }
                } else if (event == XMLStreamConstants.CHARACTERS && currentElement != null) {
                    String text = reader.getText().trim();
                    if (text.isEmpty()) continue;
                    if ((inDeleted || inError) && "Key".equals(currentElement)) currentKey = text;
                    if (inError && "Code".equals(currentElement)) currentCode = text;
                    if (inError && "Message".equals(currentElement)) currentMessage = text;
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String name = reader.getLocalName();
                    if ("Deleted".equals(name)) {
                        if (currentKey != null) deleted.add(new DeleteEntry(currentKey, null, null, false));
                        inDeleted = false;
                    }
                    if ("Error".equals(name)) {
                        if (currentKey != null) errors.add(new DeleteEntry(currentKey, currentCode, currentMessage, true));
                        inError = false;
                    }
                    currentElement = null;
                }
            }
        } finally {
            reader.close();
        }

        return new DeleteResult(deleted, errors);
    }

    private static String truncate(String s, int maxLen) {
        if (s == null || s.length() <= maxLen) return s;
        return s.substring(0, maxLen);
    }
}
