/*
 * Crimson HTTP - HTTP Request/Response Viewer for ZAP.
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

import com.crimsonwall.crimsonhttp.ExtensionCrimsonHttp;
import com.crimsonwall.crimsonhttp.redact.RedactConfig;
import com.crimsonwall.crimsonhttp.redact.RedactEntry;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;
import javax.swing.text.AbstractDocument;
import javax.swing.text.LabelView;
import javax.swing.text.StyledDocument;
import javax.swing.text.StyledEditorKit;
import javax.swing.text.View;
import javax.swing.text.ViewFactory;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.parosproxy.paros.Constant;
import org.parosproxy.paros.control.Control;
import org.parosproxy.paros.extension.AbstractPanel;
import org.parosproxy.paros.extension.ExtensionLoader;
import org.parosproxy.paros.network.HttpBody;
import org.parosproxy.paros.network.HttpHeaderField;
import org.parosproxy.paros.network.HttpMessage;
import org.parosproxy.paros.network.HttpRequestHeader;
import org.parosproxy.paros.network.HttpResponseHeader;
import org.zaproxy.zap.network.HttpRequestBody;
import org.zaproxy.zap.network.HttpResponseBody;
import org.zaproxy.zap.utils.ZapXmlConfiguration;

/**
 * Main display panel for Crimson HTTP.
 *
 * <p>Shows request and response panes side-by-side (horizontal) or stacked (vertical) in a
 * {@link JSplitPane}. The layout orientation and divider position are persisted across sessions via
 * {@link LayoutPrefs}.
 *
 * <p>Each text pane has a context menu offering copy, Markdown export, cURL generation,
 * Request Editor integration, and screenshot capture (both to clipboard and to file).
 */
public final class CrimsonHttpPanel extends AbstractPanel {

    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = LogManager.getLogger(CrimsonHttpPanel.class);

    private static final Color COLOR_BG = new Color(40, 44, 52);
    private static final int MAX_STATUS_URI_LENGTH = 500;
    private static final int MAX_SCREENSHOT_HEIGHT = 16384;
    private static final int NO_WRAP_WIDTH = 100_000;
    private static final int MAX_SINGLE_PANE_HEIGHT = 16352;
    private static final int MAX_VERTICAL_PANE_HEIGHT = 8192;

    // Cached screenshot colours to avoid per-call allocation
    private static final Color COLOR_LABEL_DARK = new Color(220, 20, 60);
    private static final Color COLOR_LABEL_LIGHT = new Color(180, 20, 40);
    private static final Color COLOR_DIVIDER_DARK = new Color(60, 64, 72);
    private static final Color COLOR_DIVIDER_LIGHT = new Color(200, 200, 200);

    /** Single-threaded executor with bounded queue for off-thread screenshot rendering. */
    private static final ExecutorService screenshotExecutor =
            new ThreadPoolExecutor(
                    1, 1, 0L, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(4),
                    r -> {
                        Thread t = new Thread(r, "crimsonhttp-screenshot");
                        t.setDaemon(true);
                        return t;
                    },
                    new ThreadPoolExecutor.DiscardOldestPolicy());

    private static ImageIcon iconVertical;
    private static ImageIcon iconHorizontal;

    private final transient ExtensionCrimsonHttp extension;
    private final transient HttpMessageRenderer renderer;

    private JTextPane requestPane;
    private JTextPane responsePane;
    private JLabel statusLabel;
    private JSplitPane splitPane;
    private JPanel requestPanel;
    private JPanel responsePanel;
    private JButton toggleButton;
    private volatile transient HttpMessage currentMessage;
    private volatile boolean horizontal;
    private Timer dividerSaveTimer;

    /**
     * Constructs the main display panel.
     *
     * @param extension the parent extension used for config access and message refresh
     */
    public CrimsonHttpPanel(ExtensionCrimsonHttp extension) {
        this.extension = extension;
        this.renderer = new HttpMessageRenderer();
        this.renderer.initAttributes();
        this.renderer.setRedactConfig(extension.getRedactConfig());
        this.horizontal = LayoutPrefs.loadHorizontal();
        initialize();
        setIcon(ExtensionCrimsonHttp.getIcon());
    }

    private void initialize() {
        setLayout(new BorderLayout());
        setName(Constant.messages.getString("crimsonhttp.panel.title"));

        add(createToolbar(), BorderLayout.NORTH);

        requestPanel = createHalfPanel("crimsonhttp.panel.request.label", true);
        responsePanel = createHalfPanel("crimsonhttp.panel.response.label", false);

        splitPane = buildSplitPane();
        add(splitPane, BorderLayout.CENTER);
        add(createStatusBar(), BorderLayout.SOUTH);

        updateToggleIcon();

        // Restore saved divider position once the component has been laid out
        splitPane.addAncestorListener(
                new AncestorListener() {
                    @Override
                    public void ancestorAdded(AncestorEvent event) {
                        applyDividerRatio();
                        splitPane.removeAncestorListener(this);
                    }

                    @Override
                    public void ancestorRemoved(AncestorEvent event) {}

                    @Override
                    public void ancestorMoved(AncestorEvent event) {}
                });
    }

    /**
     * Creates the request or response half-panel with its label.
     *
     * @param titleKey i18n message key for the section header label
     * @param isRequest {@code true} for the request pane, {@code false} for the response pane
     * @return the panel containing label and scroll pane
     */
    private JPanel createHalfPanel(String titleKey, boolean isRequest) {
        JPanel panel = new JPanel(new BorderLayout());
        JLabel label = new JLabel(Constant.messages.getString(titleKey));
        label.setFont(label.getFont().deriveFont(Font.BOLD, 12.0f));
        label.setForeground(new Color(220, 20, 60));
        label.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        panel.add(label, BorderLayout.NORTH);

        JTextPane pane = createTextPane(isRequest);
        if (isRequest) {
            requestPane = pane;
        } else {
            responsePane = pane;
        }
        panel.add(new JScrollPane(pane), BorderLayout.CENTER);
        return panel;
    }

    private JTextPane createTextPane(boolean isRequestPane) {
        JTextPane pane = new JTextPane();
        pane.setEditable(false);
        pane.setBackground(COLOR_BG);
        pane.setCaretColor(Color.WHITE);
        addContextMenu(pane, isRequestPane);
        return pane;
    }

    /**
     * Builds a new {@link JSplitPane} oriented according to the current {@link #horizontal} flag
     * and wires up the divider-save timer.
     */
    private JSplitPane buildSplitPane() {
        JSplitPane pane = new JSplitPane(horizontal ? JSplitPane.HORIZONTAL_SPLIT : JSplitPane.VERTICAL_SPLIT);
        pane.setResizeWeight(0.5);
        pane.setTopComponent(requestPanel);
        pane.setBottomComponent(responsePanel);

        dividerSaveTimer =
                new Timer(
                        500,
                        e -> {
                            int divisor =
                                    horizontal ? splitPane.getWidth() : splitPane.getHeight();
                            if (divisor > 0) {
                                double ratio = (double) splitPane.getDividerLocation() / divisor;
                                LayoutPrefs.saveDividerRatio(ratio);
                            }
                        });
        dividerSaveTimer.setRepeats(false);
        pane.addPropertyChangeListener(
                JSplitPane.DIVIDER_LOCATION_PROPERTY, evt -> dividerSaveTimer.restart());
        return pane;
    }

