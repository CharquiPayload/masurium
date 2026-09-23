package masurium.common;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * What can be tested without opening Minecraft: reading the query of a URL and escaping
 * text for JSON.
 * It lives in {@code common/} and BOTH mods compile it (through {@code srcDir}), so there
 * are never two copies of the same thing; an untested copy is exactly the one that
 * breaks. The tests live in mod-server and cover both.
 * Everything that does not need the game to work is kept out of the mods, because here it
 * is tested in milliseconds without starting anything.
 */
public final class Request {

    private Request() {}

    /** Parses the raw query string: {@code a=1&b=hello%20world}. */
    public static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null || raw.isEmpty()) return m;
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            // Without '=' there is no pair; with '=' at the start the key would be empty.
            if (i <= 0) continue;
            m.put(decode(pair.substring(0, i)),
                  decode(pair.substring(i + 1)));
        }
        return m;
    }

    private static String decode(String s) {
        return URLDecoder.decode(s, StandardCharsets.UTF_8);
    }

    /**
     * A mandatory numeric parameter. It is truncated downwards because block coordinates
     * are integers and the model often sends decimals.
     */
    public static int whole(Map<String, String> q, String key) {
        String v = q.get(key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("missing " + key);
        }
        try {
            return (int) Math.floor(Double.parseDouble(v.trim()));
        } catch (NumberFormatException e) {
            // Loud on purpose: "not a number" is fixed in a minute, a silent 0 sends the
            // bot to the other side of the world.
            throw new IllegalArgumentException(key + " is not a number: " + v);
        }
    }

    /** Escapes a string to put it inside a hand-made JSON. */
    public static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n', '\r' -> sb.append(' ');
                case '\t' -> sb.append(' ');
                default -> {
                    // Control characters would break the JSON without showing why.
                    if (c < 0x20) sb.append(' ');
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }
}
