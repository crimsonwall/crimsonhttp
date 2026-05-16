/*
 * CrimsonView - Document-Ready HTTP Screenshots for ZAP.
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
package com.crimsonwall.crimsonview.ui;

import com.crimsonwall.crimsonview.ExtensionCrimsonView;
import com.crimsonwall.crimsonview.redact.RedactConfig;
import com.crimsonwall.crimsonview.redact.RedactEntry;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
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
import javax.swing.JColorChooser;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextPane;
import javax.swing.JToggleButton;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.AncestorEvent;
import javax.swing.event.AncestorListener;
import javax.swing.text.AttributeSet;
import javax.swing.text.AbstractDocument;
import javax.swing.text.LabelView;
import javax.swing.text.StyleConstants;
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
 * Main display panel for CrimsonView.
 *
 * <p>Shows request and response panes side-by-side (horizontal) or stacked (vertical) in a
 * {@link JSplitPane}. The layout orientation and divider position are persisted across sessions via
 * {@link LayoutPrefs}.
 *
 * <p>Each text pane has a context menu offering copy, Markdown export, cURL generation,
 * Request Editor integration, and screenshot capture (both to clipboard and to file).
 */
public final class CrimsonViewPanel extends AbstractPanel {

    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = LogManager.getLogger(CrimsonViewPanel.class);

