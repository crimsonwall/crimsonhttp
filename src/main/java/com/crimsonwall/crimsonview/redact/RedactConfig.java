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
package com.crimsonwall.crimsonview.redact;

import java.awt.Color;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.parosproxy.paros.Constant;
import org.zaproxy.zap.utils.ZapXmlConfiguration;

/**
 * Manages the redaction configuration for CrimsonView.
 *
 * <p>Configuration is persisted to {@code ~/.zap/crimsonhttp/redact-config.xml}. The list of
 * {@link RedactEntry} rules is stored separately via {@link RedactStorage}. An in-memory cache of
 * active (enabled, valid-pattern) entries is maintained and invalidated whenever the list changes.
 */
public class RedactConfig {

    private static final Logger LOGGER = LogManager.getLogger(RedactConfig.class);

    private static final String CONFIG_DIR = "crimsonhttp";
    private static final String CONFIG_FILE = "redact-config.xml";

    private static final String KEY_ENABLED = "autoRedact.enabled";
    private static final String KEY_REPLACEMENT = "autoRedact.replacementText";
    private static final String KEY_SCREENSHOT = "autoRedact.redactScreenshots";
    private static final String KEY_LIGHT_MODE = "autoRedact.lightModeScreenshots";
    private static final String KEY_OPTIMIZE_SPACE = "autoRedact.optimizeScreenshotSpace";
    private static final String KEY_SCREENSHOT_MAX_WIDTH = "autoRedact.screenshotMaxWidth";
    private static final String KEY_SCREENSHOT_TRUNCATE = "autoRedact.screenshotTruncateLines";
    private static final String KEY_PENCIL_SCREENSHOT_COLOR = "autoRedact.pencilScreenshotColor";
    private static final String KEY_HIGHLIGHT_SCREENSHOT_COLOR = "autoRedact.highlightScreenshotColor";
    private static final String KEY_ANNOTATION_COLOR = "autoRedact.annotationColor";

    private static final String DEFAULT_REPLACEMENT = "[redacted]";
    private static final int DEFAULT_PENCIL_COLOR = 0x00FF00; // lime green
    private static final int DEFAULT_HIGHLIGHT_COLOR = 0xFFFF00; // yellow
    private static final int DEFAULT_ANNOTATION_COLOR = 0xFFFF00; // yellow
    public static final int DEFAULT_SCREENSHOT_MAX_WIDTH = 1000;
    public static final int MIN_SCREENSHOT_MAX_WIDTH = 200;
    public static final int MAX_SCREENSHOT_MAX_WIDTH = 4096;

    private final File configFile =
            new File(new File(Constant.getZapHome(), CONFIG_DIR), CONFIG_FILE);

    private final RedactStorage storage = new RedactStorage();

    private volatile boolean enabled;
    private volatile String replacementText;
    private volatile boolean redactScreenshots;
    private volatile boolean lightModeScreenshots;
    private volatile boolean optimizeScreenshotSpace;
    private volatile int screenshotMaxWidth;
    private volatile boolean screenshotTruncateLines;
    private volatile int pencilScreenshotColor;
    private volatile int highlightScreenshotColor;
    private volatile int annotationColor;
    private volatile List<RedactEntry> entries;
    private volatile List<RedactEntry> cachedActiveEntries;

    /**
     * Constructs a new config with redaction disabled and the default replacement text.
     */
    public RedactConfig() {
        this.enabled = false;
        this.replacementText = DEFAULT_REPLACEMENT;
        this.redactScreenshots = false;
        this.lightModeScreenshots = true;
        this.optimizeScreenshotSpace = false;
        this.screenshotMaxWidth = DEFAULT_SCREENSHOT_MAX_WIDTH;
        this.screenshotTruncateLines = false;
        this.pencilScreenshotColor = DEFAULT_PENCIL_COLOR;
        this.highlightScreenshotColor = DEFAULT_HIGHLIGHT_COLOR;
        this.annotationColor = DEFAULT_ANNOTATION_COLOR;
        this.entries = new ArrayList<>();
    }

