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

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * A single redaction rule consisting of a display name, a regex pattern, and an enabled flag.
 *
 * <p>The compiled {@link Pattern} is cached lazily and invalidated whenever the pattern string
 * changes. Returns {@code null} from {@link #getCompiledPattern()} if the pattern is empty or
 * invalid.
 */
public class RedactEntry {

    private static final Logger LOGGER = LogManager.getLogger(RedactEntry.class);

    /** Maximum allowed regex pattern length to mitigate ReDoS risk. */
    private static final int MAX_PATTERN_LENGTH = 200;

    /** Timeout in milliseconds for regex matching to prevent ReDoS attacks. */
    private static final long MATCH_TIMEOUT_MS = 100;

    private String name;
    private String pattern;
    private boolean enabled;

    /** Cached compiled pattern; {@code volatile} so reads are always fresh across threads. */
    private volatile transient Pattern compiledPattern;

    /** Single-thread executor for timeout-based regex matching. */
    private static final ExecutorService MATCH_EXECUTOR =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "redact-match");
                t.setDaemon(true);
                return t;
            });

    /**
     * Creates a default enabled entry with empty name and pattern.
     */
    public RedactEntry() {
        this("", "", true);
    }

    /**
     * Creates a new redaction rule.
     *
     * @param name    human-readable display name for this rule
     * @param pattern Java regex pattern string; compiled lazily on first access
     * @param enabled whether this rule is active
     */
    public RedactEntry(String name, String pattern, boolean enabled) {
        this.name = name;
        this.pattern = pattern;
        this.enabled = enabled;
    }

    /** @return the display name of this rule */
    public String getName() {
        return name;
    }

    /** @param name the new display name */
    public void setName(String name) {
        this.name = name;
    }

    /** @return the raw regex pattern string */
    public String getPattern() {
        return pattern;
    }

    /**
     * @param pattern the new regex pattern string; invalidates any cached compiled pattern.
     *                Patterns exceeding {@link #MAX_PATTERN_LENGTH} are silently ignored by
     *                {@link #getCompiledPattern()}.
     */
    public void setPattern(String pattern) {
        this.pattern = pattern;
        this.compiledPattern = null;
    }

    /** @return {@code true} if this rule is active */
    public boolean isEnabled() {
        return enabled;
    }

    /** @param enabled whether this rule should be active */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Returns the compiled {@link Pattern} for this rule, compiling it lazily on first access.
     *
     * @return the compiled pattern, or {@code null} if the pattern is empty or syntactically
     *     invalid
     */
    public Pattern getCompiledPattern() {
        if (compiledPattern != null) {
            return compiledPattern;
        }
        if (pattern == null || pattern.isEmpty() || pattern.length() > MAX_PATTERN_LENGTH) {
            return null;
        }
        try {
            compiledPattern = Pattern.compile(pattern);
            return compiledPattern;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Tests if this pattern matches the given input with timeout protection.
     *
     * @param input the string to test
     * @return {@code true} if a match is found, {@code false} if no match or timeout
     */
    public boolean matchesWithTimeout(String input) {
        Pattern p = getCompiledPattern();
        if (p == null || input == null) {
            return false;
        }
        Future<Boolean> future = null;
        try {
            future = MATCH_EXECUTOR.submit(() -> p.matcher(input).find());
            return future.get(MATCH_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            if (future != null) {
                future.cancel(true);
            }
            LOGGER.warn("Regex timed out after {}ms for pattern: {}", MATCH_TIMEOUT_MS, pattern);
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}