    private static final Color COLOR_BG = new Color(40, 44, 52);
    private static final Color COLOR_TOOLBAR_BG = new Color(50, 54, 62);
    private static final Color COLOR_STATUS_BG = new Color(33, 37, 43);
    private static final Color COLOR_STATUS_FG = new Color(140, 148, 160);
    private static final Color COLOR_SECTION_BAR = new Color(55, 59, 67);
    private static final Color COLOR_SECTION_FG = new Color(220, 80, 100);
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
                        Thread t = new Thread(r, "crimsonview-screenshot-render");
                        t.setDaemon(true);
                        return t;
                    },
                    new ThreadPoolExecutor.DiscardOldestPolicy());

    private static ImageIcon iconVertical;
    private static ImageIcon iconHorizontal;

    private final transient ExtensionCrimsonView extension;
    private final transient HttpMessageRenderer renderer;

    private JTextPane requestPane;
    private JTextPane responsePane;
    private JScrollPane requestScrollPane;
    private JScrollPane responseScrollPane;
    private JLabel statusLabel;
    private JSplitPane splitPane;
    private JPanel requestPanel;
    private JPanel responsePanel;
    private JButton toggleButton;
    private volatile transient HttpMessage currentMessage;
    private volatile boolean horizontal;
    private Timer dividerSaveTimer;

    // Annotation support
    private final transient AnnotationStore annotationStore = new AnnotationStore();
    private final transient ManualRedactStore manualRedactStore = new ManualRedactStore();
    private transient boolean lastFocusedIsRequest = true;

    private enum AnnotationMode { NONE, PENCIL, HIGHLIGHTER }
    private transient AnnotationMode annotationMode = AnnotationMode.NONE;
    private transient Color annotationColor = Color.YELLOW;
    private JToggleButton pencilButton;
    private JToggleButton highlighterButton;
    private JButton colorButton;

    /**
     * Constructs the main display panel.
     *
     * @param extension the parent extension used for config access and message refresh
     */
    public CrimsonViewPanel(ExtensionCrimsonView extension) {
        this.extension = extension;
        this.renderer = new HttpMessageRenderer();
        this.renderer.initAttributes();
        this.renderer.setRedactConfig(extension.getRedactConfig());
        this.horizontal = LayoutPrefs.loadHorizontal();
        this.annotationColor = new Color(extension.getRedactConfig().getAnnotationColor());
        initialize();
        setIcon(ExtensionCrimsonView.getIcon());
    }

    private void initialize() {
        setLayout(new BorderLayout());
        setName(Constant.messages.getString("crimsonview.panel.title"));

        add(createToolbar(), BorderLayout.NORTH);

        requestPanel = createHalfPanel("crimsonview.panel.request.label", true);
        responsePanel = createHalfPanel("crimsonview.panel.response.label", false);

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
        panel.setBackground(COLOR_BG);

        JLabel label = new JLabel(" " + Constant.messages.getString(titleKey));
        label.setFont(label.getFont().deriveFont(Font.BOLD, 11.0f));
        label.setForeground(COLOR_SECTION_FG);
        label.setBackground(COLOR_SECTION_BAR);
        label.setOpaque(true);
        label.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 4));
        panel.add(label, BorderLayout.NORTH);

        JTextPane pane = createTextPane(isRequest);
        if (isRequest) {
            requestPane = pane;
        } else {
            responsePane = pane;
        }
        JScrollPane scrollPane = new JScrollPane(pane);
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        scrollPane.setViewportBorder(BorderFactory.createEmptyBorder());
        if (isRequest) {
            requestScrollPane = scrollPane;
        } else {
            responseScrollPane = scrollPane;
        }
        panel.add(scrollPane, BorderLayout.CENTER);
        return panel;
    }

    /**
     * Creates a text pane for the request or response with annotation support and context menu.
     *
     * @param isRequestPane {@code true} for the request pane, {@code false} for the response pane
     * @return the configured text pane
     */
    private JTextPane createTextPane(boolean isRequestPane) {
        JTextPane pane = new JTextPane();
        pane.setEditable(false);
        pane.setBackground(COLOR_BG);
        pane.setCaretColor(Color.WHITE);
        addContextMenu(pane, isRequestPane);
        addAnnotationListener(pane, isRequestPane);
        pane.addFocusListener(
                new FocusAdapter() {
                    @Override
                    public void focusGained(FocusEvent e) {
                        lastFocusedIsRequest = isRequestPane;
                    }
                });
        return pane;
    }

    /**
     * Builds a new {@link JSplitPane} oriented according to the current {@link #horizontal} flag
     * and wires up the divider-save timer.
     */
    private JSplitPane buildSplitPane() {
        JSplitPane pane = new JSplitPane(horizontal ? JSplitPane.HORIZONTAL_SPLIT : JSplitPane.VERTICAL_SPLIT);
        pane.setResizeWeight(0.5);
        pane.setDividerSize(3);
        pane.setBorder(BorderFactory.createEmptyBorder());
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

    /**
     * Applies the saved divider position ratio to the split pane.
     */
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

    /**
     * Creates the top toolbar containing annotation tools, layout toggle, and screenshot button.
     *
     * @return the configured toolbar panel
     */
    private JPanel createToolbar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(COLOR_TOOLBAR_BG);
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(30, 33, 40)));
        bar.setOpaque(true);

        // Annotation buttons on the left (WEST)
        JPanel westButtons = new JPanel();
        westButtons.setLayout(new BoxLayout(westButtons, BoxLayout.X_AXIS));
        westButtons.setOpaque(false);
        westButtons.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));

        colorButton = new JButton();
        colorButton.setToolTipText(
                Constant.messages.getString("crimsonview.button.annotationcolor.tooltip"));
        colorButton.setFocusPainted(false);
        updateColorButtonIcon(colorButton, annotationColor);
        colorButton.addActionListener(e -> {
            Color chosen = JColorChooser.showDialog(
                    (Component) CrimsonViewPanel.this,
                    "Choose annotation color",
                    annotationColor);
            if (chosen != null) {
                annotationColor = chosen;
                updateColorButtonIcon(colorButton, chosen);
                extension.getRedactConfig().setAnnotationColor(chosen.getRGB());
                extension.getRedactConfig().save();
            }
        });
        westButtons.add(colorButton);
        westButtons.add(Box.createHorizontalStrut(2));

        pencilButton = new JToggleButton("Pencil");
        pencilButton.setToolTipText(
                Constant.messages.getString("crimsonview.button.pencil.tooltip"));
        pencilButton.setFocusPainted(false);
        pencilButton.addActionListener(e -> {
            if (pencilButton.isSelected()) {
                annotationMode = AnnotationMode.PENCIL;
                highlighterButton.setSelected(false);
            } else {
                annotationMode = AnnotationMode.NONE;
            }
        });
        westButtons.add(pencilButton);
        westButtons.add(Box.createHorizontalStrut(2));

        highlighterButton = new JToggleButton("Highlight");
        highlighterButton.setToolTipText(
                Constant.messages.getString("crimsonview.button.highlighter.tooltip"));
        highlighterButton.setFocusPainted(false);
        highlighterButton.addActionListener(e -> {
            if (highlighterButton.isSelected()) {
                annotationMode = AnnotationMode.HIGHLIGHTER;
                pencilButton.setSelected(false);
            } else {
                annotationMode = AnnotationMode.NONE;
            }
        });
        westButtons.add(highlighterButton);
        westButtons.add(Box.createHorizontalStrut(2));

        JButton resetAnnotationsButton = new JButton("Reset");
        resetAnnotationsButton.setToolTipText(
                Constant.messages.getString("crimsonview.button.resetannotations.tooltip"));
        resetAnnotationsButton.setFocusPainted(false);
        resetAnnotationsButton.addActionListener(e -> resetAnnotations());
        westButtons.add(resetAnnotationsButton);
        westButtons.add(Box.createHorizontalStrut(6));

        JButton redactButton =
                new JButton(Constant.messages.getString("crimsonview.button.redact"));
        redactButton.setToolTipText(
                Constant.messages.getString("crimsonview.button.redact.tooltip"));
        redactButton.setFocusPainted(false);
        redactButton.addActionListener(e -> redactSelection());
        westButtons.add(redactButton);

        bar.add(westButtons, BorderLayout.WEST);

        // Existing buttons on the right (EAST)
        JPanel buttons = new JPanel();
        buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
        buttons.setOpaque(false);
        buttons.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));

        toggleButton = new JButton();
        toggleButton.setToolTipText(
                Constant.messages.getString("crimsonview.button.toggle.tooltip"));
        toggleButton.setFocusPainted(false);
        toggleButton.addActionListener(e -> toggleLayout());
        buttons.add(toggleButton);
        buttons.add(Box.createHorizontalStrut(4));

        JButton screenshotButton =
                new JButton(Constant.messages.getString("crimsonview.button.screenshot"));
        screenshotButton.setToolTipText(
                Constant.messages.getString("crimsonview.button.screenshot.tooltip"));
        screenshotButton.setFocusPainted(false);
        screenshotButton.addActionListener(e -> takeScreenshot());
        buttons.add(screenshotButton);

        bar.add(buttons, BorderLayout.EAST);
        return bar;
    }

    /**
     * Creates the status bar panel that displays the current request URI.
     *
     * @return the configured status bar panel
     */
    private JPanel createStatusBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(COLOR_STATUS_BG);
        bar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(30, 33, 40)));
        statusLabel = new JLabel(Constant.messages.getString("crimsonview.status.ready"));
        statusLabel.setForeground(COLOR_STATUS_FG);
        statusLabel.setFont(statusLabel.getFont().deriveFont(11.0f));
        statusLabel.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 4));
        bar.add(statusLabel, BorderLayout.WEST);
        return bar;
    }

    /**
     * Toggles between horizontal (side-by-side) and vertical (stacked) layout.
     */
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

    /**
     * Updates the toggle button icon to show the next layout orientation.
     */
    private void updateToggleIcon() {
        toggleButton.setIcon(horizontal ? getHorizontalIcon() : getVerticalIcon());
    }

    private static synchronized ImageIcon getVerticalIcon() {
        if (iconVertical == null) {
            URL url = ExtensionCrimsonView.class.getResource("crimsonview-toggle-v.png");
            if (url != null) {
                iconVertical = new ImageIcon(url);
            }
        }
        return iconVertical;
    }

    private static synchronized ImageIcon getHorizontalIcon() {
        if (iconHorizontal == null) {
            URL url = ExtensionCrimsonView.class.getResource("crimsonview-toggle-h.png");
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

            // Re-apply stored annotations for this message
            int historyId = getHistoryId(msg);
            if (historyId >= 0) {
                List<TextAnnotation> reqAnns = annotationStore.getAnnotations(historyId, true);
                if (!reqAnns.isEmpty()) {
                    AnnotationStore.applyToDocument(reqDoc, reqAnns);
                }
                List<TextAnnotation> respAnns = annotationStore.getAnnotations(historyId, false);
                if (!respAnns.isEmpty()) {
                    AnnotationStore.applyToDocument(respDoc, respAnns);
                }

                // Re-apply stored manual redactions
                String replacementText = extension.getRedactConfig().getReplacementText();
                List<int[]> reqRedactions = manualRedactStore.getRedactions(historyId, true);
                if (!reqRedactions.isEmpty()) {
                    ManualRedactStore.applyToDocument(
                            reqDoc, reqRedactions, renderer.getAttrRedacted(), replacementText);
                }
                List<int[]> respRedactions = manualRedactStore.getRedactions(historyId, false);
                if (!respRedactions.isEmpty()) {
                    ManualRedactStore.applyToDocument(
                            respDoc, respRedactions, renderer.getAttrRedacted(), replacementText);
                }
            }

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
        statusLabel.setText(Constant.messages.getString("crimsonview.status.cleared"));
    }

    /** Stops background timers and clears the display. Called during extension unload. */
    public void cleanup() {
        clearDisplay();
        if (dividerSaveTimer != null) {
            dividerSaveTimer.stop();
        }
        screenshotExecutor.shutdown();
        try {
            if (!screenshotExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                screenshotExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            screenshotExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
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
    // Annotation support
    // -------------------------------------------------------------------------

    /**
     * Adds a mouse listener to the text pane for handling annotation creation via text selection.
     *
     * @param pane the text pane to annotate
     * @param isRequestPane {@code true} for the request pane, {@code false} for the response pane
     */
    private void addAnnotationListener(JTextPane pane, boolean isRequestPane) {
        pane.addMouseListener(
                new MouseAdapter() {
                    @Override
                    public void mouseReleased(MouseEvent e) {
                        if (annotationMode == AnnotationMode.NONE) return;
                        if (e.isPopupTrigger()) return;
                        handleAnnotationSelection(pane, isRequestPane);
                    }

                    @Override
                    public void mousePressed(MouseEvent e) {
                        // Also handle on press for platforms where release isn't the trigger
                    }
                });
    }

    /**
     * Handles text selection to create an annotation (underline or highlight) based on the current mode.
     *
     * @param pane the text pane with the selection
     * @param isRequest {@code true} for the request pane, {@code false} for the response pane
     */
    private void handleAnnotationSelection(JTextPane pane, boolean isRequest) {
        int selStart = pane.getSelectionStart();
        int selEnd = pane.getSelectionEnd();
        if (selStart == selEnd) return;

        HttpMessage msg = currentMessage;
        if (msg == null) return;
        int historyId = getHistoryId(msg);
        if (historyId < 0) {
            statusLabel.setText(
                    Constant.messages.getString("crimsonview.status.cannotannotate"));
            return;
        }

        int start = Math.min(selStart, selEnd);
        int length = Math.abs(selEnd - selStart);

        TextAnnotation.Type type =
                (annotationMode == AnnotationMode.PENCIL)
                        ? TextAnnotation.Type.UNDERLINE
                        : TextAnnotation.Type.HIGHLIGHT;

        TextAnnotation annotation = new TextAnnotation(start, length, type, annotationColor);
        annotationStore.addAnnotation(historyId, isRequest, annotation);

        AnnotationStore.applyToDocument(pane.getStyledDocument(), List.of(annotation));

        // Clear selection after applying
        pane.setCaretPosition(start);
    }

    /**
     * Clears all annotations and manual redactions for the current message and re-renders it.
     */
    private void resetAnnotations() {
        HttpMessage msg = currentMessage;
        if (msg == null) return;
        int historyId = getHistoryId(msg);
        if (historyId < 0) return;

        annotationStore.clearAnnotations(historyId, true);
        annotationStore.clearAnnotations(historyId, false);
        manualRedactStore.clearRedactions(historyId, true);
        manualRedactStore.clearRedactions(historyId, false);

        // Re-render to remove annotation styling
        displayMessage(msg);
        statusLabel.setText(
                Constant.messages.getString("crimsonview.status.annotationscleared"));
    }

    /**
     * Replaces the selected text with the redaction placeholder and stores the manual redaction.
     */
    private void redactSelection() {
        boolean isRequest = lastFocusedIsRequest;
        JTextPane pane = isRequest ? requestPane : responsePane;
        int selStart = pane.getSelectionStart();
        int selEnd = pane.getSelectionEnd();
        if (selStart == selEnd) return;

        HttpMessage msg = currentMessage;
        if (msg == null) return;
        int historyId = getHistoryId(msg);
        if (historyId < 0) {
            statusLabel.setText(
                    Constant.messages.getString("crimsonview.status.cannotannotate"));
            return;
        }

        String replacementText = extension.getRedactConfig().getReplacementText();
        int replacementLen = replacementText.length();

        // Convert current selection positions to original document positions
        List<int[]> existing = manualRedactStore.getRedactions(historyId, isRequest);
        int origStart = toOriginalPosition(Math.min(selStart, selEnd), existing, replacementLen);
        int origEnd = toOriginalPosition(Math.max(selStart, selEnd), existing, replacementLen);
        int originalLength = origEnd - origStart;
        if (originalLength <= 0) return;

        // Apply to the live document
        int start = Math.min(selStart, selEnd);
        int length = Math.abs(selEnd - selStart);
        try {
            StyledDocument doc = pane.getStyledDocument();
            doc.remove(start, length);
            doc.insertString(start, replacementText, renderer.getAttrRedacted());
        } catch (Exception e) {
            LOGGER.debug("Failed to apply manual redaction", e);
            return;
        }

        manualRedactStore.addRedaction(historyId, isRequest, origStart, originalLength);
        pane.setCaretPosition(start);
        statusLabel.setText(
                Constant.messages.getString("crimsonview.status.redacted"));
    }

    /**
     * Converts a position in the modified document (after manual redactions) back to the
     * original document position, by undoing the cumulative shift from all prior redactions.
     */
    private static int toOriginalPosition(
            int modPos, List<int[]> existingRedactions, int replacementLen) {
        if (existingRedactions.isEmpty()) return modPos;

        List<int[]> sorted = new ArrayList<>(existingRedactions);
        sorted.sort((a, b) -> Integer.compare(a[0], b[0]));

        int cumulativeShift = 0;
        for (int[] r : sorted) {
            int origStart = r[0];
            int origLen = r[1];
            int modRedactionStart = origStart + cumulativeShift;
            int modRedactionEnd = modRedactionStart + replacementLen;

            if (modPos >= modRedactionEnd) {
                cumulativeShift += (replacementLen - origLen);
            } else if (modPos >= modRedactionStart) {
                return origStart + origLen;
            } else {
                return modPos - cumulativeShift;
            }
        }
        return modPos - cumulativeShift;
    }

    /**
     * Extracts the history ID from an HTTP message.
     *
     * @param msg the HTTP message
     * @return the history ID, or -1 if unavailable
     */
    private static int getHistoryId(HttpMessage msg) {
        try {
            if (msg.getHistoryRef() != null) {
                return msg.getHistoryRef().getHistoryId();
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to get history ID", e);
        }
        return -1;
    }

    /**
     * Updates a button's icon to display a solid colour swatch with a border.
     *
     * @param button the button to update
     * @param color the colour to display in the swatch
     */
    private static void updateColorButtonIcon(JButton button, Color color) {
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, 16, 16);
        g.setColor(Color.DARK_GRAY);
        g.drawRect(0, 0, 15, 15);
        g.dispose();
        button.setIcon(new ImageIcon(img));
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
                new JMenuItem(Constant.messages.getString("crimsonview.button.copy"));
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
                                                "crimsonview.status.copiedall"));
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
                        Constant.messages.getString("crimsonview.button.copymarkdown"));
        markdownItem.setToolTipText(
                Constant.messages.getString("crimsonview.button.copymarkdown.tooltip"));
        markdownItem.addActionListener(e -> copyAsMarkdown(isRequestPane));
        popup.add(markdownItem);

        JMenuItem curlItem = null;
        JMenuItem resendItem = null;
        if (isRequestPane) {
            popup.addSeparator();
            curlItem =
                    new JMenuItem(Constant.messages.getString("crimsonview.button.copycurl"));
            curlItem.setToolTipText(
                    Constant.messages.getString("crimsonview.button.copycurl.tooltip"));
            curlItem.addActionListener(
                    e -> {
                        String curl = buildCurlCommand();
                        if (curl != null) {
                            Toolkit.getDefaultToolkit()
                                    .getSystemClipboard()
                                    .setContents(new StringSelection(curl), null);
                            statusLabel.setText(
                                    Constant.messages.getString(
                                            "crimsonview.status.curlcopied"));
                        }
                    });
            popup.add(curlItem);

            resendItem =
                    new JMenuItem(Constant.messages.getString("crimsonview.button.resend"));
            resendItem.setToolTipText(
                    Constant.messages.getString("crimsonview.button.resend.tooltip"));
            resendItem.addActionListener(e -> openInRequestEditor());
            popup.add(resendItem);
        }

        popup.addSeparator();

        String screenshotSingleKey =
                isRequestPane
                        ? "crimsonview.button.copyscreenshot.request"
                        : "crimsonview.button.copyscreenshot.response";
        JMenuItem screenshotSingleItem =
                new JMenuItem(Constant.messages.getString(screenshotSingleKey));
        screenshotSingleItem.addActionListener(
                e -> copySinglePaneScreenshotToClipboard(isRequestPane));
        popup.add(screenshotSingleItem);

        JMenuItem screenshotBothItem =
                new JMenuItem(
                        Constant.messages.getString("crimsonview.button.copyscreenshot.both"));
        screenshotBothItem.addActionListener(e -> copyBothPanesScreenshotToClipboard());
        popup.add(screenshotBothItem);

        JMenuItem copyCurrentViewItem =
                new JMenuItem(Constant.messages.getString("crimsonview.button.copycurrentview"));
        copyCurrentViewItem.setToolTipText(
                Constant.messages.getString("crimsonview.button.copycurrentview.tooltip"));
        copyCurrentViewItem.addActionListener(e -> copyCurrentViewToClipboard());
        popup.add(copyCurrentViewItem);

        final JMenuItem finalCurlItem = curlItem;
        final JMenuItem finalResendItem = resendItem;
        final JMenuItem finalMarkdownItem = markdownItem;
        final JMenuItem finalScreenshotSingleItem = screenshotSingleItem;
        final JMenuItem finalScreenshotBothItem = screenshotBothItem;
        final JMenuItem finalCopyCurrentViewItem = copyCurrentViewItem;

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
                            finalCopyCurrentViewItem.setEnabled(hasMessage);
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
                        Constant.messages.getString("crimsonview.status.resendfailed"));
                return;
            }
            ExtensionLoader loader = control.getExtensionLoader();
            if (loader == null) {
                statusLabel.setText(
                        Constant.messages.getString("crimsonview.status.resendfailed"));
                return;
            }
            Object extRequester = loader.getExtension("ExtensionRequester");
            if (extRequester == null) {
                statusLabel.setText(
                        Constant.messages.getString("crimsonview.status.norequester"));
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
                    Constant.messages.getString("crimsonview.status.resendopened"));
        } catch (SecurityException e) {
            statusLabel.setText("Permission denied");
        } catch (Exception e) {
            statusLabel.setText(
                    Constant.messages.getString("crimsonview.status.resendfailed"));
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
            String markdown;
            int historyId = getHistoryId(msg);
            boolean hasAnnotations =
                    historyId >= 0
                            && !annotationStore.getAnnotations(historyId, isRequest).isEmpty();

            if (hasAnnotations) {
                JTextPane pane = isRequest ? requestPane : responsePane;
                String title =
                        isRequest
                                ? Constant.messages.getString("crimsonview.panel.request.label")
                                : Constant.messages.getString("crimsonview.panel.response.label");
                markdown = buildAnnotatedMarkdown(pane, title);
            } else {
                markdown = isRequest ? buildRequestMarkdown(msg) : buildResponseMarkdown(msg);
            }

            if (markdown != null && !markdown.isEmpty()) {
                Toolkit.getDefaultToolkit()
                        .getSystemClipboard()
                        .setContents(new StringSelection(markdown), null);
                statusLabel.setText(
                        Constant.messages.getString("crimsonview.status.markdowncopied"));
            }
        } catch (Exception e) {
            statusLabel.setText(
                    Constant.messages.getString("crimsonview.status.markdownfailed"));
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

            sb.append((method != null) ? method : "GET")
                    .append(" ")
                    .append((uri != null) ? uri : "/")
                    .append(" ")
                    .append((version != null) ? version : "HTTP/1.1")
                    .append("\n");

            RedactConfig config = extension.getRedactConfig();
            List<HttpHeaderField> headers = header.getHeaders();
            if (headers != null) {
                for (HttpHeaderField field : headers) {
                    String name = field.getName();
                    String value = field.getValue();
                    if (name != null && value != null) {
                        sb.append(name)
                                .append(": ")
                                .append(redactHeaderValue(name, value))
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
                sb.append(bodyStr).append("\n```\n");
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

            sb.append((version != null) ? version : "HTTP/1.1")
                    .append(" ")
                    .append(statusCode)
                    .append(" ")
                    .append((reason != null) ? reason : "")
                    .append("\n");

            RedactConfig config = extension.getRedactConfig();
            List<HttpHeaderField> headers = header.getHeaders();
            if (headers != null) {
                for (HttpHeaderField field : headers) {
                    String name = field.getName();
                    String value = field.getValue();
                    if (name != null && value != null) {
                        sb.append(name)
                                .append(": ")
                                .append(redactHeaderValue(name, value))
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
                sb.append(bodyStr).append("\n```\n");
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
     * Builds a Markdown string from the live StyledDocument, including annotation markup.
     * Adjacent character runs of the same annotation type are merged to avoid colliding
     * inline markers. Annotated blocks use pandoc hard line breaks (two trailing spaces)
     * and are split at blank lines to avoid breaking inline markup across paragraphs.
     */
    private String buildAnnotatedMarkdown(JTextPane pane, String sectionTitle) {
        StyledDocument doc = pane.getStyledDocument();
        int docLen = doc.getLength();
        if (docLen == 0) return "";

        // Step 1: Walk character runs and merge adjacent runs of the same annotation type.
        // type: 0 = plain, 1 = underline, 2 = highlight
        List<int[]> segments = new ArrayList<>();
        int pos = 0;
        int currentType = -1;
        int segStart = 0;
        while (pos < docLen) {
            javax.swing.text.Element run = doc.getCharacterElement(pos);
            int runEnd = Math.min(run.getEndOffset(), docLen);
            AttributeSet attrs = run.getAttributes();
            boolean isUnderline = StyleConstants.isUnderline(attrs);
            Color bg = StyleConstants.getBackground(attrs);
            boolean hasHighlight = (bg != null && !bg.equals(COLOR_BG) && !bg.equals(Color.WHITE));
            int type = isUnderline ? 1 : (hasHighlight ? 2 : 0);
            if (type != currentType) {
                if (currentType >= 0) {
                    segments.add(new int[]{segStart, pos, currentType});
                }
                segStart = pos;
                currentType = type;
            }
            pos = runEnd;
        }
        if (currentType >= 0) {
            segments.add(new int[]{segStart, docLen, currentType});
        }

        // Step 2: Output merged segments
        StringBuilder sb = new StringBuilder();
        sb.append("## ").append(sectionTitle).append("\n\n");
        boolean inCodeBlock = false;
        for (int[] seg : segments) {
            String text;
            try {
                text = doc.getText(seg[0], seg[1] - seg[0]);
            } catch (Exception e) {
                continue;
            }
            int type = seg[2];
            if (type == 0) {
                if (!inCodeBlock) {
                    sb.append("\n```\n");
                    inCodeBlock = true;
                }
                sb.append(text);
            } else {
                if (inCodeBlock) {
                    sb.append("\n```\n");
                    inCodeBlock = false;
                }
                // Split at blank lines to avoid paragraph breaks inside inline markup
                String normalised = text.replace("\r\n", "\n").replace("\r", "\n");
                String[] paragraphs = normalised.split("\n\\s*\n");
                for (int i = 0; i < paragraphs.length; i++) {
                    if (i > 0) sb.append("\n\n");
                    String para = paragraphs[i];
                    if (para.isEmpty()) continue;
                    // Pandoc hard line break: two trailing spaces before newline
                    para = para.replace("\n", "  \n");
                    if (type == 1) {
                        sb.append("[").append(para).append("]{.underline}");
                    } else {
                        sb.append("**").append(para).append("**");
                    }
                }
            }
        }
        if (inCodeBlock) {
            sb.append("\n```\n");
        }
        sb.append("\n");
        return sb.toString();
    }

    /**
     * Returns a copy of annotations with colours remapped to the configured screenshot colours
     * for visibility on screenshots.
     */
    private List<TextAnnotation> screenshotAnnotations(List<TextAnnotation> annotations) {
        RedactConfig cfg = extension.getRedactConfig();
        Color pencilColor = new Color(cfg.getPencilScreenshotColor(), true);
        Color highlightColor = new Color(cfg.getHighlightScreenshotColor(), true);
        List<TextAnnotation> result = new ArrayList<>(annotations.size());
        for (TextAnnotation ann : annotations) {
            if (ann.getType() == TextAnnotation.Type.UNDERLINE) {
                result.add(new TextAnnotation(
                        ann.getStart(), ann.getLength(), ann.getType(), pencilColor));
            } else {
                result.add(new TextAnnotation(
                        ann.getStart(), ann.getLength(), ann.getType(), highlightColor));
            }
        }
        return result;
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

    /**
     * Shows a file chooser dialog and saves a screenshot of the current message to the selected file.
     */
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
                Constant.messages.getString("crimsonview.screenshot.dialog.title"));
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
                Constant.messages.getString("crimsonview.status.screenshotrendering"));

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
                                                                "crimsonview.status.screenshotsaved"),
                                                        outputFile.getName())));
                    } catch (Exception e) {
                        LOGGER.error("Failed to save screenshot", e);
                        SwingUtilities.invokeLater(
                                () ->
                                        statusLabel.setText(
                                                Constant.messages.getString(
                                                        "crimsonview.status.screenshotfailed")));
                    }
                });
    }

    /**
     * Validates and normalizes a screenshot file path, ensuring it has a .png extension.
     *
     * @param file the file to validate
     * @param baseDir the base directory for resolving relative paths (unused but kept for interface)
     * @return the canonical file with .png extension
     * @throws SecurityException if the path cannot be canonicalized
     */
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

    /**
     * Renders a screenshot of the request or response pane and copies it to the system clipboard.
     *
     * @param isRequest {@code true} for the request pane, {@code false} for the response pane
     */
    private void copySinglePaneScreenshotToClipboard(boolean isRequest) {
        HttpMessage msg = currentMessage;
        if (msg == null) {
            return;
        }
        statusLabel.setText(
                Constant.messages.getString("crimsonview.status.screenshotrendering"));
        screenshotExecutor.execute(
                () -> {
                    try {
                        BufferedImage image = renderSinglePaneScreenshot(msg, isRequest);
                        copyImageToClipboard(image);
                        SwingUtilities.invokeLater(
                                () ->
                                        statusLabel.setText(
                                                Constant.messages.getString(
                                                        "crimsonview.status.screenshotcopied")));
                    } catch (Exception e) {
                        LOGGER.error("Failed to copy screenshot to clipboard", e);
                        SwingUtilities.invokeLater(
                                () ->
                                        statusLabel.setText(
                                                Constant.messages.getString(
                                                        "crimsonview.status.screenshotfailed")));
                    }
                });
    }

    /**
     * Renders a screenshot of both request and response panes and copies it to the system clipboard.
     */
    private void copyBothPanesScreenshotToClipboard() {
        HttpMessage msg = currentMessage;
        if (msg == null) {
            return;
        }
        statusLabel.setText(
                Constant.messages.getString("crimsonview.status.screenshotrendering"));
        screenshotExecutor.execute(
                () -> {
                    try {
                        BufferedImage image = renderScreenshot(msg);
                        copyImageToClipboard(image);
                        SwingUtilities.invokeLater(
                                () ->
                                        statusLabel.setText(
                                                Constant.messages.getString(
                                                        "crimsonview.status.screenshotcopied")));
                    } catch (Exception e) {
                        LOGGER.error("Failed to copy screenshot to clipboard", e);
                        SwingUtilities.invokeLater(
                                () ->
                                        statusLabel.setText(
                                                Constant.messages.getString(
                                                        "crimsonview.status.screenshotfailed")));
                    }
                });
    }

    /**
     * Captures the currently visible viewport (scroll position) and copies it to the clipboard.
     */
    private void copyCurrentViewToClipboard() {
        HttpMessage msg = currentMessage;
        if (msg == null) {
            return;
        }
        statusLabel.setText(
                Constant.messages.getString("crimsonview.status.screenshotrendering"));
        screenshotExecutor.execute(
                () -> {
                    try {
                        final BufferedImage[] holder = new BufferedImage[1];
                        SwingUtilities.invokeAndWait(() -> holder[0] = captureCurrentView());
                        BufferedImage image = holder[0];
                        if (image != null) {
                            copyImageToClipboard(image);
                            SwingUtilities.invokeLater(
                                    () ->
                                            statusLabel.setText(
                                                    Constant.messages.getString(
                                                            "crimsonview.status.screenshotcopied")));
                        } else {
                            SwingUtilities.invokeLater(
                                    () ->
                                            statusLabel.setText(
                                                    Constant.messages.getString(
                                                            "crimsonview.status.screenshotfailed")));
                        }
                    } catch (Exception e) {
                        LOGGER.error("Failed to capture current view", e);
                        SwingUtilities.invokeLater(
                                () ->
                                        statusLabel.setText(
                                                Constant.messages.getString(
                                                        "crimsonview.status.screenshotfailed")));
                    }
                });
    }

    /**
     * Captures the currently visible scroll viewport of both panes into a single image.
     *
     * @return the captured image, or {@code null} if the capture failed
     */
    private BufferedImage captureCurrentView() {
        HttpMessage msg = currentMessage;
        if (msg == null || !splitPane.isDisplayable()) {
            return null;
        }

        JViewport reqViewport = requestScrollPane.getViewport();
        JViewport respViewport = responseScrollPane.getViewport();

        int reqViewW = reqViewport.getWidth();
        int reqViewH = reqViewport.getHeight();
        int respViewW = respViewport.getWidth();
        int respViewH = respViewport.getHeight();

        if (reqViewW <= 0 || reqViewH <= 0 || respViewW <= 0 || respViewH <= 0) {
            return null;
        }

        Point reqViewPos = reqViewport.getViewPosition();
        Point respViewPos = respViewport.getViewPosition();

        RedactConfig displayConfig = extension.getRedactConfig();
        boolean lightMode = displayConfig.isLightModeScreenshots();
        boolean truncateLines = displayConfig.isScreenshotTruncateLines();

        HttpMessageRenderer screenshotRenderer = buildScreenshotRenderer(displayConfig, lightMode);

        Color bgColor = lightMode ? Color.WHITE : COLOR_BG;
        Color caretColor = lightMode ? Color.BLACK : Color.WHITE;

        // Build off-screen panes with screenshot renderer (supports light mode)
        JTextPane offReq = buildOffscreenPane(bgColor, caretColor);
        JTextPane offResp = buildOffscreenPane(bgColor, caretColor);

        screenshotRenderer.renderRequest(
                offReq.getStyledDocument(),
                msg.getRequestHeader(),
                (HttpBody) msg.getRequestBody());
        screenshotRenderer.renderResponse(
                offResp.getStyledDocument(),
                msg.getResponseHeader(),
                (HttpBody) msg.getResponseBody());

        // Apply annotations with screenshot colours
        int historyId = getHistoryId(msg);
        if (historyId >= 0) {
            List<TextAnnotation> reqAnns = annotationStore.getAnnotations(historyId, true);
            if (!reqAnns.isEmpty()) {
                AnnotationStore.applyToDocument(
                        offReq.getStyledDocument(), screenshotAnnotations(reqAnns));
            }
            List<TextAnnotation> respAnns = annotationStore.getAnnotations(historyId, false);
            if (!respAnns.isEmpty()) {
                AnnotationStore.applyToDocument(
                        offResp.getStyledDocument(), screenshotAnnotations(respAnns));
            }

            // Apply stored manual redactions
            String mrText = displayConfig.getReplacementText();
            List<int[]> reqMR = manualRedactStore.getRedactions(historyId, true);
            if (!reqMR.isEmpty()) {
                ManualRedactStore.applyToDocument(
                        offReq.getStyledDocument(), reqMR,
                        screenshotRenderer.getAttrRedacted(), mrText);
            }
            List<int[]> respMR = manualRedactStore.getRedactions(historyId, false);
            if (!respMR.isEmpty()) {
                ManualRedactStore.applyToDocument(
                        offResp.getStyledDocument(), respMR,
                        screenshotRenderer.getAttrRedacted(), mrText);
            }
        }

        // Size off-screen panes to match viewport widths
        if (!truncateLines) {
            sizePane(offReq, reqViewW);
            sizePane(offResp, respViewW);
        }

        int labelHeight = 24;
        int dividerThickness = 4;
        Color labelColor = lightMode ? COLOR_LABEL_LIGHT : COLOR_LABEL_DARK;
        Color dividerColor = lightMode ? COLOR_DIVIDER_LIGHT : COLOR_DIVIDER_DARK;

        int imageWidth, imageHeight;
        if (horizontal) {
            imageWidth = reqViewW + dividerThickness + respViewW;
            imageHeight = labelHeight + Math.max(reqViewH, respViewH);
        } else {
            imageWidth = Math.max(reqViewW, respViewW);
            imageHeight = labelHeight + reqViewH + dividerThickness + labelHeight + respViewH;
        }

        BufferedImage image =
                new BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = image.createGraphics();
        try {
            g2d.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2d.setRenderingHint(
                    RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            g2d.setColor(bgColor);
            g2d.fillRect(0, 0, imageWidth, imageHeight);

            Font labelFont = g2d.getFont().deriveFont(Font.BOLD, 12.0f);
            String reqLabel =
                    Constant.messages.getString("crimsonview.panel.request.label");
            String respLabel =
                    Constant.messages.getString("crimsonview.panel.response.label");

            if (horizontal) {
                g2d.setColor(labelColor);
                g2d.setFont(labelFont);
                g2d.drawString(reqLabel, 4, 16);

                paintOffscreenViewport(
                        g2d, offReq, reqViewPos, reqViewW, reqViewH, 0, labelHeight, truncateLines);

                g2d.setColor(dividerColor);
                g2d.fillRect(reqViewW, 0, dividerThickness, imageHeight);

                int respX = reqViewW + dividerThickness;
                g2d.setColor(labelColor);
                g2d.setFont(labelFont);
                g2d.drawString(respLabel, respX + 4, 16);

                paintOffscreenViewport(
                        g2d, offResp, respViewPos, respViewW, respViewH, respX, labelHeight, truncateLines);
            } else {
                int y = 0;
                g2d.setColor(labelColor);
                g2d.setFont(labelFont);
                g2d.drawString(reqLabel, 4, y + 16);
                y += labelHeight;

                paintOffscreenViewport(
                        g2d, offReq, reqViewPos, reqViewW, reqViewH, 0, y, truncateLines);
                y += reqViewH;

                g2d.setColor(dividerColor);
                g2d.fillRect(0, y, imageWidth, dividerThickness);
                y += dividerThickness;

                g2d.setColor(labelColor);
                g2d.setFont(labelFont);
                g2d.drawString(respLabel, 4, y + 16);
                y += labelHeight;

                paintOffscreenViewport(
                        g2d, offResp, respViewPos, respViewW, respViewH, 0, y, truncateLines);
            }
        } finally {
            g2d.dispose();
            offReq.removeNotify();
            offResp.removeNotify();
        }
        return image;
    }

    /**
     * Paints the visible portion of an off-screen JTextPane at the given scroll position
     * into the target location on the Graphics2D context.
     */
    private static void paintOffscreenViewport(
            Graphics2D g2d,
            JTextPane offPane,
            Point viewPosition,
            int viewWidth,
            int viewHeight,
            int targetX,
            int targetY,
            boolean truncateLines) {

        offPane.setBounds(0, 0, truncateLines ? NO_WRAP_WIDTH : viewWidth, viewHeight);
        if (!truncateLines) {
            offPane.invalidate();
            sizePane(offPane, viewWidth);
            offPane.doLayout();
        }

        Graphics2D g = (Graphics2D) g2d.create();
        try {
            g.translate(targetX - viewPosition.x, targetY - viewPosition.y);
            g.setClip(viewPosition.x, viewPosition.y, viewWidth, viewHeight);
            offPane.printAll(g);
        } finally {
            g.dispose();
        }
    }

    /**
     * Copies an image to the system clipboard.
     *
     * @param image the image to copy
     */
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

            // Apply stored annotations to off-screen panes (lime green for pencil)
            int historyId = getHistoryId(msg);
            if (historyId >= 0) {
                List<TextAnnotation> reqAnns = annotationStore.getAnnotations(historyId, true);
                if (!reqAnns.isEmpty()) {
                    AnnotationStore.applyToDocument(
                            offReq.getStyledDocument(), screenshotAnnotations(reqAnns));
                }
                List<TextAnnotation> respAnns = annotationStore.getAnnotations(historyId, false);
                if (!respAnns.isEmpty()) {
                    AnnotationStore.applyToDocument(
                            offResp.getStyledDocument(), screenshotAnnotations(respAnns));
                }

                // Apply stored manual redactions
                String mrText = displayConfig.getReplacementText();
                List<int[]> reqMR = manualRedactStore.getRedactions(historyId, true);
                if (!reqMR.isEmpty()) {
                    ManualRedactStore.applyToDocument(
                            offReq.getStyledDocument(), reqMR,
                            screenshotRenderer.getAttrRedacted(), mrText);
                }
                List<int[]> respMR = manualRedactStore.getRedactions(historyId, false);
                if (!respMR.isEmpty()) {
                    ManualRedactStore.applyToDocument(
                            offResp.getStyledDocument(), respMR,
                            screenshotRenderer.getAttrRedacted(), mrText);
                }
            }

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

            LOGGER.debug("Final heights - reqHeight: {}, respHeight: {}", reqHeight, respHeight);
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
                    Constant.messages.getString("crimsonview.panel.request.label");
            String respLabel =
                    Constant.messages.getString("crimsonview.panel.response.label");

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

    /**
     * Renders a screenshot of a single pane (request or response) into a BufferedImage.
     *
     * @param msg the HTTP message to render
     * @param isRequest {@code true} for the request pane, {@code false} for the response pane
     * @return the rendered image
     */
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

        // Apply stored annotations to off-screen pane (lime green for pencil)
        int historyId = getHistoryId(msg);
        if (historyId >= 0) {
            List<TextAnnotation> anns = annotationStore.getAnnotations(historyId, isRequest);
            if (!anns.isEmpty()) {
                AnnotationStore.applyToDocument(doc, screenshotAnnotations(anns));
            }

            // Apply stored manual redactions
            String mrText = displayConfig.getReplacementText();
            List<int[]> redactions = manualRedactStore.getRedactions(historyId, isRequest);
            if (!redactions.isEmpty()) {
                ManualRedactStore.applyToDocument(
                        doc, redactions, screenshotRenderer.getAttrRedacted(), mrText);
            }
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
                            ? Constant.messages.getString("crimsonview.panel.request.label")
                            : Constant.messages.getString("crimsonview.panel.response.label");
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

    /**
     * Builds an off-screen JTextPane with custom text wrapping behavior for screenshot rendering.
     *
     * @param bg the background colour
     * @param caret the caret colour
     * @return the configured text pane
     */
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

    /**
     * Forces a JTextPane to recalculate its layout at a specific width.
     * This is needed to ensure proper text wrapping before painting screenshots.
     *
     * @param pane the text pane to size
     * @param width the target width in pixels
     */
    private static void sizePane(JTextPane pane, int width) {
        LOGGER.debug("sizePane called - width: {}, current size: {}x{}, preferred: {}x{}",
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

        LOGGER.debug("After sizePane - size: {}x{}, preferred: {}x{}",
                pane.getWidth(), pane.getHeight(),
                pane.getPreferredSize().width, pane.getPreferredSize().height);
    }

    // -------------------------------------------------------------------------
    // Screenshot directory preference
    // -------------------------------------------------------------------------

    /**
     * Persists and loads the last-used screenshot directory to XML storage.
     */
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