    /**
     * Loads configuration and rules from disk. If no rules file exists, default rules are created
     * and saved.
     */
    public void load() {
        loadEntries();
        if (!configFile.exists()) {
            return;
        }
        try {
            ZapXmlConfiguration config = new ZapXmlConfiguration(configFile);
            this.enabled = config.getBoolean(KEY_ENABLED, false);
            this.replacementText = config.getString(KEY_REPLACEMENT, DEFAULT_REPLACEMENT);
            this.redactScreenshots = config.getBoolean(KEY_SCREENSHOT, false);
            this.lightModeScreenshots = config.getBoolean(KEY_LIGHT_MODE, true);
            this.optimizeScreenshotSpace = config.getBoolean(KEY_OPTIMIZE_SPACE, false);
            this.screenshotMaxWidth =
                    Math.max(
                            MIN_SCREENSHOT_MAX_WIDTH,
                            Math.min(
                                    config.getInt(KEY_SCREENSHOT_MAX_WIDTH, DEFAULT_SCREENSHOT_MAX_WIDTH),
                                    MAX_SCREENSHOT_MAX_WIDTH));
            this.screenshotTruncateLines = config.getBoolean(KEY_SCREENSHOT_TRUNCATE, false);
            this.pencilScreenshotColor = config.getInt(KEY_PENCIL_SCREENSHOT_COLOR, DEFAULT_PENCIL_COLOR);
            this.highlightScreenshotColor = config.getInt(KEY_HIGHLIGHT_SCREENSHOT_COLOR, DEFAULT_HIGHLIGHT_COLOR);
            this.annotationColor = config.getInt(KEY_ANNOTATION_COLOR, DEFAULT_ANNOTATION_COLOR);
        } catch (ConfigurationException e) {
            LOGGER.warn("Failed to load redact config from {}", configFile.getAbsolutePath(), e);
        }
    }

    private void loadEntries() {
        this.entries = storage.load();
        if (this.entries.isEmpty()) {
            this.entries = RedactStorage.createDefaults();
            storage.save(this.entries);
        }
        this.cachedActiveEntries = null;
    }

    /** Saves general configuration options (enabled flag, replacement text, screenshot flags). */
    public void save() {
        try {
            configFile.getParentFile().mkdirs();
            ZapXmlConfiguration config =
                    configFile.exists()
                            ? new ZapXmlConfiguration(configFile)
                            : new ZapXmlConfiguration();
            config.setProperty(KEY_ENABLED, Boolean.valueOf(enabled));
            config.setProperty(KEY_REPLACEMENT, replacementText);
            config.setProperty(KEY_SCREENSHOT, Boolean.valueOf(redactScreenshots));
            config.setProperty(KEY_LIGHT_MODE, Boolean.valueOf(lightModeScreenshots));
            config.setProperty(KEY_OPTIMIZE_SPACE, Boolean.valueOf(optimizeScreenshotSpace));
            config.setProperty(KEY_SCREENSHOT_MAX_WIDTH, Integer.valueOf(screenshotMaxWidth));
            config.setProperty(KEY_SCREENSHOT_TRUNCATE, Boolean.valueOf(screenshotTruncateLines));
            config.setProperty(KEY_PENCIL_SCREENSHOT_COLOR, Integer.valueOf(pencilScreenshotColor));
            config.setProperty(KEY_HIGHLIGHT_SCREENSHOT_COLOR, Integer.valueOf(highlightScreenshotColor));
            config.setProperty(KEY_ANNOTATION_COLOR, Integer.valueOf(annotationColor));
            config.save(configFile);
        } catch (ConfigurationException e) {
            LOGGER.error("Failed to save redact config to {}", configFile.getAbsolutePath(), e);
        }
    }

    /** Saves the current rule list to disk via {@link RedactStorage}. */
    public void saveEntries() {
        storage.save(entries);
    }

    /** @return {@code true} if automatic redaction is enabled */
    public boolean isEnabled() {
        return enabled;
    }

    /** @param enabled whether to enable automatic redaction */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** @return the text used to replace redacted content */
    public String getReplacementText() {
        return replacementText;
    }

    /** @param replacementText the text to substitute for redacted spans */
    public void setReplacementText(String replacementText) {
        this.replacementText = replacementText;
    }

