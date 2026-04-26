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
package com.crimsonwall.crimsonhttp.redact;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.parosproxy.paros.Constant;
import org.zaproxy.zap.utils.ZapXmlConfiguration;

/**
 * Manages the redaction configuration for Crimson HTTP.
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

    private static final String DEFAULT_REPLACEMENT = "[redacted]";

    private final File configFile =
            new File(new File(Constant.getZapHome(), CONFIG_DIR), CONFIG_FILE);

    private final RedactStorage storage = new RedactStorage();

    private volatile boolean enabled;
    private volatile String replacementText;
    private volatile boolean redactScreenshots;
    private volatile boolean lightModeScreenshots;
    private volatile boolean optimizeScreenshotSpace;
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