    private void applyDividerRatio() {
        double saved = LayoutPrefs.loadDividerRatio();
        if (saved > 0.0 && saved < 1.0) {
            int size = horizontal ? splitPane.getWidth() : splitPane.getHeight();
            if (size > 0) {
                splitPane.setDividerLocation((int) (size * saved));
            }
        } else {
            splitPane.setDividerLocation(0.5);
        }
    }

    private JPanel createToolbar() {
        JPanel bar = new JPanel(new BorderLayout());

        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));

        toggleButton = new JButton();
        toggleButton.setToolTipText(
                Constant.messages.getString("crimsonhttp.button.toggle.tooltip"));
        toggleButton.addActionListener(e -> toggleLayout());
        buttons.add(toggleButton);
        buttons.add(Box.createHorizontalStrut(4));

        JButton screenshotButton =
                new JButton(Constant.messages.getString("crimsonhttp.button.screenshot"));
        screenshotButton.setToolTipText(
                Constant.messages.getString("crimsonhttp.button.screenshot.tooltip"));
        screenshotButton.addActionListener(e -> takeScreenshot());
        buttons.add(screenshotButton);

        bar.add(buttons, BorderLayout.EAST);
        return bar;
    }

    private JPanel createStatusBar() {
        JPanel bar = new JPanel(new BorderLayout());
        statusLabel = new JLabel(Constant.messages.getString("crimsonhttp.status.ready"));
        statusLabel.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        bar.add(statusLabel, BorderLayout.WEST);
        return bar;
    }

    private void toggleLayout() {
        horizontal = !horizontal;
        LayoutPrefs.saveHorizontal(horizontal);

        remove(splitPane);
        splitPane = buildSplitPane();
        add(splitPane, BorderLayout.CENTER);
        updateToggleIcon();
        revalidate();
        repaint();

        SwingUtilities.invokeLater(this::applyDividerRatio);
    }

    private void updateToggleIcon() {
        toggleButton.setIcon(horizontal ? getHorizontalIcon() : getVerticalIcon());
    }

    private static synchronized ImageIcon getVerticalIcon() {
        if (iconVertical == null) {
            URL url = ExtensionCrimsonHttp.class.getResource("crimsonhttp-toggle-v.png");
            if (url != null) {
                iconVertical = new ImageIcon(url);
            }
        }
        return iconVertical;
    }

    private static synchronized ImageIcon getHorizontalIcon() {
        if (iconHorizontal == null) {
            URL url = ExtensionCrimsonHttp.class.getResource("crimsonhttp-toggle-h.png");
            if (url != null) {
                iconHorizontal = new ImageIcon(url);
            }
        }
        return iconHorizontal;
    }

    /**
     * Renders the given HTTP message into the request and response panes and updates the status bar
     * with the request URI.
     *
     * @param msg the message to display; {@code null} is silently ignored
     */
    public void displayMessage(HttpMessage msg) {
        if (msg == null) {
            return;
        }
        currentMessage = msg;
        try {
            StyledDocument reqDoc = requestPane.getStyledDocument();
            StyledDocument respDoc = responsePane.getStyledDocument();
            renderer.renderRequest(reqDoc, msg.getRequestHeader(), (HttpBody) msg.getRequestBody());
            renderer.renderResponse(
                    respDoc, msg.getResponseHeader(), (HttpBody) msg.getResponseBody());

            try {
                String uri = msg.getRequestHeader().getURI().toString();
                if (uri.length() > MAX_STATUS_URI_LENGTH) {
                    uri = uri.substring(0, MAX_STATUS_URI_LENGTH) + "...";
                }
                statusLabel.setText(uri);
            } catch (Exception e) {
                statusLabel.setText("");
            }

            requestPane.setCaretPosition(0);
            responsePane.setCaretPosition(0);
        } catch (Exception e) {
            statusLabel.setText("Error rendering message: " + e.getMessage());
        }
    }

    /** Clears both display panes and resets the status label. */
    public void clearDisplay() {
        currentMessage = null;
        renderer.clearDocument(requestPane.getStyledDocument());
        renderer.clearDocument(responsePane.getStyledDocument());
        statusLabel.setText(Constant.messages.getString("crimsonhttp.status.cleared"));
    }

    /** Stops background timers and clears the display. Called during extension unload. */
    public void cleanup() {
        clearDisplay();
        if (dividerSaveTimer != null) {
            dividerSaveTimer.stop();
        }
        screenshotExecutor.shutdownNow();
    }

    /**
     * Re-renders the current message after a configuration change (e.g. redaction settings
     * updated).
     */
    public void refresh() {
        HttpMessage msg = currentMessage;
        if (msg != null) {
            renderer.setRedactConfig(extension.getRedactConfig());
            displayMessage(msg);
        }
    }

    // -------------------------------------------------------------------------
    // Context menu
    // -------------------------------------------------------------------------

    /**
     * Installs a right-click context menu on the given text pane with copy, Markdown export,
     * cURL generation, request-editor integration, and screenshot capture actions.
     *
     * @param textPane       the text pane to attach the menu to
     * @param isRequestPane  {@code true} for the request pane (adds cURL and resend items)
     */
    private void addContextMenu(JTextPane textPane, boolean isRequestPane) {
        final JPopupMenu popup = new JPopupMenu();

        // Copy selected text or all content
        JMenuItem copyItem =
                new JMenuItem(Constant.messages.getString("crimsonhttp.button.copy"));
        copyItem.addActionListener(
                e -> {
                    String selected = textPane.getSelectedText();
                    if (selected != null && !selected.isEmpty()) {
                        Toolkit.getDefaultToolkit()
                                .getSystemClipboard()
                                .setContents(new StringSelection(selected), null);
                    } else {
                        try {
                            String allText = textPane.getText();
                            if (allText != null && !allText.isEmpty()) {
                                Toolkit.getDefaultToolkit()
                                        .getSystemClipboard()
                                        .setContents(new StringSelection(allText), null);
                                statusLabel.setText(
                                        Constant.messages.getString(
                                                "crimsonhttp.status.copiedall"));
                            }
                        } catch (Exception ex) {
                            LOGGER.info("Failed to copy text to clipboard", ex);
                        }
                    }
                });
        popup.add(copyItem);

        popup.addSeparator();

        JMenuItem markdownItem =
                new JMenuItem(
                        Constant.messages.getString("crimsonhttp.button.copymarkdown"));
        markdownItem.setToolTipText(
                Constant.messages.getString("crimsonhttp.button.copymarkdown.tooltip"));
        markdownItem.addActionListener(e -> copyAsMarkdown(isRequestPane));
        popup.add(markdownItem);

        JMenuItem curlItem = null;
        JMenuItem resendItem = null;
        if (isRequestPane) {
            popup.addSeparator();
            curlItem =
                    new JMenuItem(Constant.messages.getString("crimsonhttp.button.copycurl"));
            curlItem.setToolTipText(
                    Constant.messages.getString("crimsonhttp.button.copycurl.tooltip"));
            curlItem.addActionListener(
                    e -> {
                        String curl = buildCurlCommand();
                        if (curl != null) {
                            Toolkit.getDefaultToolkit()
                                    .getSystemClipboard()
                                    .setContents(new StringSelection(curl), null);
                            statusLabel.setText(
                                    Constant.messages.getString(
                                            "crimsonhttp.status.curlcopied"));
                        }
                    });
            popup.add(curlItem);

            resendItem =
                    new JMenuItem(Constant.messages.getString("crimsonhttp.button.resend"));
            resendItem.setToolTipText(
                    Constant.messages.getString("crimsonhttp.button.resend.tooltip"));
            resendItem.addActionListener(e -> openInRequestEditor());
            popup.add(resendItem);
        }

        popup.addSeparator();

        String screenshotSingleKey =
                isRequestPane
                        ? "crimsonhttp.button.copyscreenshot.request"
                        : "crimsonhttp.button.copyscreenshot.response";
        JMenuItem screenshotSingleItem =
                new JMenuItem(Constant.messages.getString(screenshotSingleKey));
        screenshotSingleItem.addActionListener(
                e -> copySinglePaneScreenshotToClipboard(isRequestPane));
        popup.add(screenshotSingleItem);

        JMenuItem screenshotBothItem =
                new JMenuItem(
                        Constant.messages.getString("crimsonhttp.button.copyscreenshot.both"));
        screenshotBothItem.addActionListener(e -> copyBothPanesScreenshotToClipboard());
        popup.add(screenshotBothItem);

        final JMenuItem finalCurlItem = curlItem;
        final JMenuItem finalResendItem = resendItem;
        final JMenuItem finalMarkdownItem = markdownItem;
        final JMenuItem finalScreenshotSingleItem = screenshotSingleItem;
        final JMenuItem finalScreenshotBothItem = screenshotBothItem;

        textPane.addMouseListener(
                new MouseAdapter() {
                    @Override
                    public void mousePressed(MouseEvent e) {
                        showPopup(e);
                    }

                    @Override
                    public void mouseReleased(MouseEvent e) {
                        showPopup(e);
                    }

                    private void showPopup(MouseEvent e) {
                        if (e.isPopupTrigger()) {
                            boolean hasMessage = (currentMessage != null);
                            copyItem.setEnabled(true);
                            finalMarkdownItem.setEnabled(hasMessage);
                            if (finalCurlItem != null) {
                                finalCurlItem.setEnabled(hasMessage);
                            }
                            if (finalResendItem != null) {
                                finalResendItem.setEnabled(hasMessage);
                            }
                            finalScreenshotSingleItem.setEnabled(hasMessage);
                            finalScreenshotBothItem.setEnabled(hasMessage);
                            popup.show(e.getComponent(), e.getX(), e.getY());
                        }
                    }
                });
    }

    // -------------------------------------------------------------------------
    // Actions
    // -------------------------------------------------------------------------

    /**
     * Opens the current request in ZAP's Request Editor (Manual Request Editor) via reflection.
     */
    private void openInRequestEditor() {
        HttpMessage msg = currentMessage;
        if (msg == null) {
            return;
        }
        try {
            Control control = Control.getSingleton();
            if (control == null) {
                statusLabel.setText(
                        Constant.messages.getString("crimsonhttp.status.resendfailed"));
                return;
            }
            ExtensionLoader loader = control.getExtensionLoader();
            if (loader == null) {
                statusLabel.setText(
                        Constant.messages.getString("crimsonhttp.status.resendfailed"));
                return;
            }
            Object extRequester = loader.getExtension("ExtensionRequester");
            if (extRequester == null) {
                statusLabel.setText(
                        Constant.messages.getString("crimsonhttp.status.norequester"));
                return;
            }
            HttpMessage clone = msg.cloneRequest();
            Method displayMethod =
                    extRequester
                            .getClass()
                            .getMethod(
                                    "displayMessage",
                                    new Class<?>[] {
                                        org.zaproxy.zap.extension.httppanel.Message.class
                                    });
            displayMethod.invoke(extRequester, new Object[] {clone});
            statusLabel.setText(
                    Constant.messages.getString("crimsonhttp.status.resendopened"));
        } catch (SecurityException e) {
            statusLabel.setText("Permission denied");
        } catch (Exception e) {
            statusLabel.setText(
                    Constant.messages.getString("crimsonhttp.status.resendfailed"));
        }
    }

    /**
     * Returns {@code value} with the configured replacement text if the {@code name: value} header
     * line matches any active redaction rule, otherwise returns {@code value} unchanged.
     */
    private String redactHeaderValue(String name, String value) {
        RedactConfig config = extension.getRedactConfig();
        if (config == null || !config.isEnabled() || value == null) {
            return value;
        }
        String fullLine = name + ": " + value;
        for (RedactEntry entry : config.getActiveEntries()) {
            if (entry.matchesWithTimeout(fullLine)) {
                return config.getReplacementText();
            }
        }
        return value;
    }

    /**
     * Builds a cURL command string from the current request, applying redaction to headers and body.
     *
     * @return the cURL command, or {@code null} if no message is loaded
     */
    private String buildCurlCommand() {
        HttpMessage msg = currentMessage;
        if (msg == null) {
            return null;
        }
        try {
            HttpRequestHeader header = msg.getRequestHeader();
            if (header == null) {
                return null;
            }
            StringBuilder sb = new StringBuilder("curl");
            String method = header.getMethod();
            String uri;
            try {
                uri = header.getURI().toString();
            } catch (Exception e) {
                return null;
            }

            if (method != null && !method.equalsIgnoreCase("GET")) {
                sb.append(" -X ").append(method);
            }

            List<HttpHeaderField> headers = header.getHeaders();
            if (headers != null) {
                for (HttpHeaderField field : headers) {
                    String name = field.getName();
                    if (name == null) {
                        continue;
                    }
                    String lower = name.toLowerCase();
                    if (lower.equals("host") || lower.equals("content-length")) {
                        continue;
                    }
                    String value = redactHeaderValue(name, field.getValue());
                    sb.append(" -H ").append(shellQuote(name + ": " + value));
                }
            }

            HttpRequestBody httpRequestBody = msg.getRequestBody();
            if (httpRequestBody != null && httpRequestBody.length() > 0) {
                String bodyStr = httpRequestBody.toString();
                if (bodyStr.length() > 100000) {
                    bodyStr = bodyStr.substring(0, 100000);
                }
                RedactConfig config = extension.getRedactConfig();
                bodyStr = BodyBeautifier.redactBodyPlain(bodyStr, config);
                sb.append(" -d ").append(shellQuote(bodyStr));
            }

            sb.append(" ").append(shellQuote((uri != null) ? uri : "/"));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Wraps a string in POSIX single-quote escaping suitable for shell command embedding.
     *
     * @param s the string to quote; {@code null} is treated as an empty string
     * @return the shell-quoted string
     */
    private static String shellQuote(String s) {
        if (s == null) {
            return "''";
        }
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /**
     * Copies the request or response as formatted Markdown to the system clipboard.
     *
     * @param isRequest {@code true} to copy the request, {@code false} for the response
     */
    private void copyAsMarkdown(boolean isRequest) {
        HttpMessage msg = currentMessage;
        if (msg == null) {
            return;
        }
        try {
            String markdown = isRequest ? buildRequestMarkdown(msg) : buildResponseMarkdown(msg);
            if (markdown != null && !markdown.isEmpty()) {
                Toolkit.getDefaultToolkit()
                        .getSystemClipboard()
                        .setContents(new StringSelection(markdown), null);
                statusLabel.setText(
                        Constant.messages.getString("crimsonhttp.status.markdowncopied"));
            }
        } catch (Exception e) {
            statusLabel.setText(
                    Constant.messages.getString("crimsonhttp.status.markdownfailed"));
        }
    }

    /**
     * Builds a Markdown representation of the HTTP request with syntax-highlighted code blocks.
     *
     * @param msg the HTTP message; {@code null} returns {@code null}
     * @return the Markdown string, or {@code null} on error
     */
    private String buildRequestMarkdown(HttpMessage msg) {
        if (msg == null) {
            return null;
        }
        try {
            HttpRequestHeader header = msg.getRequestHeader();
            HttpRequestBody httpRequestBody = msg.getRequestBody();
            StringBuilder sb = new StringBuilder();

            sb.append("## Request\n\n").append("```http\n");

            String method = header.getMethod();
            String uri;
            try {
                uri = header.getURI().toString();
            } catch (Exception e) {
                uri = "/";
            }
            String version = header.getVersion();

            sb.append(escapeForMarkdown((method != null) ? method : "GET"))
                    .append(" ")
                    .append(escapeForMarkdown((uri != null) ? uri : "/"))
                    .append(" ")
                    .append(escapeForMarkdown((version != null) ? version : "HTTP/1.1"))
                    .append("\n");

            RedactConfig config = extension.getRedactConfig();
            List<HttpHeaderField> headers = header.getHeaders();
            if (headers != null) {
                for (HttpHeaderField field : headers) {
                    String name = field.getName();
                    String value = field.getValue();
                    if (name != null && value != null) {
                        sb.append(escapeForMarkdown(name))
                                .append(": ")
                                .append(escapeForMarkdown(redactHeaderValue(name, value)))
                                .append("\n");
                    }
                }
            }
            sb.append("```\n");

            if (httpRequestBody != null && httpRequestBody.length() > 0) {
                String contentType = header.getHeader("Content-Type");
                String bodyStr = prettyPrintBody(contentType, (HttpBody) httpRequestBody);
                bodyStr = BodyBeautifier.redactBodyPlain(bodyStr, config);
                String lang = detectBodyLanguage(contentType, bodyStr);
                if (lang != null) {
                    sb.append("```").append(lang).append("\n");
                }
                if (bodyStr.length() > 100000) {
                    bodyStr = bodyStr.substring(0, 100000) + "\n...[truncated]";
                }
                sb.append(escapeForMarkdown(bodyStr)).append("\n```\n");
            }

            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Builds a Markdown representation of the HTTP response with syntax-highlighted code blocks.
     *
     * @param msg the HTTP message; {@code null} returns {@code null}
     * @return the Markdown string, or {@code null} on error
     */
    private String buildResponseMarkdown(HttpMessage msg) {
        if (msg == null) {
            return null;
        }
        try {
            HttpResponseHeader header = msg.getResponseHeader();
            HttpResponseBody httpResponseBody = msg.getResponseBody();
            StringBuilder sb = new StringBuilder();

            sb.append("## Response\n\n").append("```http\n");

            String version = header.getVersion();
            int statusCode = header.getStatusCode();
            String reason = header.getReasonPhrase();

            sb.append(escapeForMarkdown((version != null) ? version : "HTTP/1.1"))
                    .append(" ")
                    .append(statusCode)
                    .append(" ")
                    .append(escapeForMarkdown((reason != null) ? reason : ""))
                    .append("\n");

            RedactConfig config = extension.getRedactConfig();
            List<HttpHeaderField> headers = header.getHeaders();
            if (headers != null) {
                for (HttpHeaderField field : headers) {
                    String name = field.getName();
                    String value = field.getValue();
                    if (name != null && value != null) {
                        sb.append(escapeForMarkdown(name))
                                .append(": ")
                                .append(escapeForMarkdown(redactHeaderValue(name, value)))
                                .append("\n");
                    }
                }
            }
            sb.append("```\n");

            if (httpResponseBody != null && httpResponseBody.length() > 0) {
                String contentType = header.getHeader("Content-Type");
                String bodyStr = prettyPrintBody(contentType, (HttpBody) httpResponseBody);
                bodyStr = BodyBeautifier.redactBodyPlain(bodyStr, config);
                String lang = detectBodyLanguage(contentType, bodyStr);
                if (lang != null) {
                    sb.append("```").append(lang).append("\n");
                }
                if (bodyStr.length() > 100000) {
                    bodyStr = bodyStr.substring(0, 100000) + "\n...[truncated]";
                }
                sb.append(escapeForMarkdown(bodyStr)).append("\n```\n");
            }

            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Detects the code-block language tag for Markdown output based on Content-Type.
     *
     * @param contentType the Content-Type header value
     * @param bodyStr     the body text (used as a fallback heuristic)
     * @return a language tag ({@code "json"}, {@code "xml"}, {@code "html"}), or {@code null}
     */
    private String detectBodyLanguage(String contentType, String bodyStr) {
        if (contentType == null) {
            return null;
        }
        String lower = contentType.toLowerCase();
        if (lower.contains("application/json") || lower.contains("text/json")) {
            return "json";
        }
        if (lower.contains("application/xml")
                || lower.contains("text/xml")
                || (lower.contains("application/") && lower.contains("+xml"))) {
            return "xml";
        }
        if (lower.contains("text/html")) {
            return "html";
        }
        if (bodyStr != null && bodyStr.trim().startsWith("{")) {
            return "json";
        }
        return null;
    }

    /**
     * Escapes backticks and backslashes in text for safe embedding inside Markdown code fences.
     *
     * @param text the raw text; {@code null} returns an empty string
     * @return the escaped text
     */
    private static String escapeForMarkdown(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '`') {
                sb.append("\\`");
            } else if (c == '\\') {
                sb.append("\\\\");
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Pretty-prints an HTTP body based on Content-Type (JSON or XML), returning the raw string
     * for unknown types.
     *
     * @param contentType the Content-Type header value
     * @param body        the HTTP body
     * @return the formatted body string
     */
    private String prettyPrintBody(String contentType, HttpBody body) {
        if (body == null || body.length() == 0) {
            return "";
        }
        try {
            String charset = BodyBeautifier.extractCharset(contentType);
            String bodyStr = BodyBeautifier.bytesToString(body.getBytes(), charset);
            if (contentType == null) {
                return bodyStr;
            }
            String lower = contentType.toLowerCase();
            if ((lower.contains("application/json") || lower.contains("text/json"))
                    && bodyStr.length() <= 300000) {
                return BodyBeautifier.prettyPrintJson(bodyStr);
            }
            if ((lower.contains("application/xml")
                            || lower.contains("text/xml")
                            || (lower.contains("application/") && lower.contains("+xml")))
                    && bodyStr.length() <= 50000) {
                return BodyBeautifier.prettyPrintXml(bodyStr);
            }
            return bodyStr;
        } catch (Exception e) {
            return body.toString();
        }
    }

    // -------------------------------------------------------------------------
    // Screenshot support
    // -------------------------------------------------------------------------

    private void takeScreenshot() {
        HttpMessage msg = currentMessage;
        if (msg == null) {
            return;
        }

        String defaultName = "message.png";
        try {
            if (msg.getHistoryRef() != null) {
                defaultName = msg.getHistoryRef().getHistoryId() + ".png";
            }
        } catch (Exception e) {
            LOGGER.info("Failed to get history reference for screenshot filename", e);
        }

        File lastDir = ScreenshotPrefs.loadDirectory();
        JFileChooser chooser = new JFileChooser(lastDir);
        chooser.setDialogTitle(
                Constant.messages.getString("crimsonhttp.screenshot.dialog.title"));
        chooser.setSelectedFile(new File(lastDir, defaultName));

        int result = chooser.showSaveDialog((Component) this);
        if (result != JFileChooser.APPROVE_OPTION) {
            return;
        }

        File selectedFile = chooser.getSelectedFile();
        if (!selectedFile.getName().toLowerCase().endsWith(".png")) {
            selectedFile =
                    new File(selectedFile.getParentFile(), selectedFile.getName() + ".png");
        }
        
        try {
            selectedFile = validateScreenshotPath(selectedFile, lastDir);
        } catch (SecurityException e) {
            statusLabel.setText("Invalid file path");
            return;
        }
        
        ScreenshotPrefs.saveDirectory(selectedFile.getParentFile());

        final File outputFile = selectedFile;
        statusLabel.setText(
                Constant.messages.getString("crimsonhttp.status.screenshotrendering"));

        screenshotExecutor.execute(
                () -> {
                    try {
                        BufferedImage image = renderScreenshot(msg);
                        boolean written = ImageIO.write(image, "png", outputFile);
                        if (!written) {
                            throw new IOException("No appropriate writer found for PNG format");
                        }
                        SwingUtilities.invokeLater(
                                () ->
                                        statusLabel.setText(
                                                String.format(
                                                        Constant.messages.getString(
                                                                "crimsonhttp.status.screenshotsaved"),
                                                        outputFile.getName())));
                    } catch (Exception e) {
                        LOGGER.error("Failed to save screenshot", e);
                        SwingUtilities.invokeLater(
                                () ->
                                        statusLabel.setText(
                                                Constant.messages.getString(
                                                        "crimsonhttp.status.screenshotfailed")));
                    }
                });
    }

    private File validateScreenshotPath(File file, File baseDir) throws SecurityException {
        try {
            File canonical = file.getCanonicalFile();
            if (!canonical.getName().toLowerCase().endsWith(".png")) {
                canonical = new File(canonical.getParentFile(), canonical.getName() + ".png");
            }
            return canonical;
        } catch (IOException e) {
            throw new SecurityException("Invalid path: " + e.getMessage());
        }
    }

    private void copySinglePaneScreenshotToClipboard(boolean isRequest) {
        HttpMessage msg = currentMessage;
        if (msg == null) {
            return;
        }
        statusLabel.setText(
                Constant.messages.getString("crimsonhttp.status.screenshotrendering"));
        screenshotExecutor.execute(
                () -> {
                    try {
                        BufferedImage image = renderSinglePaneScreenshot(msg, isRequest);
                        copyImageToClipboard(image);
                        SwingUtilities.invokeLater(
                                () ->
                                        statusLabel.setText(
                                                Constant.messages.getString(
                                                        "crimsonhttp.status.screenshotcopied")));
                    } catch (Exception e) {
                        LOGGER.error("Failed to copy screenshot to clipboard", e);
                        SwingUtilities.invokeLater(
                                () ->
                                        statusLabel.setText(
                                                Constant.messages.getString(
                                                        "crimsonhttp.status.screenshotfailed")));
                    }
                });
    }

    private void copyBothPanesScreenshotToClipboard() {
        HttpMessage msg = currentMessage;
        if (msg == null) {
            return;
        }
        statusLabel.setText(
                Constant.messages.getString("crimsonhttp.status.screenshotrendering"));
        screenshotExecutor.execute(
                () -> {
                    try {
                        BufferedImage image = renderScreenshot(msg);
                        copyImageToClipboard(image);
                        SwingUtilities.invokeLater(
                                () ->
                                        statusLabel.setText(
                                                Constant.messages.getString(
                                                        "crimsonhttp.status.screenshotcopied")));
                    } catch (Exception e) {
                        LOGGER.error("Failed to copy screenshot to clipboard", e);
                        SwingUtilities.invokeLater(
                                () ->
                                        statusLabel.setText(
                                                Constant.messages.getString(
                                                        "crimsonhttp.status.screenshotfailed")));
                    }
                });
    }

    private void copyImageToClipboard(final BufferedImage image) {
        Transferable transferable =
                new Transferable() {
                    @Override
                    public DataFlavor[] getTransferDataFlavors() {
                        return new DataFlavor[] {DataFlavor.imageFlavor};
                    }

                    @Override
                    public boolean isDataFlavorSupported(DataFlavor flavor) {
                        return flavor.equals(DataFlavor.imageFlavor);
                    }

                    @Override
                    public Object getTransferData(DataFlavor flavor)
                            throws UnsupportedFlavorException, IOException {
                        if (isDataFlavorSupported(flavor)) {
                            return image;
                        }
                        throw new UnsupportedFlavorException(flavor);
                    }
                };
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(transferable, null);
    }

    /**
     * Renders both request and response panes side-by-side or stacked into a {@link BufferedImage}.
     * Respects the current {@link #horizontal} layout and screenshot preferences.
     */
    private BufferedImage renderScreenshot(HttpMessage msg) {
        RedactConfig displayConfig = extension.getRedactConfig();
        boolean lightMode = displayConfig.isLightModeScreenshots();
        boolean optimizeSpace = displayConfig.isOptimizeScreenshotSpace();
        boolean truncateLines = displayConfig.isScreenshotTruncateLines();
        int maxWidth = displayConfig.getScreenshotMaxWidth();

        HttpMessageRenderer screenshotRenderer = buildScreenshotRenderer(displayConfig, lightMode);

        Color bgColor = lightMode ? Color.WHITE : COLOR_BG;
        Color caretColor = lightMode ? Color.BLACK : Color.WHITE;

        int padding = 8;
        int headerHeight = 24;
        int dividerWidth = 4;

        int reqWidth, respWidth, reqHeight, respHeight, imageWidth, imageHeight;

        // Calculate widths FIRST, before creating panes and rendering text
        if (horizontal) {
            int contentWidth = maxWidth - padding * 2 - dividerWidth;
            int halfContent = contentWidth / 2;
            if (optimizeSpace) {
                // We'll need to measure first, then adjust
                reqWidth = halfContent;
                respWidth = halfContent;
            } else {
                reqWidth = halfContent;
                respWidth = halfContent;
            }
        } else {
            int contentWidth = maxWidth - padding * 2;
            reqWidth = contentWidth;
            respWidth = contentWidth;
        }

        // NOW create panes and set their size BEFORE rendering text
        JTextPane offReq = buildOffscreenPane(bgColor, caretColor);
        JTextPane offResp = buildOffscreenPane(bgColor, caretColor);

        // Set size constraint BEFORE rendering text - this is crucial for wrapping
        if (!truncateLines) {
            sizePane(offReq, reqWidth);
            sizePane(offResp, respWidth);
        }

        Graphics2D g2d = null;
        try {
            // Render text NOW that the size constraint is in place
            screenshotRenderer.renderRequest(
                    offReq.getStyledDocument(),
                    msg.getRequestHeader(),
                    (HttpBody) msg.getRequestBody());
            screenshotRenderer.renderResponse(
                    offResp.getStyledDocument(),
                    msg.getResponseHeader(),
                    (HttpBody) msg.getResponseBody());

            if (horizontal) {
                // For optimizeSpace, recalculate widths based on actual content size
                if (optimizeSpace) {
                int contentWidth = maxWidth - padding * 2 - dividerWidth;
                int halfContent = contentWidth / 2;
                int reqOptimal = Math.max(Math.min(offReq.getPreferredSize().width, halfContent), 300);
                reqWidth = Math.min(reqOptimal, halfContent);
                respWidth = contentWidth - reqWidth;

                // Re-size with the adjusted widths
                if (!truncateLines) {
                    sizePane(offReq, reqWidth);
                    sizePane(offResp, respWidth);
                }
            }

            reqHeight =
                    Math.min(
                            offReq.getPreferredSize().height,
                            MAX_SCREENSHOT_HEIGHT - headerHeight - padding * 2);
            respHeight =
                    Math.min(
                            offResp.getPreferredSize().height,
                            MAX_SCREENSHOT_HEIGHT - headerHeight - padding * 2);

            LOGGER.info("Final heights - reqHeight: {}, respHeight: {}", reqHeight, respHeight);
            imageWidth = maxWidth;
            imageHeight =
                    Math.min(
                            padding + headerHeight + Math.max(reqHeight, respHeight) + padding,
                            MAX_SCREENSHOT_HEIGHT);
        } else {
            // Vertical layout - recalculate if needed for optimizeSpace
            if (optimizeSpace && !truncateLines) {
                int contentWidth = maxWidth - padding * 2;
                int reqOptimal = Math.max(Math.min(offReq.getPreferredSize().width, contentWidth), 300);
                int respOptimal = Math.max(Math.min(offResp.getPreferredSize().width, contentWidth), 300);
                reqWidth = Math.min(reqOptimal, contentWidth);
                respWidth = Math.min(respOptimal, contentWidth);

                // Re-size with the adjusted widths
                sizePane(offReq, reqWidth);
                sizePane(offResp, respWidth);
            }

            reqHeight =
                    Math.min(
                            offReq.getPreferredSize().height,
                            MAX_VERTICAL_PANE_HEIGHT - headerHeight - padding);
            respHeight =
                    Math.min(
                            offResp.getPreferredSize().height,
                            MAX_VERTICAL_PANE_HEIGHT - headerHeight - padding);
            imageWidth = maxWidth;
            imageHeight =
                    Math.min(
                            padding
                                    + headerHeight
                                    + reqHeight
                                    + padding
                                    + headerHeight
                                    + respHeight
                                    + padding,
                            MAX_SCREENSHOT_HEIGHT);
        }

        BufferedImage image =
                new BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_ARGB);
        g2d = image.createGraphics();
        try {
            Color labelColor = lightMode ? COLOR_LABEL_LIGHT : COLOR_LABEL_DARK;
            Color dividerColor = lightMode ? COLOR_DIVIDER_LIGHT : COLOR_DIVIDER_DARK;
            Font baseFont = g2d.getFont();
            Font labelFont = (baseFont != null) ? baseFont.deriveFont(Font.BOLD, 12.0f) : new Font("Monospaced", Font.BOLD, 12);
            int labelBaseline = padding + 16;
            int textInset = 4;

            g2d.setColor(bgColor);
            g2d.fillRect(0, 0, imageWidth, imageHeight);

            String reqLabel =
                    Constant.messages.getString("crimsonhttp.panel.request.label");
            String respLabel =
                    Constant.messages.getString("crimsonhttp.panel.response.label");

            if (horizontal) {
                g2d.setColor(labelColor);
                g2d.setFont(labelFont);
                g2d.drawString(reqLabel, padding + textInset, labelBaseline);

                offReq.setBounds(0, 0, truncateLines ? NO_WRAP_WIDTH : reqWidth, reqHeight);

                // Ensure the view is properly laid out before painting
                if (!truncateLines) {
                    // Force a complete view hierarchy invalidation and relayout
                    offReq.invalidate();
                    sizePane(offReq, reqWidth);
                    offReq.doLayout();
                }
                g2d.translate(padding, padding + headerHeight);
                if (truncateLines) {
                    Shape prevClip = g2d.getClip();
                    g2d.clipRect(0, 0, reqWidth, reqHeight);
                    offReq.paint(g2d);
                    g2d.setClip(prevClip);
                } else {
                    offReq.paint(g2d);
                }
                g2d.translate(-padding, -(padding + headerHeight));

                g2d.setColor(dividerColor);
                g2d.fillRect(padding + reqWidth, padding, dividerWidth, imageHeight - padding * 2);

                int respX = padding + reqWidth + dividerWidth;
                g2d.setColor(labelColor);
                g2d.setFont(labelFont);
                g2d.drawString(respLabel, respX + textInset, labelBaseline);

                offResp.setBounds(0, 0, truncateLines ? NO_WRAP_WIDTH : respWidth, respHeight);
                // Ensure the view is properly laid out before painting
                if (!truncateLines) {
                    // Force a complete view hierarchy invalidation and relayout
                    offResp.invalidate();
                    sizePane(offResp, respWidth);
                    offResp.doLayout();
                }
                g2d.translate(respX, padding + headerHeight);
                if (truncateLines) {
                    Shape prevClip = g2d.getClip();
                    g2d.clipRect(0, 0, respWidth, respHeight);
                    offResp.paint(g2d);
                    g2d.setClip(prevClip);
                } else {
                    offResp.paint(g2d);
                }
            } else {
                int y = padding;
                g2d.setColor(labelColor);
                g2d.setFont(labelFont);
                g2d.drawString(reqLabel, padding + textInset, y + 16);
                y += headerHeight;

                offReq.setBounds(0, 0, truncateLines ? NO_WRAP_WIDTH : reqWidth, reqHeight);
                // Ensure the view is properly laid out before painting
                if (!truncateLines) {
                    // Force a complete view hierarchy invalidation and relayout
                    offReq.invalidate();
                    sizePane(offReq, reqWidth);
                    offReq.doLayout();
                }
                g2d.translate(0, y);
                if (truncateLines) {
                    Shape prevClip = g2d.getClip();
                    g2d.clipRect(0, 0, reqWidth, reqHeight);
                    offReq.paint(g2d);
                    g2d.setClip(prevClip);
                } else {
                    offReq.paint(g2d);
                }
                g2d.translate(0, -y);
                y += reqHeight + padding;

                g2d.setColor(labelColor);
                g2d.setFont(labelFont);
                g2d.drawString(respLabel, padding + textInset, y + 16);
                y += headerHeight;

                offResp.setBounds(0, 0, truncateLines ? NO_WRAP_WIDTH : respWidth, respHeight);
                // Ensure the view is properly laid out before painting
                if (!truncateLines) {
                    // Force a complete view hierarchy invalidation and relayout
                    offResp.invalidate();
                    sizePane(offResp, respWidth);
                    offResp.doLayout();
                }
                g2d.translate(0, y);
                if (truncateLines) {
                    Shape prevClip = g2d.getClip();
                    g2d.clipRect(0, 0, respWidth, respHeight);
                    offResp.paint(g2d);
                    g2d.setClip(prevClip);
                } else {
                    offResp.paint(g2d);
                }
            }
        } finally {
            if (g2d != null) {
                g2d.dispose();
            }
            offReq.removeNotify();
            offResp.removeNotify();
        }
        return image;
        } catch (Exception e) {
            LOGGER.error("Failed to render screenshot", e);
            return null;
        }
    }

    private BufferedImage renderSinglePaneScreenshot(HttpMessage msg, boolean isRequest) {
        RedactConfig displayConfig = extension.getRedactConfig();
        boolean lightMode = displayConfig.isLightModeScreenshots();
        boolean optimizeSpace = displayConfig.isOptimizeScreenshotSpace();
        boolean truncateLines = displayConfig.isScreenshotTruncateLines();
        int maxWidth = displayConfig.getScreenshotMaxWidth();

        HttpMessageRenderer screenshotRenderer = buildScreenshotRenderer(displayConfig, lightMode);

        Color bgColor = lightMode ? Color.WHITE : COLOR_BG;
        Color caretColor = lightMode ? Color.BLACK : Color.WHITE;

        JTextPane offPane = buildOffscreenPane(bgColor, caretColor);
        StyledDocument doc = offPane.getStyledDocument();
        if (isRequest) {
            screenshotRenderer.renderRequest(
                    doc, msg.getRequestHeader(), (HttpBody) msg.getRequestBody());
        } else {
            screenshotRenderer.renderResponse(
                    doc, msg.getResponseHeader(), (HttpBody) msg.getResponseBody());
        }

        int padding = 8;
        int headerHeight = 24;
        int contentWidth = maxWidth - padding * 2;

        sizePane(offPane, truncateLines ? NO_WRAP_WIDTH : contentWidth);
        int paneWidth =
                optimizeSpace
                        ? Math.max(Math.min(offPane.getPreferredSize().width, contentWidth), 300)
                        : contentWidth;
        sizePane(offPane, truncateLines ? NO_WRAP_WIDTH : paneWidth);
        int paneHeight = Math.min(offPane.getPreferredSize().height, MAX_SINGLE_PANE_HEIGHT);

        int imageWidth = paneWidth + padding * 2;
        int imageHeight = Math.min(padding + headerHeight + paneHeight + padding, MAX_SCREENSHOT_HEIGHT);

        BufferedImage image =
                new BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = image.createGraphics();
        try {
            g2d.setColor(bgColor);
            g2d.fillRect(0, 0, imageWidth, imageHeight);

            String label =
                    isRequest
                            ? Constant.messages.getString("crimsonhttp.panel.request.label")
                            : Constant.messages.getString("crimsonhttp.panel.response.label");
            Color labelColor = lightMode ? COLOR_LABEL_LIGHT : COLOR_LABEL_DARK;
            Font baseFont = g2d.getFont();
            Font labelFont = (baseFont != null) ? baseFont.deriveFont(Font.BOLD, 12.0f) : new Font("Monospaced", Font.BOLD, 12);

            g2d.setColor(labelColor);
            g2d.setFont(labelFont);
            g2d.drawString(label, padding + 4, padding + 16);

            offPane.setBounds(0, 0, truncateLines ? NO_WRAP_WIDTH : paneWidth, paneHeight);
            // Ensure the view is properly laid out before painting
            if (!truncateLines) {
                // Force a complete view hierarchy invalidation and relayout
                offPane.invalidate();
                sizePane(offPane, paneWidth);
                offPane.doLayout();
            }
            g2d.translate(padding, padding + headerHeight);
            if (truncateLines) {
                Shape prevClip = g2d.getClip();
                g2d.clipRect(0, 0, paneWidth, paneHeight);
                offPane.paint(g2d);
                g2d.setClip(prevClip);
            } else {
                offPane.paint(g2d);
            }
        } finally {
            g2d.dispose();
            offPane.removeNotify();
        }
        return image;
    }

    /**
     * Builds a renderer for off-screen screenshot rendering.
     *
     * <p>In light-mode a subclass with inverted colours is returned; otherwise the standard
     * renderer is returned. Redaction settings are copied from {@code displayConfig}.
     */
    // Cached colours for the light-mode screenshot renderer
    private static final Color LIGHT_COLOR_KEY = new Color(180, 20, 40);
    private static final Color LIGHT_COLOR_STRING = new Color(0, 0, 128);
    private static final Color LIGHT_COLOR_NUMBER = new Color(120, 60, 20);
    private static final Color LIGHT_COLOR_BOOL_NULL = new Color(120, 40, 120);
    private static final Color LIGHT_COLOR_PUNCT = new Color(80, 80, 80);
    private static final Color LIGHT_COLOR_OFFSET = new Color(120, 120, 120);
    private static final Color LIGHT_COLOR_REDACTED = new Color(40, 80, 180);

    private HttpMessageRenderer buildScreenshotRenderer(
            RedactConfig displayConfig, boolean lightMode) {
        HttpMessageRenderer screenshotRenderer;
        if (lightMode) {
            screenshotRenderer =
                    new HttpMessageRenderer() {
                        @Override
                        public void initAttributes() {
                            initAttr(attrAccent, LIGHT_COLOR_NUMBER);
                            initAttr(attrKeyword, LIGHT_COLOR_KEY);
                            initAttr(attrLiteral, LIGHT_COLOR_STRING);
                            initAttr(attrPunct, LIGHT_COLOR_PUNCT);
                            initAttr(attrStatus2xx, LIGHT_COLOR_STRING);
                            initAttr(attrStatus3xx, LIGHT_COLOR_NUMBER);
                            initAttr(attrStatus4xx, LIGHT_COLOR_KEY);
                            initAttr(attrBoolNull, LIGHT_COLOR_BOOL_NULL);
                            initAttr(attrOffset, LIGHT_COLOR_OFFSET);
                            initAttr(attrRedacted, LIGHT_COLOR_REDACTED);
                            initAttrBold(attrBoldMethod, LIGHT_COLOR_NUMBER);
                            initAttr(attrUrlNavy, new Color(0, 0, 128));
                        }
                    };
        } else {
            screenshotRenderer = new HttpMessageRenderer();
        }
        screenshotRenderer.initAttributes();
        RedactConfig screenshotConfig = new RedactConfig();
        screenshotConfig.setEnabled(displayConfig.isRedactScreenshots());
        screenshotConfig.setReplacementText(displayConfig.getReplacementText());
        screenshotConfig.setEntries(new ArrayList<>(displayConfig.getEntries()));
        screenshotRenderer.setRedactConfig(screenshotConfig);
        return screenshotRenderer;
    }

    private static JTextPane buildOffscreenPane(Color bg, Color caret) {
        JTextPane pane = new JTextPane();
        // FlowView only calls breakView() on children that return ExcellentBreakWeight.
        // LabelView returns GoodBreakWeight when there is no whitespace in the run (e.g. a long
        // URL), so the URL overflows the line instead of wrapping. Override to always return
        // ExcellentBreakWeight on the X axis so every character position is a valid break point.
        StyledEditorKit kit =
                new StyledEditorKit() {
                    @Override
                    public ViewFactory getViewFactory() {
                        ViewFactory svf = super.getViewFactory();
                        return elem -> {
                            View v = svf.create(elem);

                            // Wrap ParagraphView to force wrapping at constrained width
                            if (v instanceof javax.swing.text.ParagraphView) {
                                return new javax.swing.text.ParagraphView(elem) {
                                    @Override
                                    protected void layout(int width, int height) {
                                        // Force layout at the constrained width instead of preferred width
                                        Container c = getContainer();
                                        if (c != null && c instanceof JTextPane) {
                                            JTextPane pane = (JTextPane) c;
                                            int constrainedWidth = pane.getWidth();
                                            if (constrainedWidth > 0 && constrainedWidth < width) {
                                                super.layout(constrainedWidth, height);
                                                return;
                                            }
                                        }
                                        super.layout(width, height);
                                    }
                                };
                            }
                            // Also wrap LabelView to return ExcellentBreakWeight
                            if (v instanceof LabelView) {
                                return new LabelView(elem) {
                                    @Override
                                    public int getBreakWeight(int axis, float pos, float len) {
                                        // Return ExcellentBreakWeight for X-axis to allow breaking anywhere
                                        if (axis == View.X_AXIS) {
                                            return View.ExcellentBreakWeight;
                                        }
                                        return super.getBreakWeight(axis, pos, len);
                                    }

                                    @Override
                                    public View breakView(int axis, int offset, float pos, float len) {
                                        return super.breakView(axis, offset, pos, len);
                                    }
                                };
                            }
                            return v;
                        };
                    }
                };
        pane.setEditorKit(kit);
        pane.setEditable(false);
        pane.setBackground(bg);
        pane.setCaretColor(caret);
        pane.addNotify();
        return pane;
    }

    /**
     * Manually breaks long lines in a JTextPane by inserting line breaks.
     * This is a workaround for Swing's text view system not respecting size constraints for wrapping.
     *
     * @param pane the text pane to process
     * @param maxWidth the maximum width in pixels (approximately 60 chars per 490px)
     */
    private static void breakLongLines(JTextPane pane, int maxWidth) {
        try {
            StyledDocument doc = pane.getStyledDocument();
            String text = pane.getText();
            if (text == null || text.isEmpty()) {
                return;
            }

            // Calculate approximate character width (monospace font = 12px)
            // 490px / 12px ≈ 40 chars, but we use 50 to be safe
            int maxCharsPerLine = (maxWidth * 50) / 1000; // Approximate scaling
            if (maxCharsPerLine < 30) maxCharsPerLine = 30; // Minimum reasonable length
            if (maxCharsPerLine > 100) maxCharsPerLine = 100; // Maximum reasonable length

            // Create a new document with line breaks added
            StyledDocument newDoc = pane.getStyledDocument();
            newDoc.remove(0, newDoc.getLength());

            String[] lines = text.split("\n", -1);
            for (String line : lines) {
                if (line.length() <= maxCharsPerLine) {
                    // Line is short enough, keep it as is
                    insertTextWithAttributes(doc, line + "\n");
                } else {
                    // Break long line while trying to preserve attributes
                    insertStyledTextWithBreaks(doc, line, maxCharsPerLine);
                }
            }

        } catch (Exception e) {
            LOGGER.error("Failed to break long lines", e);
        }
    }

    /**
     * Helper method to insert styled text from one document to another.
     * This preserves the syntax highlighting attributes.
     */
    private static void insertTextWithAttributes(StyledDocument destDoc, String text) {
        try {
            // For now, we'll insert plain text to avoid complexity
            // The syntax highlighting comes from the renderer, not the stored attributes
            destDoc.insertString(destDoc.getLength(), text, null);
        } catch (Exception e) {
            LOGGER.error("Failed to insert text with attributes", e);
        }
    }

    /**
     * Breaks a long line into multiple lines while trying to preserve some formatting.
     * This is a simplified version that focuses on breaking at reasonable positions.
     */
    private static void insertStyledTextWithBreaks(StyledDocument doc, String line, int maxChars) {
        try {
            // Break the line at maxChars intervals
            int pos = 0;
            while (pos < line.length()) {
                int end = Math.min(pos + maxChars, line.length());
                String segment = line.substring(pos, end);
                doc.insertString(doc.getLength(), segment + "\n", null);
                pos = end;
            }
        } catch (Exception e) {
            LOGGER.error("Failed to break styled text", e);
        }
    }

    private static void sizePane(JTextPane pane, int width) {
        LOGGER.info("sizePane called - width: {}, current size: {}x{}, preferred: {}x{}",
                width, pane.getWidth(), pane.getHeight(),
                pane.getPreferredSize().width, pane.getPreferredSize().height);

        // First, set to 0 to force invalidation of cached layout
        pane.setSize(0, 0);
        pane.getUI().getRootView(pane).setSize(0, 0);

        // Then set to target size to trigger proper layout calculation
        pane.setSize(width, Integer.MAX_VALUE);
        View root = pane.getUI().getRootView(pane);
        root.setSize(width, Integer.MAX_VALUE);
        root.preferenceChanged(null, true, true);

        LOGGER.info("After sizePane - size: {}x{}, preferred: {}x{}",
                pane.getWidth(), pane.getHeight(),
                pane.getPreferredSize().width, pane.getPreferredSize().height);
    }

    // -------------------------------------------------------------------------
    // Screenshot directory preference
    // -------------------------------------------------------------------------

    private static final class ScreenshotPrefs {

        private static final String CONFIG_DIR = "crimsonhttp";
        private static final String CONFIG_FILE = "screenshot-prefs.xml";
        private static final String KEY_DIR = "screenshot.lastDirectory";

        private ScreenshotPrefs() {}

        static File loadDirectory() {
            File configFile =
                    new File(new File(Constant.getZapHome(), CONFIG_DIR), CONFIG_FILE);
            if (!configFile.exists()) {
                return new File(System.getProperty("user.home"));
            }
            try {
                ZapXmlConfiguration config = new ZapXmlConfiguration(configFile);
                String path = config.getString(KEY_DIR, null);
                if (path != null) {
                    File dir = new File(path);
                    if (dir.isDirectory()) {
                        return dir;
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to load screenshot directory preference", e);
            }
            return new File(System.getProperty("user.home"));
        }

        static void saveDirectory(File dir) {
            if (dir == null) {
                return;
            }
            try {
                File configFile =
                        new File(new File(Constant.getZapHome(), CONFIG_DIR), CONFIG_FILE);
                configFile.getParentFile().mkdirs();
                ZapXmlConfiguration config =
                        configFile.exists()
                                ? new ZapXmlConfiguration(configFile)
                                : new ZapXmlConfiguration();
                config.setProperty(KEY_DIR, dir.getAbsolutePath());
                config.save(configFile);
            } catch (Exception e) {
                LOGGER.error("Failed to save screenshot directory preference", e);
            }
        }
    }
}
