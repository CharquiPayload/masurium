package masurium.server;

import masurium.common.Json;
import masurium.server.Rules.Family;
import masurium.server.Rules.Layer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A bot's three layers of rules, and what they come to. */
class RulesTest {

    private static Map<String, Object> cases() throws Exception {
        try (InputStream in = RulesTest.class.getResourceAsStream("/masurium/rules-cases.json")) {
            return Json.object(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    @DisplayName("the shared cases: the launcher works them out the same way")
    @SuppressWarnings("unchecked")
    void sharedCases() throws Exception {
        List<Object> all = (List<Object>) cases().get("cases");
        assertFalse(all.isEmpty());
        for (Object o : all) {
            Map<String, Object> c = (Map<String, Object>) o;
            String name = (String) c.get("name");
            Rules r = new Rules();
            for (String layer : Rules.LAYERS) {
                r.set(layer, Layer.fromJson(c.get(layer), true));
            }
            Rules.Effective e = r.effective();
            ((Map<String, Object>) c.get("prefs")).forEach((k, v) ->
                    assertEquals(v, e.prefs().get(k), name + ": " + k));
            assertEquals(c.get("food_banned"), new ArrayList<>(e.food()), name);
            assertEquals(c.get("break_allowed"), new ArrayList<>(e.breaking()), name);
            for (Object s : (List<Object>) c.get("sources")) {
                List<Object> row = (List<Object>) s;
                Family f = "prefs".equals(row.get(0)) ? null : Family.of((String) row.get(0));
                assertEquals(row.get(2), r.source(f, (String) row.get(1)).layer(),
                        name + ": " + row);
            }
        }
    }

    @Test
    @DisplayName("a layer reads back what it wrote, in the shape the launcher sends")
    void layerRoundTrip() {
        Layer l = Layer.fromJson(Json.parse("{\"prefs\":{\"Hunt_Players\":true},"
                + "\"food\":{\"ban\":[\"Beef\",\"minecraft:salmon\"],\"allow\":[\"golden_apple\"]},"
                + "\"break\":{\"allow\":[\"oak_log\"],\"replace\":true},"
                + "\"from\":{\"food.beef\":\"global\"}}"), true);
        assertEquals(Map.of("hunt_players", true), l.prefs);
        assertEquals(Map.of("beef", true, "salmon", true, "golden_apple", false), l.list(Family.FOOD));
        assertTrue(l.replace.contains(Family.BREAK));
        assertEquals(l, Layer.fromJson(Json.parse(Json.write(l.toJson())), true));
        assertEquals("{\"prefs\":{\"hunt_players\":true},\"food\":{\"ban\":[\"beef\",\"salmon\"],"
                + "\"allow\":[\"golden_apple\"]},\"break\":{\"allow\":[\"oak_log\"],\"replace\":true},"
                + "\"from\":{\"food.beef\":\"global\"}}", Json.write(l.toJson()));
        assertTrue(new Layer().toJson().isEmpty());
    }

    @Test
    @DisplayName("from outside, anything not understood is refused, saying what")
    void strictRefuses() {
        for (String bad : List.of(
                "[]",
                "{\"prefz\":{}}",
                "{\"prefs\":{\"hunt_playerz\":true}}",
                "{\"prefs\":{\"hunt_players\":\"yes\"}}",
                "{\"food\":{\"veto\":[\"beef\"]}}",
                "{\"food\":{\"ban\":\"beef\"}}",
                "{\"food\":{\"ban\":[\"a:b:c\"]}}",
                "{\"food\":{\"ban\":[\":cog\"]}}",
                "{\"food\":{\"ban\":[\"beef\"],\"allow\":[\"beef\"]}}",
                "{\"break\":{\"replace\":\"yes\"}}",
                "{\"from\":{\"food\":\"global\"}}",
                "{\"from\":{\"food.beef\":\"\"}}")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> Layer.fromJson(Json.parse(bad), true), bad);
            assertFalse(e.getMessage().isBlank(), bad);
        }
    }

    @Test
    @DisplayName("from its own file, a toggle the code no longer has is dropped, not fatal")
    void lenientDrops() {
        Layer l = Layer.fromJson(Json.parse("{\"prefs\":{\"fly_to_the_moon\":true,\"hunt_players\":true},"
                + "\"food\":{\"ban\":[\"beef\",\"not an id\"]},\"extra\":1}"), false);
        assertEquals(Map.of("hunt_players", true), l.prefs);
        assertEquals(Map.of("beef", true), l.list(Family.FOOD));
        // A hand edit that broke a whole part costs that part, not the server's start.
        assertTrue(Layer.fromJson(Json.parse("[1]"), false).isEmpty());
        assertTrue(Layer.fromJson(Json.parse("{\"prefs\":[],\"food\":\"x\",\"from\":1}"), false).isEmpty());
        assertTrue(Rules.fromJson(Json.parse("{\"own\":[1],\"base\":\"x\"}")).isEmpty());
    }

    @Test
    @DisplayName("merging leaves both layers as they were")
    void mergeTouchesNothing() {
        Layer low = Layer.fromJson(Json.parse("{\"food\":{\"ban\":[\"beef\"]}}"), true);
        Layer high = Layer.fromJson(Json.parse("{\"food\":{\"ban\":[\"cod\"],\"replace\":true}}"), true);
        Layer m = Rules.merge(low, high);
        assertEquals(Map.of("cod", true), m.list(Family.FOOD));
        assertEquals(Map.of("beef", true), low.list(Family.FOOD));
        assertEquals(Map.of("cod", true), high.list(Family.FOOD));
    }

    @Test
    @DisplayName("what is imposed says who imposes it; the rest is not imposed")
    void whoImposes() {
        Rules r = new Rules();
        r.set(Rules.IMPOSED, Layer.fromJson(Json.parse("{\"prefs\":{\"hunt_players\":false},"
                + "\"food\":{\"ban\":[\"beef\"]},\"break\":{\"replace\":true},"
                + "\"from\":{\"prefs.hunt_players\":\"global\",\"break.*\":\"group miners\"}}"), true));
        assertEquals("imposed by global", r.imposedOn(null, "hunt_players").say());
        // Named, but by no one in particular: the launcher.
        assertEquals("imposed by the launcher", r.imposedOn(Family.FOOD, "beef").say());
        // A replaced list is imposed whole, every id of it.
        assertEquals("imposed by group miners", r.imposedOn(Family.BREAK, "dirt").say());
        assertNull(r.imposedOn(Family.FOOD, "salmon"));
        assertNull(r.imposedOn(null, "tame_wolves"));
        assertThrows(IllegalArgumentException.class, () -> r.layer("global"));
    }

    @Test
    @DisplayName("the three layers survive their own file")
    void rulesRoundTrip() {
        Rules r = new Rules();
        r.layer(Rules.OWN).list(Family.FOOD).put("beef", true);
        r.layer(Rules.BASE).prefs.put("hunt_players", true);
        Rules back = Rules.fromJson(Json.parse(Json.pretty(r.toJson())));
        assertEquals(r.toJson(), back.toJson());
        assertTrue(new Rules().isEmpty());
        assertFalse(back.isEmpty());
    }
}
