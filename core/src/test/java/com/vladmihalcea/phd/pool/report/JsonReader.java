package com.vladmihalcea.phd.pool.report;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal JSON reader for the JMH result files ({@code ResultFormatType.JSON}), so the reports can read the raw
 * per-fork, per-iteration scores, which the tidy CSV of {@code AbstractBenchmarkTest} aggregates away, without a
 * JSON library on the class path. Objects become {@link LinkedHashMap}s, arrays {@link List}s, numbers
 * {@link Double}s (JMH writes {@code NaN} unquoted, which this reader also accepts), and the literals
 * {@link Boolean} or {@code null}.
 *
 * @author Vlad Mihalcea
 */
final class JsonReader {

    private final String text;
    private int position;

    private JsonReader(String text) {
        this.text = text;
    }

    static Object parse(String text) {
        JsonReader reader = new JsonReader(text);
        Object value = reader.value();
        reader.skipWhitespace();
        if (reader.position != text.length()) {
            throw reader.error("trailing content");
        }
        return value;
    }

    private Object value() {
        skipWhitespace();
        if (position >= text.length()) {
            throw error("unexpected end");
        }
        char c = text.charAt(position);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            case 'N' -> literal("NaN", Double.NaN);
            default -> number();
        };
    }

    private Map<String, Object> object() {
        Map<String, Object> map = new LinkedHashMap<>();
        position++;
        skipWhitespace();
        if (peek() == '}') {
            position++;
            return map;
        }
        while (true) {
            skipWhitespace();
            String key = string();
            skipWhitespace();
            expect(':');
            map.put(key, value());
            skipWhitespace();
            char c = text.charAt(position++);
            if (c == '}') {
                return map;
            }
            if (c != ',') {
                throw error("expected , or }");
            }
        }
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<>();
        position++;
        skipWhitespace();
        if (peek() == ']') {
            position++;
            return list;
        }
        while (true) {
            list.add(value());
            skipWhitespace();
            char c = text.charAt(position++);
            if (c == ']') {
                return list;
            }
            if (c != ',') {
                throw error("expected , or ]");
            }
        }
    }

    private String string() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (true) {
            char c = text.charAt(position++);
            if (c == '"') {
                return out.toString();
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            char escaped = text.charAt(position++);
            switch (escaped) {
                case 'n' -> out.append('\n');
                case 't' -> out.append('\t');
                case 'r' -> out.append('\r');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'u' -> {
                    out.append((char) Integer.parseInt(text.substring(position, position + 4), 16));
                    position += 4;
                }
                default -> out.append(escaped);
            }
        }
    }

    private Double number() {
        int start = position;
        while (position < text.length() && "+-0123456789.eE".indexOf(text.charAt(position)) >= 0) {
            position++;
        }
        if (start == position) {
            throw error("unexpected character");
        }
        return Double.parseDouble(text.substring(start, position));
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, position)) {
            throw error("expected " + word);
        }
        position += word.length();
        return value;
    }

    private void expect(char c) {
        if (position >= text.length() || text.charAt(position) != c) {
            throw error("expected " + c);
        }
        position++;
    }

    private char peek() {
        return position < text.length() ? text.charAt(position) : 0;
    }

    private void skipWhitespace() {
        while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
            position++;
        }
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message + " at offset " + position);
    }
}
