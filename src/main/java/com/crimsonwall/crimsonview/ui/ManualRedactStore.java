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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyledDocument;

/**
 * In-memory store for user-initiated manual redactions, keyed by history ID and pane.
 *
 * <p>Each redaction records the original document position and length so that it can be
 * re-applied after a fresh render. Redactions are applied from end-to-start to preserve
 * earlier positions.
 */
public final class ManualRedactStore {

    private static final int MAX_STORED_MESSAGES = 500;

    private static final class PaneKey {
        final int historyId;
        final boolean isRequest;

        PaneKey(int historyId, boolean isRequest) {
            this.historyId = historyId;
            this.isRequest = isRequest;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PaneKey)) return false;
            PaneKey that = (PaneKey) o;
            return historyId == that.historyId && isRequest == that.isRequest;
        }

        @Override
        public int hashCode() {
            return Objects.hash(historyId, isRequest);
        }
    }

    private final Map<PaneKey, List<int[]>> store = new HashMap<>();
    private final List<PaneKey> insertionOrder = new ArrayList<>();

    public ManualRedactStore() {}

    public List<int[]> getRedactions(int historyId, boolean isRequest) {
        List<int[]> list = store.get(new PaneKey(historyId, isRequest));
        return (list != null) ? Collections.unmodifiableList(list) : Collections.emptyList();
    }

    public void addRedaction(int historyId, boolean isRequest, int start, int originalLength) {
        PaneKey key = new PaneKey(historyId, isRequest);
        List<int[]> list = store.computeIfAbsent(key, k -> new ArrayList<>());
        list.add(new int[]{start, originalLength});
        insertionOrder.add(key);
        evictIfNeeded();
    }

    public void clearRedactions(int historyId, boolean isRequest) {
        PaneKey key = new PaneKey(historyId, isRequest);
        store.remove(key);
        insertionOrder.remove(key);
    }

    public void clearAll() {
        store.clear();
        insertionOrder.clear();
    }

    /**
     * Applies stored manual redactions to a {@link StyledDocument}, replacing each recorded span
     * with the given replacement text in the redacted style. Redactions are applied from
     * end-to-start so that earlier positions remain valid.
     */
    public static void applyToDocument(
            StyledDocument doc,
            List<int[]> redactions,
            SimpleAttributeSet redactedAttr,
            String replacementText) {
        if (doc == null || redactions == null || redactions.isEmpty()) {
            return;
        }
        List<int[]> sorted = new ArrayList<>(redactions);
        sorted.sort((a, b) -> Integer.compare(b[0], a[0]));
        for (int[] r : sorted) {
            int start = r[0];
            int len = r[1];
            try {
                if (start >= 0 && start + len <= doc.getLength()) {
                    doc.remove(start, len);
                    doc.insertString(start, replacementText, redactedAttr);
                }
            } catch (Exception ignored) {
            }
        }
    }

    private void evictIfNeeded() {
        while (store.size() > MAX_STORED_MESSAGES && !insertionOrder.isEmpty()) {
            PaneKey oldest = insertionOrder.remove(0);
            store.remove(oldest);
        }
    }
}
