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
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

/**
 * In-memory store for user annotations (underlines and highlights), keyed by history ID and pane.
 *
 * <p>Annotations are not persisted to disk. The store is bounded to prevent unbounded memory growth.
 */
public final class AnnotationStore {

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

    private final Map<PaneKey, List<TextAnnotation>> store = new HashMap<>();
    private final List<PaneKey> insertionOrder = new ArrayList<>();

    public AnnotationStore() {}

    public List<TextAnnotation> getAnnotations(int historyId, boolean isRequest) {
        List<TextAnnotation> list = store.get(new PaneKey(historyId, isRequest));
        return (list != null) ? Collections.unmodifiableList(list) : Collections.emptyList();
    }

    public void addAnnotation(int historyId, boolean isRequest, TextAnnotation annotation) {
        PaneKey key = new PaneKey(historyId, isRequest);
        List<TextAnnotation> list = store.computeIfAbsent(key, k -> new ArrayList<>());
        list.add(annotation);
        insertionOrder.add(key);
        evictIfNeeded();
    }

    public void clearAnnotations(int historyId, boolean isRequest) {
        PaneKey key = new PaneKey(historyId, isRequest);
        store.remove(key);
        insertionOrder.remove(key);
    }

    public void clearAll() {
        store.clear();
        insertionOrder.clear();
    }

    /**
     * Applies a list of annotations to a {@link StyledDocument}, merging with existing character
     * attributes (e.g. syntax highlighting) rather than replacing them.
     */
    public static void applyToDocument(StyledDocument doc, List<TextAnnotation> annotations) {
        if (doc == null || annotations == null || annotations.isEmpty()) {
            return;
        }
        for (TextAnnotation ann : annotations) {
            if (ann.getStart() < 0 || ann.getLength() <= 0) {
                continue;
            }
            SimpleAttributeSet attr = new SimpleAttributeSet();
            if (ann.getType() == TextAnnotation.Type.UNDERLINE) {
                StyleConstants.setUnderline(attr, true);
                StyleConstants.setForeground(attr, ann.getColor());
            } else {
                StyleConstants.setBackground(attr, ann.getColor());
            }
            try {
                doc.setCharacterAttributes(ann.getStart(), ann.getLength(), attr, false);
            } catch (Exception ignored) {
                // Skip out-of-range annotations (e.g. truncated bodies)
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
