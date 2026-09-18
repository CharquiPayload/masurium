package marionette.bot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one decision that keeps the jar safe to install in a client someone plays on,
 * and the one that keeps a name from being used as a path.
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
        assertNull(Bot.nameFrom(" \t "));
    }

    @Test
    @DisplayName("a name is a name, and the spaces around it are not part of it")
    void aNameIsABot() {
        assertEquals("Alice", Bot.nameFrom("Alice"));
        assertEquals("Alice", Bot.nameFrom("  Alice  "));
    }

    @Test
    @DisplayName("ordinary names are accepted, including the ones that look odd")
    void realNamesPass() {
        assertTrue(Bot.usable("Alice"));
        assertTrue(Bot.usable("Bot_Alice"));
        assertTrue(Bot.usable("a"));
        assertTrue(Bot.usable("SomePlayer"));
        assertTrue(Bot.usable("_"));
        assertTrue(Bot.usable("0123456789abcdef"));
    }

    @Test
    @DisplayName("a name that is really a path is refused")
    void pathsAreRefused() {
        // This is the one that matters: the name becomes a directory and part of file
        // names, so anything able to climb out of it must never get that far.
        assertFalse(Bot.usable("../../etc"));
        assertFalse(Bot.usable(".."));
        assertFalse(Bot.usable("."));
        assertFalse(Bot.usable("a/b"));
        assertFalse(Bot.usable("a" + ((char) 92) + "b"));
        assertFalse(Bot.usable("/etc/passwd"));
        assertFalse(Bot.usable("~"));
    }

    @Test
    @DisplayName("a name with a space is refused: quoted by the launcher, it gets through")
    void spacesAreRefused() {
        // Unquoted, the JVM takes the second word as a class name and nothing starts.
        // Quoted, it arrives whole, and would be a directory with a space in it and a
        // chat token matching half of what anyone says.
        assertFalse(Bot.usable("Bot Alice"));
        assertFalse(Bot.usable("Bot" + ((char) 9) + "Alice"));
    }

    @Test
    @DisplayName("punctuation, quotes and control characters are refused")
    void punctuationIsRefused() {
        assertFalse(Bot.usable(String.valueOf((char) 34).repeat(5)));
        assertFalse(Bot.usable("[" + ((char) 92) + "/,"));
        assertFalse(Bot.usable("..."));
        assertFalse(Bot.usable("a;b"));
        assertFalse(Bot.usable("a" + ((char) 0) + "b"));
        assertFalse(Bot.usable("a" + ((char) 10) + "b"));
        assertFalse(Bot.usable("<script>"));
        assertFalse(Bot.usable("%s"));
        assertFalse(Bot.usable("Alice$(whoami)"));
    }

    @Test
    @DisplayName("too long is refused, and the limit is exactly Minecraft's")
    void lengthHasALimit() {
        assertTrue(Bot.usable("A".repeat(16)));
        assertFalse(Bot.usable("A".repeat(17)));
        assertFalse(Bot.usable(""));
        assertFalse(Bot.usable(null));
    }

    @Test
    @DisplayName("the server flag follows the same blank rule as the name")
    void aBlankServerIsNoServer() {
        assertNull(Bot.nameFrom(""));
        assertNull(Bot.nameFrom("  "));
        assertEquals("10.0.0.5:25565", Bot.nameFrom("  10.0.0.5:25565 "));
    }

    @Test
    @DisplayName("the properties are the ones the launcher documentation names")
    void theFlagsAreTheDocumentedOnes() {
        // Renaming one silently would leave every configured instance a ghost, with
        // nothing in the game to say why.
        assertEquals("marionette.name", Bot.NAME_PROPERTY);
        assertEquals("marionette.server", Bot.SERVER_PROPERTY);
        assertEquals("marionette.bot.port", Bot.PORT_PROPERTY);
    }
}
