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
package com.crimsonwall.crimsonview;

import com.crimsonwall.crimsonview.redact.RedactConfig;
import com.crimsonwall.crimsonview.ui.CrimsonViewPanel;
import com.crimsonwall.crimsonview.ui.OptionsRedactPanel;
import java.net.URI;
import java.net.URL;
import javax.swing.ImageIcon;
import javax.swing.SwingUtilities;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.parosproxy.paros.extension.AbstractPanel;
import org.parosproxy.paros.extension.ExtensionAdaptor;
import org.parosproxy.paros.extension.ExtensionHook;
import org.parosproxy.paros.network.HttpMessage;
import org.parosproxy.paros.view.AbstractParamPanel;
import org.zaproxy.zap.extension.httppanel.DisplayedMessageChangedListener;
import org.zaproxy.zap.extension.httppanel.Message;
import org.zaproxy.zap.utils.DisplayUtils;

/**
 * Main extension entry point for CrimsonView.
 *
 * <p>Registers the {@link CrimsonViewPanel} as a work panel and hooks into ZAP's
 * request/response display events to render messages as they are selected.
 */
public final class ExtensionCrimsonView extends ExtensionAdaptor {

    private static final Logger LOGGER = LogManager.getLogger(ExtensionCrimsonView.class);

    public static final String NAME = "ExtensionCrimsonView";

    protected static final String PREFIX = "crimsonview";

    private volatile CrimsonViewPanel httpPanel;
    private volatile OptionsRedactPanel optionsPanel;
    private volatile RedactConfig redactConfig;

    private static ImageIcon cachedIcon;

    private final DisplayedMessageChangedListener messageChangedListener =
            new DisplayedMessageChangedListener() {
                @Override
                public void messageChanged(Message oldMessage, Message newMessage) {
                    if (newMessage instanceof HttpMessage) {
                        HttpMessage msg = (HttpMessage) newMessage;
                        SwingUtilities.invokeLater(
                                () -> {
                                    try {
                                        getHttpPanel().displayMessage(msg);
                                    } catch (Exception e) {
                                        LOGGER.warn("Error displaying HTTP message", e);
                                    }
                                });
                    }
                }
            };

    public ExtensionCrimsonView() {
        super(NAME);
        setI18nPrefix(PREFIX);
    }

    @Override
    public void hook(ExtensionHook extensionHook) {
        super.hook(extensionHook);

        getRedactConfig().load();

        if (hasView()) {
            extensionHook.getHookView().addWorkPanel((AbstractPanel) getHttpPanel());
            extensionHook
                    .getHookView()
                    .addRequestPanelDisplayedMessageChangedListener(messageChangedListener);
            extensionHook
                    .getHookView()
                    .addResponsePanelDisplayedMessageChangedListener(messageChangedListener);
            extensionHook.getHookView().addOptionPanel((AbstractParamPanel) getOptionsPanel());
        }

        LOGGER.info("CrimsonView extension hooked successfully");
    }

    @Override
    public boolean canUnload() {
        return true;
    }

    @Override
    public void unload() {
        super.unload();
        if (httpPanel != null) {
            httpPanel.cleanup();
        }
        httpPanel = null;
        optionsPanel = null;
        redactConfig = null;
    }

    @Override
    public String getAuthor() {
        return "Renico Koen / crimsonwall.com";
    }

    @Override
    public String getDescription() {
        return getMessages().getString("crimsonview.desc");
    }

    @Override
    public URL getURL() {
        try {
            return URI.create("https://github.com/crimsonwall/crimsonview").toURL();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Returns the main HTTP display panel, creating it lazily on first access.
     *
     * @return the {@link CrimsonViewPanel} instance
     */
    public synchronized CrimsonViewPanel getHttpPanel() {
        if (httpPanel == null) {
            httpPanel = new CrimsonViewPanel(this);
        }
        return httpPanel;
    }

    /**
     * Returns the options/settings panel, creating it lazily on first access.
     *
     * @return the {@link OptionsRedactPanel} instance
     */
    public synchronized OptionsRedactPanel getOptionsPanel() {
        if (optionsPanel == null) {
            optionsPanel = new OptionsRedactPanel(this);
        }
        return optionsPanel;
    }

    /**
     * Returns the redaction configuration, creating it lazily on first access.
     *
     * @return the {@link RedactConfig} instance
     */
    public synchronized RedactConfig getRedactConfig() {
        if (redactConfig == null) {
            redactConfig = new RedactConfig();
        }
        return redactConfig;
    }

    /**
     * Re-renders the currently displayed message using the latest redaction config.
     * Called after options are saved to reflect changes immediately.
     */
    public void refreshCurrentMessage() {
        CrimsonViewPanel panel = httpPanel;
        if (panel != null) {
            panel.refresh();
        }
    }

    /**
     * Returns the scaled add-on icon, loading it once and caching it.
     *
     * @return the panel icon
     */
    public static synchronized ImageIcon getIcon() {
        if (cachedIcon == null) {
            cachedIcon =
                    new ImageIcon(
                            DisplayUtils.getScaledIcon(
                                            ExtensionCrimsonView.class.getResource(
                                                    "crimsonview-icon.png"))
                                    .getImage());
        }
        return cachedIcon;
    }
}
