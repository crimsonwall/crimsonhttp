/*
 * Crimson HTTP - HTTP Request/Response Viewer for Zap.
 *
 * Renico Koen / Crimson Wall / 2026
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.crimsonwall.crimsonhttp.ui;

import com.crimsonwall.crimsonhttp.redact.RedactConfig;
import com.crimsonwall.crimsonhttp.redact.RedactEntry;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyledDocument;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

/**
 * Formats and syntax-highlights HTTP body content for display in a {@link StyledDocument}.
 *
 * <p>Supports JSON (pretty-print + highlight), XML (pretty-print + highlight), HTML (highlight
 * only), and plain text. Bodies exceeding size thresholds are either truncated or displayed as
 * plain text without highlighting to keep the UI responsive.
 *
 * <p>Redaction is performed before formatting by pre-processing the body string with {@link
 * #redactBody}. Redacted spans are wrapped in {@link #REDACT_START} / {@link #REDACT_END} sentinel
 * sequences so the per-format highlighters can emit them in the redacted colour without disrupting
 * surrounding syntax colouring.
 */
public final class BodyBeautifier {

    private static final Logger LOGGER = LogManager.getLogger(BodyBeautifier.class);

    private static final int MAX_BODY_BYTES = 1_000_000;
    private static final int MAX_BODY_LENGTH = 500_000;
    private static final int MAX_JSON_INPUT = 300_000;
    private static final int MAX_XML_INPUT = 50_000;
    private static final int MAX_HTML_INPUT = 50_000;
    private static final int MAX_PLAIN_LENGTH = 200_000;
    private static final int MAX_TAG_NAME_LENGTH = 1_024;
    private static final int MAX_ATTR_VALUE_LENGTH = 10_000;
    private static final int MAX_XML_ATTRS = 100;
    private static final int MAX_XML_HIGHLIGHT_TOTAL = 500_000;
    private static final int MAX_INSERT_LENGTH = 100_000;
    private static final int MAX_REDACT_INPUT = 100_000;

    private static final String TRUNCATION_NOTE = "\n\n--- Body truncated at %d characters ---\n";

    private static final Pattern CONTENT_TYPE_CHARSET = Pattern.compile("charset=([\\w-]+)");

    /** Start sentinel for a redacted span embedded in pre-processed body text. */
    static final String REDACT_START = "\001R[";

    /** End sentinel for a redacted span. */
    static final String REDACT_END = "]\001";

    /** Thread-local factory hardened against XXE and XML bombs. */
    private static final ThreadLocal<TransformerFactory> TRANSFORMER_FACTORY =
            ThreadLocal.withInitial(
                    () -> {
                        TransformerFactory tf = TransformerFactory.newInstance();
                        boolean secureProcessingEnabled = false;
                        try {
                            tf.setFeature(
                                    "http://javax.xml.XMLConstants/feature/secure-processing",
                                    true);
                            secureProcessingEnabled = true;
                        } catch (Exception e) {
                            LOGGER.warn("Secure processing not supported, XXE protection disabled");
                        }
                        try {
                            tf.setFeature(
                                    "http://apache.org/xml/features/disallow-doctype-decl",
                                    true);
                        } catch (Exception e) {
                            if (secureProcessingEnabled) {
                                LOGGER.warn("Could not disable DOCTYPE declarations");
                            }
                        }
                        try {
                            tf.setFeature(
                                    "http://xml.org/sax/features/external-general-entities",
                                    false);
                        } catch (Exception e) {
                            if (secureProcessingEnabled) {
                                LOGGER.warn("Could not disable external general entities");
                            }
                        }
                        try {
                            tf.setFeature(
                                    "http://xml.org/sax/features/external-parameter-entities",
                                    false);
                        } catch (Exception e) {
                            if (secureProcessingEnabled) {
                                LOGGER.warn("Could not disable external parameter entities");
                            }
                        }
                        if (!secureProcessingEnabled) {
                            throw new IllegalStateException(
                                    "XML processor does not support secure processing");
                        }
                        return tf;
                    });

    private BodyBeautifier() {}

