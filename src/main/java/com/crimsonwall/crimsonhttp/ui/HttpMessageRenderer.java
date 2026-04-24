/*
 * Crimson HTTP - HTTP Request/Response Viewer for OWASP ZAP.
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
import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import org.parosproxy.paros.network.HttpBody;
import org.parosproxy.paros.network.HttpHeader;
import org.parosproxy.paros.network.HttpHeaderField;
import org.parosproxy.paros.network.HttpRequestHeader;
import org.parosproxy.paros.network.HttpResponseHeader;

/**
 * Renders HTTP request and response messages into a {@link StyledDocument} with syntax
 * highlighting using a One Dark colour scheme.
 *
 * <p>When redaction is enabled, header values and URI text that match active {@link RedactEntry}
 * patterns are replaced inline with the configured replacement string, shown in the redacted colour.
 * Body text is pre-processed by {@link BodyBeautifier}, which embeds sentinel markers for
 * redacted spans.
 *
 * <p>Subclasses may override {@link #initAttributes()} to supply an alternative colour palette
 * (e.g. a light-mode variant for screenshots).
 */
public class HttpMessageRenderer {

    // One Dark palette
    private static final Color COLOR_KEY = new Color(224, 108, 117);
    private static final Color COLOR_STRING = new Color(152, 195, 127);
    private static final Color COLOR_NUMBER = new Color(209, 154, 102);
    private static final Color COLOR_BOOL_NULL = new Color(198, 120, 221);
    private static final Color COLOR_PUNCT = new Color(171, 178, 191);
    private static final Color COLOR_OFFSET = new Color(92, 99, 112);
    private static final Color COLOR_REDACTED = new Color(86, 156, 214);

    private static final int MAX_HEADERS = 200;
    private static final int MAX_HEADER_VALUE_LENGTH = 10000;
    private static final int MAX_URI_LENGTH = 10000;
    private static final int MAX_INSERT_LENGTH = 100000;
    private static final int MAX_REDACT_INPUT = 100000;

    /** Orange accent — HTTP method line, JSON numbers, XML attribute names. */
    protected final SimpleAttributeSet attrAccent = new SimpleAttributeSet();
    /** Red keyword — HTTP header names, JSON keys, XML tag names. */
    protected final SimpleAttributeSet attrKeyword = new SimpleAttributeSet();
    /** Green literal — HTTP header values, JSON strings, XML attribute values. */
    protected final SimpleAttributeSet attrLiteral = new SimpleAttributeSet();
    protected final SimpleAttributeSet attrPunct = new SimpleAttributeSet();
    protected final SimpleAttributeSet attrStatus2xx = new SimpleAttributeSet();
    protected final SimpleAttributeSet attrStatus3xx = new SimpleAttributeSet();
    protected final SimpleAttributeSet attrStatus4xx = new SimpleAttributeSet();
    protected final SimpleAttributeSet attrRedacted = new SimpleAttributeSet();
    protected final SimpleAttributeSet attrBoolNull = new SimpleAttributeSet();
    protected final SimpleAttributeSet attrOffset = new SimpleAttributeSet();

    private RedactConfig redactConfig;

    /**
     * Constructs a renderer with default One Dark colour attributes.
     */
    public HttpMessageRenderer() {
        initAttributes();
    }

    /**
     * @param config the redaction configuration to use; {@code null} disables redaction
     */
    public void setRedactConfig(RedactConfig config) {
        this.redactConfig = config;
    }

    /** @return the current redaction configuration, or {@code null} */
    public RedactConfig getRedactConfig() {
        return redactConfig;
    }

    /** Initialises all styled-document attribute sets with their default colours. */
    protected void initAttributes() {
        initAttr(attrAccent, COLOR_NUMBER);
        initAttr(attrKeyword, COLOR_KEY);
        initAttr(attrLiteral, COLOR_STRING);
        initAttr(attrPunct, COLOR_PUNCT);
        initAttr(attrStatus2xx, COLOR_STRING);
        initAttr(attrStatus3xx, COLOR_NUMBER);
        initAttr(attrStatus4xx, COLOR_KEY);
        initAttr(attrBoolNull, COLOR_BOOL_NULL);
        initAttr(attrOffset, COLOR_OFFSET);
        initAttr(attrRedacted, COLOR_REDACTED);
    }

    /**
     * Configures a single attribute set with monospaced font and the given foreground colour.
     *
     * @param attr the attribute set to configure
     * @param fg the foreground colour
     */
    protected void initAttr(SimpleAttributeSet attr, Color fg) {
        StyleConstants.setFontFamily(attr, "Monospaced");
        StyleConstants.setFontSize(attr, 12);
        StyleConstants.setForeground(attr, fg);
    }

