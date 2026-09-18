package marionette.server;

import marionette.common.Phrases;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a bot says by itself, without the brain. A live test caught a bot speaking Spanish
 * warning "Alice, creeper 15 blocks from you".
 */
class PhrasesTest {

    @Test
    @DisplayName("the warning comes out in the bot's language, with its numbers")
    void warningComesOutInTheBotsLanguage() {
        assertEquals("Alice, creeper a 15 bloques de ti",
                Phrases.in("es", "f", "escort_creeper", "Alice", 15));
        assertEquals("Alice, creeper 15 blocks from you",
                Phrases.in("en", "f", "escort_creeper", "Alice", 15));
    }

    @Test
    @DisplayName("an unknown language falls back to English instead of going mute")
    void unknownLanguageFallsBackToEnglish() {
        assertEquals(Phrases.in("en", "f", "creeper_cornered"),
                Phrases.in("ja", "f", "creeper_cornered"));
        assertEquals(Phrases.in("en", "f", "turning_back"),
                Phrases.in("", "f", "turning_back"));
    }

    @Test
    @DisplayName("gender changes the ending where the language inflects")
    void genderChangesTheEnding() {
        assertEquals("malherida y sin salida", Phrases.in("es", "f", "hurt_cornered"));
        assertEquals("malherido y sin salida", Phrases.in("es", "m", "hurt_cornered"));
        // English does not inflect, and no marker must leak into what is said.
        assertFalse(Phrases.in("en", "m", "hurt_cornered").contains("{"));
    }

    @Test
    @DisplayName("no language is missing a sentence, and none leaves a marker behind")
    void noLanguageIsMissingASentence() {
        Map<String, String> english = Phrases.byLanguage().get("en");
        Phrases.byLanguage().forEach((language, sentences) -> {
            assertEquals(english.keySet(), sentences.keySet(), "keys of " + language);
            sentences.forEach((key, pattern) -> {
                // {a} is the only marker; anything else left in is a typo that would
                // reach the chat.
                assertFalse(pattern.replace("{a}", "").contains("{"),
                        language + "/" + key);
                // A line starting with a slash would be a server command, not a sentence.
                assertFalse(pattern.startsWith("/"), language + "/" + key);
            });
        });
    }

    @Test
    @DisplayName("a sentence that does not exist complains instead of saying nothing")
    void missingSentenceComplains() {
        assertThrows(IllegalArgumentException.class,
                () -> Phrases.in("es", "f", "there_is_no_such_thing"));
    }

    @Test
    @DisplayName("the placeholders match between languages")
    void placeholdersMatchBetweenLanguages() {
        Map<String, String> english = Phrases.byLanguage().get("en");
        Phrases.byLanguage().forEach((language, sentences) -> sentences.forEach((key, s) -> {
            assertEquals(marks(english.get(key)), marks(s), language + "/" + key);
            assertTrue(s.length() <= 250, language + "/" + key);
        }));
    }

    /** "%s ... %d" -> "sd": what String.format will be asked to fill in. */
    private static String marks(String pattern) {
        StringBuilder found = new StringBuilder();
        for (int i = 0; i + 1 < pattern.length(); i++) {
            if (pattern.charAt(i) == '%') found.append(pattern.charAt(i + 1));
        }
        return found.toString();
    }
}