    /** @return {@code true} if screenshot output should apply redaction */
    public boolean isRedactScreenshots() {
        return redactScreenshots;
    }

    /** @param redactScreenshots whether screenshot output should apply redaction */
    public void setRedactScreenshots(boolean redactScreenshots) {
        this.redactScreenshots = redactScreenshots;
    }

    /** @return {@code true} if screenshots use a light colour scheme */
    public boolean isLightModeScreenshots() {
        return lightModeScreenshots;
    }

    /** @param lightModeScreenshots whether screenshots use a light colour scheme */
    public void setLightModeScreenshots(boolean lightModeScreenshots) {
        this.lightModeScreenshots = lightModeScreenshots;
    }

    /** @return {@code true} if screenshots should minimise wasted whitespace */
    public boolean isOptimizeScreenshotSpace() {
        return optimizeScreenshotSpace;
    }

    /** @param optimizeScreenshotSpace whether screenshots should minimise wasted whitespace */
    public void setOptimizeScreenshotSpace(boolean optimizeScreenshotSpace) {
        this.optimizeScreenshotSpace = optimizeScreenshotSpace;
    }

    /** @return the maximum total width (in pixels) for screenshot images */
    public int getScreenshotMaxWidth() {
        return screenshotMaxWidth;
    }

    /** @param width the maximum total width (in pixels) for screenshot images */
    public void setScreenshotMaxWidth(int width) {
        this.screenshotMaxWidth =
                Math.max(MIN_SCREENSHOT_MAX_WIDTH, Math.min(width, MAX_SCREENSHOT_MAX_WIDTH));
    }

    /** @return {@code true} if screenshot lines that exceed the pane width are truncated */
    public boolean isScreenshotTruncateLines() {
        return screenshotTruncateLines;
    }

    /** @param screenshotTruncateLines whether to truncate lines instead of wrapping them */
    public void setScreenshotTruncateLines(boolean screenshotTruncateLines) {
        this.screenshotTruncateLines = screenshotTruncateLines;
    }

    /** @return the colour used for pencil (underline) annotations in screenshots as RGB int */
    public int getPencilScreenshotColor() {
        return pencilScreenshotColor;
    }

    /** @param rgb the colour for pencil annotations in screenshots */
    public void setPencilScreenshotColor(int rgb) {
        this.pencilScreenshotColor = rgb;
    }

    /** @return the colour used for highlight annotations in screenshots as RGB int */
    public int getHighlightScreenshotColor() {
        return highlightScreenshotColor;
    }

    /** @param rgb the colour for highlight annotations in screenshots */
    public void setHighlightScreenshotColor(int rgb) {
        this.highlightScreenshotColor = rgb;
    }

    /** @return the annotation toolbar colour as RGB int */
    public int getAnnotationColor() {
        return annotationColor;
    }

    /** @param rgb the annotation toolbar colour */
    public void setAnnotationColor(int rgb) {
        this.annotationColor = rgb;
    }

    /**
     * Returns a defensive copy of all rules.
     *
     * @return copy of the entries list
     */
    public List<RedactEntry> getEntries() {
        return new ArrayList<>(entries);
    }

    /**
     * Replaces the rule list and invalidates the active-entries cache.
     *
     * @param entries the new list of rules
     */
    public void setEntries(List<RedactEntry> entries) {
        this.entries = new ArrayList<>(entries);
        this.cachedActiveEntries = null;
    }

    private final Object activeEntriesLock = new Object();

    /**
     * Returns the subset of rules that are enabled and have a valid compiled pattern. The result is
     * cached until {@link #setEntries(List)} is called.
     *
     * @return immutable view of active redaction rules
     */
    public List<RedactEntry> getActiveEntries() {
        List<RedactEntry> cached = cachedActiveEntries;
        if (cached != null) {
            return cached;
        }
        synchronized (activeEntriesLock) {
            cached = cachedActiveEntries;
            if (cached != null) {
                return cached;
            }
            List<RedactEntry> active = new ArrayList<>();
            for (RedactEntry entry : entries) {
                if (entry.isEnabled() && entry.getCompiledPattern() != null) {
                    active.add(entry);
                }
            }
            this.cachedActiveEntries = active;
            return active;
        }
    }
}
