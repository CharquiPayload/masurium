package masurium.server;

import masurium.common.Request;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The /mods answer, on its own so a test can call it: {@link MasuriumServer} names
 * Minecraft classes the moment it loads, and a unit test has none of them.
 */
final class ModsJson {

    private ModsJson() {
    }

    /** The answer for these versions, sorted by id so two answers compare as text. */
    static String of(Map<String, String> versions) {
        List<String> cards = new ArrayList<>();
        for (Map.Entry<String, String> e : new TreeMap<>(versions).entrySet()) {
            cards.add(String.format("{\"id\":\"%s\",\"version\":\"%s\"}",
                    Request.escape(e.getKey()), Request.escape(e.getValue())));
        }
        return "{\"ok\":true,\"mods\":[" + String.join(",", cards) + "]}";
    }
}
