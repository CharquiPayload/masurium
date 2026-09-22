package marionette.common;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON in and out, for the few places that READ it: a bot's rules, sent by the launcher
 * and kept in a file on the server.
 *
 * <p>Everything else the mod says in JSON is written by hand, a line at a time, and that
 * stays: an answer is a handful of fields. Rules are nested and come from outside, so
 * they need a real parser, and one that is strict. Minecraft ships Gson, but the tests
 * run without Minecraft's classes around, and what cannot be tested in milliseconds here
 * is the part that breaks.
 *
 * <p>What it reads: objects become {@link LinkedHashMap} (keys in their order), arrays
 * {@link ArrayList}, strings {@link String}, true and false {@link Boolean}, null
 * {@code null}, whole numbers {@link Long} and the rest {@link Double}. Anything
 * malformed is an {@link IllegalArgumentException} that says where.
 */
public final class Json {

    private Json() {}

    /** Deeper than this is not a rule, it is someone trying to overflow the stack. */
    static final int MAX_DEPTH = 32;

    // ------------------------------------------------------------------ reading

    public static Object parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("no JSON");
        }
        Reader r = new Reader(text);
        r.blank();
        Object value = r.value(0);
        r.blank();
        if (r.at < text.length()) {
            throw r.wrong("text after the end of the JSON");
        }
        return value;
    }

    /** An object, or an error saying it is not one. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(String text) {
        Object o = parse(text);
        if (!(o instanceof Map)) {
            throw new IllegalArgumentException("expected a JSON object");
        }
        return (Map<String, Object>) o;
    }

    private static final class Reader {
        final String s;
        int at;

        Reader(String s) {
            this.s = s;
        }

        IllegalArgumentException wrong(String what) {
            return new IllegalArgumentException("bad JSON at " + at + ": " + what);
        }

        void blank() {
            while (at < s.length() && " \t\r\n".indexOf(s.charAt(at)) >= 0) {
                at++;
            }
        }

        char next() {
            if (at >= s.length()) {
                throw wrong("it ends too soon");
            }
            return s.charAt(at);
        }

        Object value(int depth) {
            if (depth > MAX_DEPTH) {
                throw wrong("nested too deep");
            }
            char c = next();
            switch (c) {
                case '{':
                    return object(depth);
                case '[':
                    return array(depth);
                case '"':
                    return string();
                case 't':
                    return word("true", Boolean.TRUE);
                case 'f':
                    return word("false", Boolean.FALSE);
                case 'n':
                    return word("null", null);
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return number();
                    }
                    throw wrong("unexpected '" + c + "'");
            }
        }

        Object word(String w, Object value) {
            if (!s.startsWith(w, at)) {
                throw wrong("expected " + w);
            }
            at += w.length();
            return value;
        }

        Map<String, Object> object(int depth) {
            Map<String, Object> m = new LinkedHashMap<>();
            at++;
            blank();
            if (next() == '}') {
                at++;
                return m;
            }
            while (true) {
                blank();
                if (next() != '"') {
                    throw wrong("expected a key in quotes");
                }
                String key = string();
                blank();
                if (next() != ':') {
                    throw wrong("expected ':'");
                }
                at++;
                blank();
                if (m.containsKey(key)) {
                    // Two values for one key: whichever one wins, one was not meant.
                    throw wrong("the key '" + key + "' twice");
                }
                m.put(key, value(depth + 1));
                blank();
                char c = next();
                at++;
                if (c == '}') {
                    return m;
                }
                if (c != ',') {
                    at--;
                    throw wrong("expected ',' or '}'");
                }
            }
        }

        List<Object> array(int depth) {
            List<Object> l = new ArrayList<>();
            at++;
            blank();
            if (next() == ']') {
                at++;
                return l;
            }
            while (true) {
                blank();
                l.add(value(depth + 1));
                blank();
                char c = next();
                at++;
                if (c == ']') {
                    return l;
                }
                if (c != ',') {
                    at--;
                    throw wrong("expected ',' or ']'");
                }
            }
        }

        String string() {
            at++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next();
                at++;
                if (c == '"') {
                    return sb.toString();
                }
                if (c < 0x20) {
                    at--;
                    throw wrong("a control character inside a string");
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                char e = next();
                at++;
                switch (e) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        if (at + 4 > s.length()) {
                            throw wrong("a \\u escape cut short");
                        }
                        try {
                            sb.append((char) Integer.parseInt(s.substring(at, at + 4), 16));
                        } catch (NumberFormatException x) {
                            throw wrong("a \\u escape that is not hexadecimal");
                        }
                        at += 4;
                    }
                    default -> {
                        at--;
                        throw wrong("unknown escape \\" + e);
                    }
                }
            }
        }

        Object number() {
            int start = at;
            if (next() == '-') {
                at++;
            }
            boolean whole = true;
            while (at < s.length()) {
                char c = s.charAt(at);
                if (c >= '0' && c <= '9') {
                    at++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    whole = false;
                    at++;
                } else {
                    break;
                }
            }
            String n = s.substring(start, at);
            try {
                return whole ? (Object) Long.parseLong(n) : (Object) Double.parseDouble(n);
            } catch (NumberFormatException x) {
                at = start;
                throw wrong("not a number: " + n);
            }
        }
    }

    // ------------------------------------------------------------------ writing

    /** On one line, for answers. */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, -1, 0);
        return sb.toString();
    }

    /** Indented, for files a person may open. */
    public static String pretty(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, 2, 0);
        return sb.append('\n').toString();
    }

    private static void write(StringBuilder sb, Object v, int indent, int level) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String str) {
            quote(sb, str);
        } else if (v instanceof Boolean || v instanceof Long || v instanceof Integer) {
            sb.append(v);
        } else if (v instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new IllegalArgumentException("JSON has no " + d);
            }
            sb.append(d == Math.rint(d) && Math.abs(d) < 1e15 ? String.valueOf((long) d) : String.valueOf(d));
        } else if (v instanceof Map<?, ?> m) {
            if (m.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                line(sb, indent, level + 1);
                quote(sb, String.valueOf(e.getKey()));
                sb.append(indent < 0 ? ":" : ": ");
                write(sb, e.getValue(), indent, level + 1);
            }
            line(sb, indent, level);
            sb.append('}');
        } else if (v instanceof Collection<?> c) {
            if (c.isEmpty()) {
                sb.append("[]");
                return;
            }
            // Lists of plain values stay on one line even when indented: a list of
            // item ids reads better across than down.
            boolean flat = indent < 0 || c.stream().noneMatch(x -> x instanceof Map || x instanceof Collection);
            sb.append('[');
            boolean first = true;
            for (Object x : c) {
                if (!first) {
                    sb.append(flat && indent >= 0 ? ", " : ",");
                }
                first = false;
                if (!flat) {
                    line(sb, indent, level + 1);
                }
                write(sb, x, indent, level + 1);
            }
            if (!flat) {
                line(sb, indent, level);
            }
            sb.append(']');
        } else {
            throw new IllegalArgumentException("cannot write a " + v.getClass().getSimpleName() + " as JSON");
        }
    }

    private static void line(StringBuilder sb, int indent, int level) {
        if (indent >= 0) {
            sb.append('\n').append(" ".repeat(indent * level));
        }
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
