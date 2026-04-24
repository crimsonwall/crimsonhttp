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

import java.io.File;
import org.apache.commons.configuration.ConfigurationException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.parosproxy.paros.Constant;
import org.zaproxy.zap.utils.ZapXmlConfiguration;

/**
 * Persists the split-pane layout preferences (orientation and divider position) to
 * {@code ~/.zap/crimsonhttp/layout.xml} so they survive ZAP restarts.
 */
public final class LayoutPrefs {

    private static final Logger LOGGER = LogManager.getLogger(LayoutPrefs.class);

    private static final String CONFIG_DIR = "crimsonhttp";
    private static final String CONFIG_FILE = "layout.xml";
    private static final String KEY_HORIZONTAL = "layout.horizontal";
    private static final String KEY_DIVIDER_RATIO = "layout.dividerRatio";

    /** Sentinel value indicating no saved divider ratio. */
    private static final double RATIO_UNSET = -1.0;

    private static final File configFile =
            new File(new File(Constant.getZapHome(), CONFIG_DIR), CONFIG_FILE);

    private LayoutPrefs() {}

    /**
     * Loads the saved orientation flag.
     *
     * @return {@code true} for horizontal layout (side-by-side), {@code false} for vertical;
     *     defaults to {@code true} if not saved
     */
    public static boolean loadHorizontal() {
        if (!configFile.exists()) {
            return true;
        }
        try {
            ZapXmlConfiguration config = new ZapXmlConfiguration(configFile);
            return config.getBoolean(KEY_HORIZONTAL, true);
        } catch (ConfigurationException e) {
            LOGGER.warn("Failed to load layout prefs from {}", configFile.getAbsolutePath(), e);
            return true;
        }
    }

    /**
     * Loads the saved divider position ratio.
     *
     * @return ratio in [0,1], or {@link #RATIO_UNSET} if not saved
     */
    public static double loadDividerRatio() {
        if (!configFile.exists()) {
            return RATIO_UNSET;
        }
        try {
            ZapXmlConfiguration config = new ZapXmlConfiguration(configFile);
            return config.getDouble(KEY_DIVIDER_RATIO, RATIO_UNSET);
        } catch (ConfigurationException e) {
            LOGGER.warn("Failed to load layout prefs from {}", configFile.getAbsolutePath(), e);
            return RATIO_UNSET;
        }
    }

    /**
     * Saves the orientation flag to disk.
     *
     * @param horizontal {@code true} for side-by-side, {@code false} for top/bottom
     */
    public static void saveHorizontal(boolean horizontal) {
        try {
            configFile.getParentFile().mkdirs();
            ZapXmlConfiguration config =
                    configFile.exists()
                            ? new ZapXmlConfiguration(configFile)
                            : new ZapXmlConfiguration();
            config.setProperty(KEY_HORIZONTAL, Boolean.valueOf(horizontal));
            config.save(configFile);
        } catch (ConfigurationException e) {
            LOGGER.error("Failed to save layout prefs to {}", configFile.getAbsolutePath(), e);
        }
    }

    /**
     * Saves the divider position ratio to disk.
     *
     * @param ratio the divider position as a fraction of the total panel dimension
     */
    public static void saveDividerRatio(double ratio) {
        try {
            configFile.getParentFile().mkdirs();
            ZapXmlConfiguration config =
                    configFile.exists()
                            ? new ZapXmlConfiguration(configFile)
                            : new ZapXmlConfiguration();
            config.setProperty(KEY_DIVIDER_RATIO, Double.valueOf(ratio));
            config.save(configFile);
        } catch (ConfigurationException e) {
            LOGGER.error("Failed to save layout prefs to {}", configFile.getAbsolutePath(), e);
        }
    }
}