    /**
     * Renders an HTTP request into the given document.
     *
     * @param doc the target styled document (will be cleared first)
     * @param header the request header; if {@code null} the document is left empty
     * @param body the request body; may be {@code null} or empty
     */
    public void renderRequest(StyledDocument doc, HttpRequestHeader header, HttpBody body) {
        clearDocument(doc);
        if (header == null) {
            return;
        }
        try {
            String method = header.getMethod();
            String uri;
            try {
                uri = header.getURI().toString();
            } catch (Exception e) {
                uri = "/";
            }
            String version = header.getVersion();

            appendText(doc, (method != null) ? method : "GET", attrAccent);
            appendText(doc, " ", attrPunct);

            if (uri != null) {
                appendRedactedText(
                        doc,
                        (uri.length() > MAX_URI_LENGTH) ? (uri.substring(0, MAX_URI_LENGTH) + "...") : uri,
                        attrPunct);
            } else {
                appendText(doc, "/", attrPunct);
            }

            appendText(doc, " ", attrPunct);
            appendText(doc, (version != null) ? version : "HTTP/1.1", attrPunct);
            appendText(doc, "\n", attrPunct);

            renderHeaders(doc, (HttpHeader) header);
            appendText(doc, "\n", attrPunct);

            String contentType = header.getHeader("Content-Type");
            if (body != null && body.length() > 0) {
                BodyBeautifier.renderBody(doc, contentType, body.getBytes(), this);
            }
        } catch (Exception e) {
            appendText(doc, "[Error rendering request: " + e.getMessage() + "]", attrPunct);
        }
    }

    /**
     * Renders an HTTP response into the given document.
     *
     * @param doc the target styled document (will be cleared first)
     * @param header the response header; if {@code null} the document is left empty
     * @param body the response body; may be {@code null} or empty
     */
    public void renderResponse(StyledDocument doc, HttpResponseHeader header, HttpBody body) {
        clearDocument(doc);
        if (header == null) {
            return;
        }
        try {
            String version = header.getVersion();
            int statusCode = header.getStatusCode();
            String reason = header.getReasonPhrase();

            SimpleAttributeSet statusAttr = getStatusAttr(statusCode);

            appendText(doc, (version != null) ? version : "HTTP/1.1", attrPunct);
            appendText(doc, " ", attrPunct);
            appendText(doc, String.valueOf(statusCode), statusAttr);
            appendText(doc, " ", attrPunct);
            if (reason != null && reason.length() > MAX_URI_LENGTH) {
                appendText(doc, reason.substring(0, MAX_URI_LENGTH) + "...", attrPunct);
            } else {
                appendText(doc, (reason != null) ? reason : "", attrPunct);
            }
            appendText(doc, "\n", attrPunct);

            renderHeaders(doc, (HttpHeader) header);
            appendText(doc, "\n", attrPunct);

            String contentType = header.getHeader("Content-Type");
            if (body != null && body.length() > 0) {
                BodyBeautifier.renderBody(doc, contentType, body.getBytes(), this);
            }
        } catch (Exception e) {
            appendText(doc, "[Error rendering response: " + e.getMessage() + "]", attrPunct);
        }
    }

    private void renderHeaders(StyledDocument doc, HttpHeader header) {
        List<HttpHeaderField> headers = header.getHeaders();
        if (headers == null) {
            return;
        }
        int count = 0;
        for (HttpHeaderField field : headers) {
            count++;
            if (count > MAX_HEADERS) {
                appendText(
                        doc,
                        "... [" + (headers.size() - MAX_HEADERS) + " more headers omitted]\n",
                        attrPunct);
                break;
            }
            String value = field.getValue();
            if (value != null && value.length() > MAX_HEADER_VALUE_LENGTH) {
                value = value.substring(0, MAX_HEADER_VALUE_LENGTH) + "...";
            }
            appendRedactedHeaderLine(doc, field.getName(), value, attrKeyword, attrLiteral);
            appendText(doc, "\n", attrPunct);
        }
    }

    /**
     * Appends a single header line, redacting the value if the full {@code name: value} line
     * matches any active rule.
     */
    private void appendRedactedHeaderLine(
            StyledDocument doc,
            String name,
            String value,
            SimpleAttributeSet nameAttr,
            SimpleAttributeSet valueAttr) {
        appendText(doc, name, nameAttr);
        appendText(doc, ": ", attrPunct);
        if (!isRedactEnabled() || value == null || value.isEmpty()) {
            appendText(doc, (value != null) ? value : "", valueAttr);
            return;
        }
        String fullLine = name + ": " + value;
        if (shouldRedact(fullLine)) {
            appendText(doc, redactConfig.getReplacementText(), attrRedacted);
        } else {
            appendText(doc, value, valueAttr);
        }
    }