    /**
     * Renders body bytes into the given document, choosing the appropriate formatter based on the
     * Content-Type header.
     *
     * @param doc the target document
     * @param contentType the Content-Type header value; may be {@code null}
     * @param bodyBytes the raw body bytes
     * @param renderer the renderer supplying colour attributes and redaction state
     */
    public static void renderBody(
            StyledDocument doc,
            String contentType,
            byte[] bodyBytes,
            HttpMessageRenderer renderer) {
        if (bodyBytes == null || bodyBytes.length == 0) {
            return;
        }
        if (bodyBytes.length > MAX_BODY_BYTES) {
            renderTruncated(doc, bodyBytes.length, renderer);
            return;
        }

        String charset = extractCharset(contentType);
        String body = bytesToString(bodyBytes, charset);

        if (renderer.isRedactEnabled()) {
            body = redactBody(body, renderer);
        }

        if (body.length() > MAX_BODY_LENGTH) {
            body = body.substring(0, MAX_BODY_LENGTH);
        }

        if (contentType == null) {
            renderPlain(doc, body, renderer);
            return;
        }
        String lower = contentType.toLowerCase();

        if (lower.contains("application/json") || lower.contains("text/json")) {
            renderJson(doc, body, renderer);
        } else if (lower.contains("application/xml")
                || lower.contains("text/xml")
                || (lower.contains("application/") && lower.contains("+xml"))) {
            renderXml(doc, body, renderer);
        } else if (lower.contains("text/html")) {
            renderHtml(doc, body, renderer);
        } else {
            renderPlain(doc, body, renderer);
        }
    }

    /**
     * Pre-processes a body string, wrapping matched redaction spans in {@link #REDACT_START} and
     * {@link #REDACT_END} sentinels. All active rules are matched against the original input in a
     * single pass (ranges are collected, merged, then replaced) so that no rule can corrupt another
     * rule's sentinel markers.
     *
     * @param body the body string to process
     * @param renderer the renderer supplying the active redaction config
     * @return the body with redacted spans wrapped in sentinels
     */
    static String redactBody(String body, HttpMessageRenderer renderer) {
        RedactConfig config = renderer.getRedactConfig();
        if (config == null || !config.isEnabled()) {
            return body;
        }
        List<RedactEntry> activeEntries = config.getActiveEntries();
        if (activeEntries.isEmpty()) {
            return body;
        }

        String input = body.length() > MAX_REDACT_INPUT ? body.substring(0, MAX_REDACT_INPUT) : body;
        input = sanitizeSentinels(input);
        String wrappedReplacement = REDACT_START + config.getReplacementText() + REDACT_END;

        List<int[]> merged = collectMergedRanges(input, activeEntries);
        if (merged.isEmpty()) {
            return body;
        }

        StringBuilder result = buildRedactedString(input, merged, wrappedReplacement);
        if (body.length() > MAX_REDACT_INPUT) {
            result.append(body, MAX_REDACT_INPUT, body.length());
        }
        return result.toString();
    }

    /**
     * Applies redaction rules to {@code body}, replacing matched spans with the configured
     * replacement text (plain string, no sentinels). Suitable for text exports such as cURL
     * commands and Markdown where sentinel colouring is not needed.
     *
     * @param body the body string to process
     * @param config the active redaction configuration
     * @return the body with matched spans replaced by plain replacement text
     */
    static String redactBodyPlain(String body, RedactConfig config) {
        if (config == null || !config.isEnabled()) {
            return body;
        }
        List<RedactEntry> activeEntries = config.getActiveEntries();
        if (activeEntries.isEmpty()) {
            return body;
        }

        String input = body.length() > MAX_REDACT_INPUT ? body.substring(0, MAX_REDACT_INPUT) : body;
        input = sanitizeSentinels(input);
        String replacement = config.getReplacementText();

        List<int[]> merged = collectMergedRanges(input, activeEntries);
        if (merged.isEmpty()) {
            return body;
        }

        StringBuilder result = buildRedactedString(input, merged, replacement);
        if (body.length() > MAX_REDACT_INPUT) {
            result.append(body, MAX_REDACT_INPUT, body.length());
        }
        return result.toString();
    }

