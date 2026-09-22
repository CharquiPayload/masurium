package marionette.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The small JSON reader and writer the rules travel in. */
class JsonTest {

    @Test
    @DisplayName("objects, arrays, strings, numbers, booleans and null come back as Java")
    void readsEverything() {
        Map<String, Object> m = Json.object(
                "{ \"a\": [1, -2, 3.5, true, false, null], \"b\": {\"c\": \"d\"}, \"e\": \"\" }");
        assertEquals(List.of(1L, -2L, 3.5, true, false), m.get("a") instanceof List<?> l
                ? new ArrayList<>(l.subList(0, 5)) : null);
        assertNull(((List<?>) m.get("a")).get(5));
        assertEquals(Map.of("c", "d"), m.get("b"));
        assertEquals("", m.get("e"));
        // Keys keep their order: a file read and written back does not shuffle.
        assertEquals(List.of("a", "b", "e"), new ArrayList<>(m.keySet()));
    }

    @Test
    @DisplayName("escapes, unicode and accents survive both ways")
    void escapes() {
        String text = "quote \" back \\ slash / tab\t line\n ñ ¡ \u0001";
        Object back = Json.parse(Json.write(text));
        assertEquals(text, back);
        assertEquals("é☃", Json.parse("\"\\u00e9\\u2603\""));
    }

    @Test
    @DisplayName("what it writes, it reads back the same, on one line or indented")
    void roundTrip() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("prefs", Map.of("hunt_players", true));
        m.put("food", Map.of("ban", List.of("beef", "rotten_flesh")));
        m.put("empty", Map.of());
        m.put("none", List.of());
        m.put("n", 42L);
        assertEquals(m, Json.parse(Json.write(m)));
        assertEquals(m, Json.parse(Json.pretty(m)));
        assertTrue(Json.pretty(m).contains("\"ban\": [\"beef\", \"rotten_flesh\"]"), Json.pretty(m));
        assertTrue(!Json.write(m).contains("\n"));
    }

    @Test
    @DisplayName("malformed JSON is refused, saying where")
    void refusesMalformed() {
        for (String bad : List.of("", "{", "{\"a\" 1}", "{\"a\":1,}", "[1 2]", "tru", "\"open",
                "{\"a\":1} extra", "{a:1}", "\"\\x\"", "\"\\u12\"", "01.2.3", "-",
                "{\"a\":1,\"a\":2}", "\"line\nbreak\"")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> Json.parse(bad), bad);
            assertTrue(e.getMessage().startsWith("bad JSON at "), bad + ": " + e.getMessage());
        }
        assertThrows(IllegalArgumentException.class, () -> Json.parse(null));
        assertThrows(IllegalArgumentException.class, () -> Json.object("[1]"));
    }

    @Test
    @DisplayName("nesting has a limit, so nobody overflows the stack with brackets")
    void depthLimit() {
        String deep = "[".repeat(Json.MAX_DEPTH + 5) + "]".repeat(Json.MAX_DEPTH + 5);
        assertThrows(IllegalArgumentException.class, () -> Json.parse(deep));
        String fine = "[".repeat(10) + "]".repeat(10);
        Json.parse(fine);
    }
}