    private boolean shouldRedact(String text) {
        if (redactConfig == null || !redactConfig.isEnabled()) {
            return false;
        }
        List<RedactEntry> activeEntries = redactConfig.getActiveEntries();
        for (RedactEntry entry : activeEntries) {
            if (entry.matchesWithTimeout(text)) {
                return true;
            }
        }
        return false;
    }

    private static final int MAX_TOTAL_MATCHES = 1000;

    /**
     * Appends text to the document, replacing spans that match active redaction rules with the
     * replacement text in the redacted colour. Non-matching portions use {@code defaultAttr}.
     *
     * @param doc the target document
     * @param text the text to append
     * @param defaultAttr attribute set to use for non-redacted spans
     */
    public void appendRedactedText(
            StyledDocument doc, String text, SimpleAttributeSet defaultAttr) {
        if (!isRedactEnabled() || text == null || text.isEmpty()) {
            appendText(doc, text, defaultAttr);
            return;
        }
        List<RedactEntry> activeEntries = redactConfig.getActiveEntries();
        if (activeEntries.isEmpty()) {
            appendText(doc, text, defaultAttr);
            return;
        }

        String input = text;
        if (input.length() > MAX_REDACT_INPUT) {
            input = input.substring(0, MAX_REDACT_INPUT);
        }

        List<int[]> matches = new ArrayList<>(MAX_TOTAL_MATCHES);
        for (RedactEntry entry : activeEntries) {
            Pattern p = entry.getCompiledPattern();
            if (p == null) {
                continue;
            }
            try {
                Matcher m = p.matcher(input);
                while (m.find()) {
                    if (matches.size() >= MAX_TOTAL_MATCHES) {
                        break;
                    }
                    matches.add(new int[] {m.start(), m.end()});
                }
            } catch (Exception ignored) {
            }
            if (matches.size() >= MAX_TOTAL_MATCHES) {
                break;
            }
        }

        if (matches.isEmpty()) {
            appendText(doc, text, defaultAttr);
            return;
        }

        matches.sort(
                (a, b) -> (a[0] != b[0]) ? Integer.compare(a[0], b[0]) : Integer.compare(b[1], a[1]));

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

        int pos = 0;
        for (int[] range : merged) {
            if (range[0] > pos) {
                appendText(doc, text.substring(pos, range[0]), defaultAttr);
            }
            appendText(doc, redactConfig.getReplacementText(), attrRedacted);
            pos = range[1];
        }
        if (pos < text.length()) {
            appendText(doc, text.substring(pos), defaultAttr);
        }
    }

    /**
     * Returns {@code true} if redaction is enabled and a config is set.
     *
     * @return whether redaction is active
     */
    public boolean isRedactEnabled() {
        return (redactConfig != null && redactConfig.isEnabled());
    }

    private SimpleAttributeSet getStatusAttr(int statusCode) {
        if (statusCode >= 200 && statusCode < 300) {
            return attrStatus2xx;
        }
        if (statusCode >= 300 && statusCode < 400) {
            return attrStatus3xx;
        }
        return attrStatus4xx;
    }

    /** Removes all content from the given document. */
    public void clearDocument(StyledDocument doc) {
        try {
            doc.remove(0, doc.getLength());
        } catch (Exception ignored) {
        }
    }

    /**
     * Appends text to the document with the given attribute set. Truncates to {@link
     * #MAX_INSERT_LENGTH} characters if necessary.
     *
     * @param doc the target document
     * @param text the text to append; {@code null} or empty strings are silently ignored
     * @param attr the text style to apply
     */
    public void appendText(StyledDocument doc, String text, SimpleAttributeSet attr) {
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

    // Attribute accessors used by BodyBeautifier

    /** @return the punctuation colour attribute */
    public SimpleAttributeSet getAttrPunct() {
        return attrPunct;
    }

    /** Returns the keyword colour (JSON keys, XML tag names). */
    public SimpleAttributeSet getAttrKey() {
        return attrKeyword;
    }

    /** Returns the literal colour (JSON strings, XML attribute values). */
    public SimpleAttributeSet getAttrString() {
        return attrLiteral;
    }

    /** Returns the accent colour (JSON numbers, XML attribute names). */
    public SimpleAttributeSet getAttrNumber() {
        return attrAccent;
    }

    /** @return the boolean/null keyword colour attribute */
    public SimpleAttributeSet getAttrBoolNull() {
        return attrBoolNull;
    }

    /** @return the offset/comment colour attribute */
    public SimpleAttributeSet getAttrOffset() {
        return attrOffset;
    }

    /** @return the redacted-text colour attribute */
    public SimpleAttributeSet getAttrRedacted() {
        return attrRedacted;
    }
}