    private static final int MAX_REDACT_MATCHES = 500;

    /**
     * Collects all regex match ranges from all active entries against {@code input}, then merges
     * overlapping ranges. Matching is done on the original input so no rule can affect another.
     */
    private static List<int[]> collectMergedRanges(String input, List<RedactEntry> activeEntries) {
        List<int[]> matches = new ArrayList<>(MAX_REDACT_MATCHES);
        for (RedactEntry entry : activeEntries) {
            Pattern p = entry.getCompiledPattern();
            if (p == null) {
                continue;
            }
            try {
                Matcher m = p.matcher(input);
                while (m.find()) {
                    if (matches.size() >= MAX_REDACT_MATCHES) {
                        break;
                    }
                    matches.add(new int[] {m.start(), m.end()});
                }
            } catch (Exception ignored) {
            }
            if (matches.size() >= MAX_REDACT_MATCHES) {
                break;
            }
        }
        if (matches.isEmpty()) {
            return matches;
        }
        matches.sort(
                (a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(b[1], a[1]));
        List<int[]> merged = new ArrayList<>(matches.size());
        for (int[] match : matches) {
            if (merged.isEmpty()) {
                merged.add(match);
                continue;
            }
            int[] last = merged.get(merged.size() - 1);
            if (match[0] <= last[1]) {
                last[1] = Math.max(last[1], match[1]);
            } else {
                merged.add(match);
            }
        }
        return merged;
    }

    /** Builds the redacted output string from the original input and a sorted, merged range list. */
    private static StringBuilder buildRedactedString(
            String input, List<int[]> merged, String replacement) {
        StringBuilder result = new StringBuilder(Math.min(input.length(), MAX_REDACT_INPUT * 2));
        int pos = 0;
        int replacementLen = replacement.length();
        for (int[] range : merged) {
            int start = range[0];
            int end = range[1];
            if (start > pos) {
                result.append(input, pos, start);
            }
            result.append(replacement);
            pos = end;
        }
        if (pos < input.length()) {
            result.append(input, pos, input.length());
        }
        return result;
    }

    private static void renderTruncated(
            StyledDocument doc, int bodySize, HttpMessageRenderer renderer) {
        SimpleAttributeSet attrPunct = renderer.getAttrPunct();
        SimpleAttributeSet attrOffset = renderer.getAttrOffset();
        appendText(doc, "[Body too large to display: ", attrPunct);
        appendText(doc, String.format("%,d bytes", bodySize), attrOffset);
        appendText(doc, " (max ", attrPunct);
        appendText(doc, String.format("%,d", MAX_BODY_BYTES), attrOffset);
        appendText(doc, ")]", attrPunct);
    }

    /**
     * Renders a JSON body, falling back to plain text if the input exceeds the JSON size limit.
     *
     * @param doc      the target document
     * @param json     the JSON string to render
     * @param renderer the renderer supplying colour attributes
     */
    static void renderJson(StyledDocument doc, String json, HttpMessageRenderer renderer) {
        if (json.length() > MAX_JSON_INPUT) {
            renderPlain(doc, json, renderer);
            return;
        }
        String pretty = prettyPrintJson(json);
        highlightJson(doc, pretty, renderer);
    }

    /**
     * Pretty-prints a JSON string using a minimal hand-written formatter that preserves embedded
     * redaction sentinels.
     *
     * @param json the JSON string to format
     * @return the indented JSON string
     */
    static String prettyPrintJson(String json) {
        if (json == null || json.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        int indent = 0;
        boolean inString = false;
        boolean escape = false;

        int len = json.length();
        for (int i = 0; i < len; i++) {
            char c = json.charAt(i);

            if (escape) {
                out.append(c);
                if (c == 'u' && i + 4 < len) {
                    out.append(json, i + 1, i + 5);
                    i += 4;
                }
                escape = false;
                continue;
            }
            if (c == '\\' && inString) {
                out.append(c);
                escape = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                out.append(c);
                continue;
            }
            if (inString) {
                out.append(c);
                continue;
            }

            // Pass through embedded redaction sentinel sequences unchanged
            if (c == '\001') {
                int end = json.indexOf(REDACT_END, i);
                if (end >= 0) {
                    out.append(json, i, end + REDACT_END.length());
                    i = end + REDACT_END.length() - 1;
                    continue;
                }
            }

            switch (c) {
                case '[':
                case '{':
                    out.append(c);
                    indent += 2;
                    out.append('\n');
                    out.append(" ".repeat(indent));
                    break;
                case ']':
                case '}':
                    indent = Math.max(0, indent - 2);
                    out.append('\n');
                    out.append(" ".repeat(indent));
                    out.append(c);
                    break;
                case ',':
                    out.append(c);
                    out.append('\n');
                    out.append(" ".repeat(indent));
                    break;
                case ':':
                    out.append(c);
                    out.append(' ');
                    break;
                default:
                    if (!Character.isWhitespace(c)) {
                        out.append(c);
                    }
                    break;
            }
        }
        return out.toString();
    }

    /**
     * Applies One Dark syntax highlighting to a JSON string, emitting styled segments into the document.
     * Handles embedded redaction sentinels by rendering them in the redacted colour.
     *
     * @param doc      the target document
     * @param json     the (pretty-printed) JSON to highlight
     * @param renderer the renderer supplying colour attributes
     */
    static void highlightJson(StyledDocument doc, String json, HttpMessageRenderer renderer) {
        SimpleAttributeSet attrKey = renderer.getAttrKey();
        SimpleAttributeSet attrString = renderer.getAttrString();
        SimpleAttributeSet attrNumber = renderer.getAttrNumber();
        SimpleAttributeSet attrBoolNull = renderer.getAttrBoolNull();
        SimpleAttributeSet attrPunct = renderer.getAttrPunct();
        SimpleAttributeSet attrRedacted = renderer.getAttrRedacted();

        int i = 0;
        int len = json.length();
        int segmentStart = 0;
        SimpleAttributeSet currentAttr = null;

        while (i < len) {
            char c = json.charAt(i);

            if (c == '\001' && json.startsWith(REDACT_START, i)) {
                int end = json.indexOf(REDACT_END, i);
                if (end >= 0) {
                    if (i > segmentStart && currentAttr != null) {
                        appendText(doc, json.substring(segmentStart, i), currentAttr);
                    }
                    String replacement = json.substring(i + REDACT_START.length(), end);
                    appendText(doc, replacement, attrRedacted);
                    currentAttr = null;
                    i = end + REDACT_END.length();
                    segmentStart = i;
                    continue;
                }
            }

            SimpleAttributeSet attr;
            int nextI = i;

            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                attr = attrPunct;
                nextI = i + 1;
            } else if (c == '{' || c == '}' || c == '[' || c == ']' || c == ':' || c == ',') {
                attr = attrPunct;
                nextI = i + 1;
            } else if (c == '"') {
                int start = i;
                i++;
                while (i < len) {
                    char sc = json.charAt(i);
                    if (sc == '\\' && i + 1 < len) {
                        i += 2;
                        continue;
                    }
                    if (sc == '"') {
                        i++;
                        break;
                    }
                    i++;
                }
                boolean isKey = false;
                int peek = i;
                while (peek < len && json.charAt(peek) == ' ') {
                    peek++;
                }
                if (peek < len && json.charAt(peek) == ':') {
                    isKey = true;
                }
                SimpleAttributeSet stringAttr = isKey ? attrKey : attrString;
                if (currentAttr != null && currentAttr != stringAttr && segmentStart < start) {
                    appendText(doc, json.substring(segmentStart, start), currentAttr);
                }
                appendText(doc, json.substring(start, i), stringAttr);
                currentAttr = stringAttr;
                segmentStart = i;
                continue;
            } else if (c == '-' || (c >= '0' && c <= '9')) {
                int start = i;
                while (i < len) {
                    char nc = json.charAt(i);
                    if (nc == '-'
                            || nc == '+'
                            || nc == '.'
                            || nc == 'e'
                            || nc == 'E'
                            || (nc >= '0' && nc <= '9')) {
                        i++;
                    } else {
                        break;
                    }
                }
                if (currentAttr != null && currentAttr != attrNumber && segmentStart < start) {
                    appendText(doc, json.substring(segmentStart, start), currentAttr);
                }
                appendText(doc, json.substring(start, i), attrNumber);
                currentAttr = attrNumber;
                segmentStart = i;
                continue;
            } else if (json.startsWith("true", i)) {
                if (currentAttr != null && currentAttr != attrBoolNull && segmentStart < i) {
                    appendText(doc, json.substring(segmentStart, i), currentAttr);
                }
                appendText(doc, "true", attrBoolNull);
                currentAttr = attrBoolNull;
                i += 4;
                segmentStart = i;
                continue;
            } else if (json.startsWith("false", i)) {
                if (currentAttr != null && currentAttr != attrBoolNull && segmentStart < i) {
                    appendText(doc, json.substring(segmentStart, i), currentAttr);
                }
                appendText(doc, "false", attrBoolNull);
                currentAttr = attrBoolNull;
                i += 5;
                segmentStart = i;
                continue;
            } else if (json.startsWith("null", i)) {
                if (currentAttr != null && currentAttr != attrBoolNull && segmentStart < i) {
                    appendText(doc, json.substring(segmentStart, i), currentAttr);
                }
                appendText(doc, "null", attrBoolNull);
                currentAttr = attrBoolNull;
                i += 4;
                segmentStart = i;
                continue;
            } else {
                attr = attrPunct;
                nextI = i + 1;
            }

            if (currentAttr != attr) {
                if (segmentStart < i && currentAttr != null) {
                    appendText(doc, json.substring(segmentStart, i), currentAttr);
                }
                currentAttr = attr;
                segmentStart = i;
            }
            i = nextI;
        }

        if (segmentStart < len && currentAttr != null) {
            appendText(doc, json.substring(segmentStart, len), currentAttr);
        }
    }

    /**
     * Renders an XML body, falling back to plain text if the input exceeds the XML size limit.
     *
     * @param doc      the target document
     * @param xml      the XML string to render
     * @param renderer the renderer supplying colour attributes
     */
    static void renderXml(StyledDocument doc, String xml, HttpMessageRenderer renderer) {
        if (xml.length() > MAX_XML_INPUT) {
            renderPlain(doc, xml, renderer);
            return;
        }
        // Redaction sentinels break XSLT parsing — strip them, pretty-print, then re-apply.
        boolean hadSentinels = xml.indexOf('\001') >= 0;
        String cleanXml = hadSentinels ? sanitizeSentinels(xml) : xml;
        String pretty = prettyPrintXml(cleanXml);
        if (pretty.length() > MAX_XML_HIGHLIGHT_TOTAL) {
            pretty = pretty.substring(0, MAX_XML_HIGHLIGHT_TOTAL);
        }
        if (hadSentinels && renderer.isRedactEnabled()) {
            pretty = redactBody(pretty, renderer);
        }
        highlightXml(doc, pretty, renderer);
    }

    /**
     * Pretty-prints XML using the JDK XSLT identity transform with indentation enabled.
     *
     * @param xml the XML string to format
     * @return indented XML, or the original string if transformation fails
     */
    static String prettyPrintXml(String xml) {
        try {
            Transformer transformer = TRANSFORMER_FACTORY.get().newTransformer();
            transformer.setOutputProperty("indent", "yes");
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            transformer.setOutputProperty("omit-xml-declaration", "yes");
            StreamSource source = new StreamSource(new StringReader(xml));
            StringWriter writer = new StringWriter();
            StreamResult result = new StreamResult(writer);
            transformer.transform(source, result);
            return writer.toString().trim();
        } catch (IllegalStateException e) {
            LOGGER.warn("XML pretty-print disabled due to XXE vulnerability", e);
            return "[XML formatting disabled for security]";
        } catch (Exception e) {
            return xml;
        }
    }

    /**
     * Applies syntax highlighting to XML/HTML content, recognising tags, attributes, comments,
     * CDATA sections, and embedded redaction sentinels.
     *
     * @param doc      the target document
     * @param xml      the XML string to highlight
     * @param renderer the renderer supplying colour attributes
     */
    static void highlightXml(StyledDocument doc, String xml, HttpMessageRenderer renderer) {
        SimpleAttributeSet attrKey = renderer.getAttrKey();
        SimpleAttributeSet attrString = renderer.getAttrString();
        SimpleAttributeSet attrNumber = renderer.getAttrNumber();
        SimpleAttributeSet attrPunct = renderer.getAttrPunct();
        SimpleAttributeSet attrOffset = renderer.getAttrOffset();
        SimpleAttributeSet attrRedacted = renderer.getAttrRedacted();

        int[] totalInserted = {0};
        int i = 0;
        int len = xml.length();

        while (i < len) {
            if (totalInserted[0] >= MAX_XML_HIGHLIGHT_TOTAL) {
                appendText(doc, "\n...[display truncated]", attrPunct);
                return;
            }

            if (xml.charAt(i) == '\001' && xml.startsWith(REDACT_START, i)) {
                int end = xml.indexOf(REDACT_END, i);
                if (end >= 0) {
                    String replacement = xml.substring(i + REDACT_START.length(), end);
                    appendTextTracked(doc, replacement, attrRedacted, totalInserted);
                    i = end + REDACT_END.length();
                    continue;
                }
            }

            if (i + 3 < len && xml.charAt(i) == '<' && xml.startsWith("<!--", i)) {
                int end = xml.indexOf("-->", i + 4);
                end = (end == -1) ? len - 3 : end + 3;
                appendTextTracked(
                        doc,
                        xml.substring(i, Math.min(end, i + MAX_XML_HIGHLIGHT_TOTAL)),
                        attrOffset,
                        totalInserted);
                i = end;
                continue;
            }
            if (i + 8 < len && xml.startsWith("<![CDATA[", i)) {
                int end = xml.indexOf("]]>", i + 9);
                end = (end == -1) ? len - 3 : end + 3;
                appendTextTracked(
                        doc,
                        xml.substring(i, Math.min(end, i + MAX_XML_HIGHLIGHT_TOTAL)),
                        attrString,
                        totalInserted);
                i = end;
                continue;
            }
            if (xml.charAt(i) == '<') {
                i =
                        highlightXmlTag(
                                doc,
                                xml,
                                i,
                                len,
                                attrKey,
                                attrString,
                                attrNumber,
                                attrPunct,
                                attrRedacted,
                                totalInserted);
                continue;
            }
            int start = i;
            while (i < len && xml.charAt(i) != '<' && xml.charAt(i) != '\001') {
                i++;
            }
            appendTextTracked(doc, xml.substring(start, i), attrPunct, totalInserted);
        }
    }

    private static int highlightXmlTag(
            StyledDocument doc,
            String xml,
            int i,
            int len,
            SimpleAttributeSet attrKey,
            SimpleAttributeSet attrString,
            SimpleAttributeSet attrNumber,
            SimpleAttributeSet attrPunct,
            SimpleAttributeSet attrRedacted,
            int[] totalInserted) {
        appendTextTracked(doc, "<", attrPunct, totalInserted);
        i++;

        if (i < len && xml.charAt(i) == '/') {
            appendTextTracked(doc, "/", attrPunct, totalInserted);
            i++;
        }

        int start = i;
        int nameLimit = Math.min(i + MAX_TAG_NAME_LENGTH, len);
        while (i < nameLimit
                && !Character.isWhitespace(xml.charAt(i))
                && xml.charAt(i) != '>'
                && xml.charAt(i) != '/') {
            i++;
        }
        appendTextTracked(doc, xml.substring(start, i), attrKey, totalInserted);

        int attrCount = 0;
        while (i < len) {
            start = i;
            while (i < len && Character.isWhitespace(xml.charAt(i))) {
                i++;
            }
            if (i > start) {
                appendTextTracked(doc, xml.substring(start, i), attrPunct, totalInserted);
            }

            if (i >= len) {
                break;
            }
            if (xml.charAt(i) == '>') {
                appendTextTracked(doc, ">", attrPunct, totalInserted);
                i++;
                break;
            }
            if (i + 1 < len && xml.charAt(i) == '/' && xml.charAt(i + 1) == '>') {
                appendTextTracked(doc, "/>", attrPunct, totalInserted);
                i += 2;
                break;
            }

            attrCount++;
            if (attrCount > MAX_XML_ATTRS) {
                int end = xml.indexOf('>', i);
                end = (end == -1) ? len : end + 1;
                appendTextTracked(doc, xml.substring(i, end), attrPunct, totalInserted);
                i = end;
                break;
            }

            start = i;
            while (i < len
                    && xml.charAt(i) != '='
                    && xml.charAt(i) != '>'
                    && !Character.isWhitespace(xml.charAt(i))) {
                i++;
            }
            if (i > start) {
                appendTextTracked(doc, xml.substring(start, i), attrNumber, totalInserted);
            }

            start = i;
            while (i < len && Character.isWhitespace(xml.charAt(i))) {
                i++;
            }
            if (i > start) {
                appendTextTracked(doc, xml.substring(start, i), attrPunct, totalInserted);
            }

            if (i < len && xml.charAt(i) == '=') {
                appendTextTracked(doc, "=", attrPunct, totalInserted);
                i++;
            }

            start = i;
            while (i < len && Character.isWhitespace(xml.charAt(i))) {
                i++;
            }
            if (i > start) {
                appendTextTracked(doc, xml.substring(start, i), attrPunct, totalInserted);
            }

            if (i < len && (xml.charAt(i) == '"' || xml.charAt(i) == '\'')) {
                char quote = xml.charAt(i);
                appendTextTracked(doc, String.valueOf(quote), attrPunct, totalInserted);
                start = ++i;
                while (i < len && xml.charAt(i) != quote) {
                    i++;
                }
                String value = xml.substring(start, i);
                if (value.length() > MAX_ATTR_VALUE_LENGTH) {
                    value = value.substring(0, MAX_ATTR_VALUE_LENGTH) + "...";
                }
                appendRedactedContent(doc, value, attrString, attrRedacted, totalInserted);
                if (i < len) {
                    appendTextTracked(doc, String.valueOf(quote), attrPunct, totalInserted);
                    i++;
                }
            }
        }
        return i;
    }

    private static void appendRedactedContent(
            StyledDocument doc,
            String text,
            SimpleAttributeSet defaultAttr,
            SimpleAttributeSet attrRedacted,
            int[] totalInserted) {
        scanSentinels(
                text,
                defaultAttr,
                attrRedacted,
                (content, attr) -> appendTextTracked(doc, content, attr, totalInserted));
    }

    /**
     * Renders HTML content using the XML highlighter, falling back to plain text on parse errors.
     *
     * @param doc      the target document
     * @param html     the HTML string to render
     * @param renderer the renderer supplying colour attributes
     */
    static void renderHtml(StyledDocument doc, String html, HttpMessageRenderer renderer) {
        if (html.length() > MAX_HTML_INPUT) {
            html = html.substring(0, MAX_HTML_INPUT);
        }
        try {
            highlightXml(doc, html, renderer);
        } catch (Exception e) {
            // HTML is not guaranteed to be valid XML; fall back to plain text on error
            renderer.clearDocument(doc);
            renderPlain(doc, html, renderer);
        }
    }

    /**
     * Renders plain text with sentinel-aware redaction, truncating if the body exceeds
     * {@link #MAX_PLAIN_LENGTH} characters.
     *
     * @param doc      the target document
     * @param text     the plain text to render
     * @param renderer the renderer supplying colour attributes
     */
    static void renderPlain(StyledDocument doc, String text, HttpMessageRenderer renderer) {
        SimpleAttributeSet attrPunct = renderer.getAttrPunct();
        SimpleAttributeSet attrRedacted = renderer.getAttrRedacted();
        if (text.length() > MAX_PLAIN_LENGTH) {
            String truncated = text.substring(0, MAX_PLAIN_LENGTH);
            appendWithRedactSentinels(doc, truncated, attrPunct, attrRedacted);
            appendText(
                    doc,
                    String.format(TRUNCATION_NOTE, MAX_PLAIN_LENGTH),
                    renderer.getAttrOffset());
        } else {
            appendWithRedactSentinels(doc, text, attrPunct, attrRedacted);
        }
    }

    private static void appendWithRedactSentinels(
            StyledDocument doc,
            String text,
            SimpleAttributeSet defaultAttr,
            SimpleAttributeSet attrRedacted) {
        scanSentinels(text, defaultAttr, attrRedacted, (content, attr) -> appendText(doc, content, attr));
    }

    /**
     * Scans {@code text} for embedded redaction sentinel sequences and invokes {@code appender} for
     * each plain or redacted segment in order.
     */
    private static void scanSentinels(
            String text,
            SimpleAttributeSet defaultAttr,
            SimpleAttributeSet attrRedacted,
            SegmentAppender appender) {
        int pos = 0;
        while (pos < text.length()) {
            int startIdx = text.indexOf(REDACT_START, pos);
            if (startIdx < 0) {
                appender.append(text.substring(pos), defaultAttr);
                break;
            }
            if (startIdx > pos) {
                appender.append(text.substring(pos, startIdx), defaultAttr);
            }
            int endIdx = text.indexOf(REDACT_END, startIdx);
            if (endIdx < 0) {
                appender.append(text.substring(startIdx), defaultAttr);
                break;
            }
            String replacement = text.substring(startIdx + REDACT_START.length(), endIdx);
            appender.append(replacement, attrRedacted);
            pos = endIdx + REDACT_END.length();
        }
    }

    /**
     * Converts raw bytes to a string using the given charset, falling back to UTF-8 on error.
     *
     * @param bytes the raw bytes
     * @param charset the charset name
     * @return the decoded string
     */
    static String bytesToString(byte[] bytes, String charset) {
        try {
            return new String(bytes, charset);
        } catch (Exception e) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    /**
     * Extracts the charset name from a Content-Type header value.
     *
     * @param contentType the Content-Type value; may be {@code null}
     * @return the charset name, defaulting to {@code "UTF-8"}
     */
    static String extractCharset(String contentType) {
        if (contentType == null) {
            return "UTF-8";
        }
        Matcher m = CONTENT_TYPE_CHARSET.matcher(contentType);
        if (m.find()) {
            return m.group(1);
        }
        return "UTF-8";
    }

    /**
     * Strips any pre-existing sentinel markers ({@code \001}) from the input to prevent
     * false-positive matches during redaction processing.
     *
     * @param input the text to sanitize
     * @return the text with sentinel control characters removed
     */
    private static String sanitizeSentinels(String input) {
        return input.indexOf('\001') >= 0 ? input.replace("\001", "") : input;
    }

    private static void appendText(StyledDocument doc, String text, SimpleAttributeSet attr) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (text.length() > MAX_INSERT_LENGTH) {
            text = text.substring(0, MAX_INSERT_LENGTH) + "\n...[truncated]";
        }
        try {
            doc.insertString(doc.getLength(), text, attr);
        } catch (Exception ignored) {
        }
    }

    private static void appendTextTracked(
            StyledDocument doc, String text, SimpleAttributeSet attr, int[] total) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (total[0] + text.length() > MAX_XML_HIGHLIGHT_TOTAL) {
            text = text.substring(0, Math.max(0, MAX_XML_HIGHLIGHT_TOTAL - total[0]));
        }
        if (text.isEmpty()) {
            return;
        }
        total[0] += text.length();
        try {
            doc.insertString(doc.getLength(), text, attr);
        } catch (Exception ignored) {
        }
    }

    private static void flushSegment(
            StyledDocument doc,
            StringBuilder segment,
            SimpleAttributeSet oldAttr,
            SimpleAttributeSet newAttr) {
        if (oldAttr != null && oldAttr != newAttr && segment.length() > 0) {
            appendText(doc, segment.toString(), oldAttr);
            segment.setLength(0);
        }
    }

    /** Functional interface for appending a styled text segment to a document. */
    private interface SegmentAppender {
        void append(String text, SimpleAttributeSet attr);
    }
}
