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
import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.parosproxy.paros.Constant;
import org.zaproxy.zap.utils.ZapXmlConfiguration;

/**
 * Persists and loads {@link RedactEntry} rules to/from an XML file under the ZAP home directory.
 *
 * <p>Rules are stored as attributes on {@code <rule>} elements within the root element. A set of
 * sensible default rules covering common credential patterns is provided via {@link
 * #createDefaults()}.
 */
public class RedactStorage {

    private static final Logger LOGGER = LogManager.getLogger(RedactStorage.class);

    private static final String CONFIG_DIR = "crimsonhttp";
    private static final String CONFIG_FILE = "redact-rules.xml";

    private final File configFile;

    /**
     * Constructs storage pointing to {@code <ZAP_HOME>/crimsonhttp/redact-rules.xml}.
     */
    public RedactStorage() {
        File dir = new File(Constant.getZapHome(), CONFIG_DIR);
        this.configFile = new File(dir, CONFIG_FILE);
    }

    /**
     * Loads all stored redaction rules from disk.
     *
     * @return list of loaded {@link RedactEntry} objects, or an empty list if the file does not
     *     exist or cannot be read
     */
    public List<RedactEntry> load() {
        List<RedactEntry> entries = new ArrayList<>();
        if (!configFile.exists()) {
            return entries;
        }
        try {
            ZapXmlConfiguration config = new ZapXmlConfiguration(configFile);
            List<HierarchicalConfiguration> rules = config.configurationsAt("rule");
            for (HierarchicalConfiguration rule : rules) {
                String name = rule.getString("[@name]", "");
                String pattern = rule.getString("[@pattern]", "");
                boolean enabled = rule.getBoolean("[@enabled]", true);
                entries.add(new RedactEntry(name, pattern, enabled));
            }
        } catch (ConfigurationException e) {
            LOGGER.warn("Failed to load redact rules from {}", configFile.getAbsolutePath(), e);
        }
        return entries;
    }

    /**
     * Saves the given list of redaction rules to disk, overwriting any existing file.
     *
     * @param entries the rules to persist
     */
    public void save(List<RedactEntry> entries) {
        try {
            configFile.getParentFile().mkdirs();
            ZapXmlConfiguration config = new ZapXmlConfiguration();
            config.setRootElementName("redact-rules");
            for (int i = 0; i < entries.size(); i++) {
                RedactEntry entry = entries.get(i);
                String key = "rule(" + i + ")";
                config.setProperty(key + "[@name]", entry.getName());
                config.setProperty(key + "[@pattern]", entry.getPattern());
                config.setProperty(key + "[@enabled]", Boolean.valueOf(entry.isEnabled()));
            }
            config.save(configFile);
        } catch (ConfigurationException e) {
            LOGGER.error("Failed to save redact rules to {}", configFile.getAbsolutePath(), e);
        }
    }

    /**
     * Creates a default set of redaction rules covering common credential and token patterns.
     *
     * @return list of default {@link RedactEntry} rules
     */
    public static List<RedactEntry> createDefaults() {
        List<RedactEntry> defaults = new ArrayList<>();
        defaults.add(
                new RedactEntry(
                        "Email Address",
                        "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}",
                        false));
        defaults.add(
                new RedactEntry(
                        "Authorization Header",
                        "(?i)(Authorization\\s*:\\s*)(Basic|Bearer|Digest|Negotiate|NTLM)\\s+\\S+",
                        true));
        defaults.add(
                new RedactEntry(
                        "Cookie Header", "(?i)(Cookie\\s*:\\s*)[^\\r\\n]+", true));
        defaults.add(
                new RedactEntry(
                        "Set-Cookie Header",
                        "(?i)(Set-Cookie\\s*:\\s*\\S+?\\s*=\\s*)[^\\r\\n;]+",
                        true));
        defaults.add(
                new RedactEntry(
                        "WWW-Authenticate Header",
                        "(?i)(WWW-Authenticate\\s*:\\s*\\S+\\s+realm=\\S+,?\\s*(?:,?\\s*[a-z][-a-z]*=\\S+)*)",
                        true));
        defaults.add(
                new RedactEntry(
                        "Proxy-Authorization Header",
                        "(?i)(Proxy-Authorization\\s*:\\s*)(Basic|Bearer|Digest|Negotiate|NTLM)\\s+\\S+",
                        true));
        defaults.add(
                new RedactEntry(
                        "X-API-Key Header", "(?i)(X-Api-Key\\s*:\\s*)\\S+", true));
        defaults.add(
                new RedactEntry(
                        "Bearer Token",
                        "(?i)Bearer\\s+[A-Za-z0-9\\-._~+/]+=*",
                        true));
        defaults.add(
                new RedactEntry(
                        "Basic Auth Credentials",
                        "(?i)Basic\\s+[A-Za-z0-9+/]+=*",
                        true));
        defaults.add(
                new RedactEntry(
                        "JWT Token",
                        "ey[A-Za-z0-9]{17,}\\.ey[A-Za-z0-9/\\\\_-]{17,}\\.[A-Za-z0-9/\\\\_-]{10,}",
                        true));
        defaults.add(
                new RedactEntry(
                        "AWS Access Key",
                        "(?:A3T[A-Z0-9]|AKIA|ASIA|ABIA|ACCA)[A-Z2-7]{16}",
                        true));
        defaults.add(
                new RedactEntry(
                        "AWS Secret Key",
                        "(?i)aws_secret_access_key\\s*[=:]\\s*[A-Za-z0-9/+=]{40}",
                        true));
        defaults.add(new RedactEntry("GCP API Key", "AIza[\\w-]{35}", true));
        defaults.add(new RedactEntry("GitHub PAT", "ghp_[0-9a-zA-Z]{36}", true));
        defaults.add(new RedactEntry("GitLab PAT", "glpat-[0-9a-zA-Z\\-]{20}", true));
        defaults.add(new RedactEntry("Slack Token", "xox[bpers]-[0-9a-zA-Z-]+", true));
        defaults.add(
                new RedactEntry(
                        "Stripe Secret Key",
                        "(?:sk|rk)_(?:test|live)_[a-zA-Z0-9]{10,99}",
                        true));
        defaults.add(
                new RedactEntry(
                        "Private Key Header",
                        "-----BEGIN[ A-Z0-9_-]{0,100}PRIVATE KEY[^-]*-----[\\s\\S]*?-----END[^-]*-----",
                        true));
        defaults.add(
                new RedactEntry(
                        "Generic Secret Assignment",
                        "(?i)(?:password|secret|apikey|api_key|token|auth)\\s*[=:]\\s*['\"]?[A-Za-z0-9_\\-]{16,}['\"]?",
                        true));
        return defaults;
    }
}
