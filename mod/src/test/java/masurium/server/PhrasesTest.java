package masurium.server;

import masurium.common.Phrases;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a bot says by itself, without the brain: its own version when its brain wrote a
 * good one, plain English when not.
 */
class PhrasesTest {

    @Test
    @DisplayName("without a version of its own, plain English, with its numbers")
    void englishWithoutAVersion() {
        assertEquals("Alice, creeper 15 blocks from you!",
                Phrases.of(Map.of(), "escort_creeper", "Alice", 15));
    }

    @Test
    @DisplayName("the bot's own version is said, the placeholders in its own order")
    void itsOwnVersion() {
        Map<String, String> own = Map.of("escort_creeper",
                "¡Cuidado {player}! Un creeper a {blocks} bloques de ti");
        assertEquals("¡Cuidado Alice! Un creeper a 15 bloques de ti",
                Phrases.of(own, "escort_creeper", "Alice", 15));
        Map<String, String> reordered = Map.of("nothing_here",
                "Tramo {segment}/{segments}: aquí no hay {what}");
        assertEquals("Tramo 2/5: aquí no hay aldea",
                Phrases.of(reordered, "nothing_here", "aldea", 2, 5));
    }

    @Test
    @DisplayName("a version that lost or invented a placeholder is not said: English is")
    void aBrokenVersionIsNotSaid() {
        assertFalse(Phrases.acceptable("escort_creeper", "Cuidado, un creeper cerca de ti"));
        assertFalse(Phrases.acceptable("escort_creeper", "{player}, {blocks} {thing}"));
        assertFalse(Phrases.acceptable("escort_creeper", "{player}, creeper a {blocks} {"));
        assertFalse(Phrases.acceptable("turning_back", "/kill @a"));
        assertFalse(Phrases.acceptable("turning_back", "Me devuelvo\ny algo más"));
        assertFalse(Phrases.acceptable("turning_back", "x".repeat(300)));
        assertFalse(Phrases.acceptable("no_such_key", "anything"));
        assertTrue(Phrases.acceptable("turning_back", "Me devuelvo."));
        assertEquals("Alice, creeper 15 blocks from you!", Phrases.of(
                Map.of("escort_creeper", "Cuidado, un creeper cerca"), "escort_creeper", "Alice", 15));
    }

    @Test
    @DisplayName("an unknown key is a bug, said loudly")
    void unknownKey() {
        assertThrows(IllegalArgumentException.class, () -> Phrases.of(Map.of(), "no_such_sentence"));
    }

    @Test
    @DisplayName("the English catalog: no sentence is a command, and placeholders have names")
    void theCatalog() {
        Phrases.catalog().forEach((key, sentence) -> {
            assertFalse(sentence.startsWith("/"), key);
            assertFalse(sentence.contains("%"), key);
            assertFalse(sentence.replaceAll("\\{[a-z]+\\}", "").contains("{"), key);
            assertTrue(Phrases.acceptable(key, sentence), key);
        });
        assertEquals(List.of("player", "blocks"), placeholdersOf("escort_creeper"));
    }

    @Test
    @DisplayName("the versions file is read as UTF-8 properties, only the known keys")
    void readsTheFile() throws Exception {
        Path f = Files.createTempFile("phrases", ".properties");
        Files.writeString(f, "# written by the brain\n"
                + "turning_back=Me devuelvo, ¡qué lástima!\n"
                + "unknown=not a sentence\n"
                + "_fingerprint=abc\n", StandardCharsets.UTF_8);
        Map<String, String> read = Phrases.read(f);
        assertEquals(Map.of("turning_back", "Me devuelvo, ¡qué lástima!"), read);
        assertEquals(Map.of(), Phrases.read(f.resolveSibling("does-not-exist.properties")));
        Files.delete(f);
    }

    private static List<String> placeholdersOf(String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{([a-z]+)\\}")
                .matcher(Phrases.catalog().get(key));
        java.util.List<String> out = new java.util.ArrayList<>();
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }
}
