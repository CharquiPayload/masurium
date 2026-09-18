package marionette.bot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The one decision that keeps the jar safe to install in a client someone plays on.
 *
 * <p>Getting this wrong in either direction is bad, and not symmetrically: a false
 * negative is a bot that does nothing and says why in its log, a false positive is
 * someone's game going dark and eating their food.
 */
class BotTest {

    @Test
    @DisplayName("no property at all: not a bot")
    void missingIsNotABot() {
        assertNull(Bot.nameFrom(null));
    }

    @Test
    @DisplayName("an empty or blank value is a launcher half configured, not a bot")
    void blankIsNotABot() {
        assertNull(Bot.nameFrom(""));
        assertNull(Bot.nameFrom("   "));
        assertNull(Bot.nameFrom("\t\n"));
    }

    @Test
    @DisplayName("a name is a name, and the spaces around it are not part of it")
    void aNameIsABot() {
        assertEquals("Alice", Bot.nameFrom("Alice"));
        assertEquals("Alice", Bot.nameFrom("  Alice  "));
    }

    @Test
    @DisplayName("a blank flag is not the same silence as no flag at all")
    void blankIsLoudNotQuiet() {
        // Same answer to "are you a bot?", different answer to "should anyone hear
        // about it?". A player gets a line in the log; a half-configured launcher gets
        // an error, because someone is waiting for a bot that is never coming.
        assertNull(Bot.nameFrom(""));
        assertNull(Bot.nameFrom(null));
    }

    @Test
    @DisplayName("the property is the one the launcher documentation names")
    void theFlagIsTheDocumentedOne() {
        // Renaming it silently would leave every configured instance a ghost, with
        // nothing in the game to say why.
        assertEquals("marionette.name", Bot.NAME_PROPERTY);
    }
}
