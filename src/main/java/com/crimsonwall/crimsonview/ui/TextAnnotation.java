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

import java.awt.Color;
import java.util.Objects;

/** Immutable record of a user-applied text annotation (underline or highlight). */
public final class TextAnnotation {

    /** Annotation type. */
    public enum Type {
        UNDERLINE,
        HIGHLIGHT
    }

    private final int start;
    private final int length;
    private final Type type;
    private final Color color;

    public TextAnnotation(int start, int length, Type type, Color color) {
        this.start = start;
        this.length = length;
        this.type = Objects.requireNonNull(type);
        this.color = Objects.requireNonNull(color);
    }

    public int getStart() {
        return start;
    }

    public int getLength() {
        return length;
    }

    public Type getType() {
        return type;
    }

    public Color getColor() {
        return color;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TextAnnotation)) return false;
        TextAnnotation that = (TextAnnotation) o;
        return start == that.start
                && length == that.length
                && type == that.type
                && Objects.equals(color, that.color);
    }

    @Override
    public int hashCode() {
        return Objects.hash(start, length, type, color);
    }
}
