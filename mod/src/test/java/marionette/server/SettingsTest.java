package marionette.server;

import marionette.common.Settings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The settings table, shared by the body and the server. It used to live only inside the
 * body, so the server could offer a key that did nothing.
 */
class SettingsTest {

    @Test
    @DisplayName("the toggles that have to exist are there, with the safe side by default")
    void theSafeOnesAreOffByDefault() {
        // These three let a bot hit people or dig up someone else's world. If a careless
        // edit ever flips one of these defaults, this test is the one that says so.
        assertFalse(Settings.byDefault("hunt_players"));
        assertFalse(Settings.byDefault("defend_from_players"));
        assertFalse(Settings.byDefault("break_to_advance"));
        // And these are survival, not taste: off they leave the bot dying at work.
        assertTrue(Settings.byDefault("retreat_when_hurt"));
        assertTrue(Settings.byDefault("recover_on_death"));
    }

    @Test
    @DisplayName("an unknown key is unknown, whatever it looks like")
    void unknownKeysAreRefused() {
        assertTrue(Settings.known("bunny_hop"));
        assertFalse(Settings.known("buny_hop"));
        assertFalse(Settings.known(""));
        assertFalse(Settings.known(null));
        // Case is the caller's business: the command lowercases before asking.
        assertFalse(Settings.known("BUNNY_HOP"));
    }

    @Test
    @DisplayName("every toggle says what it does, because the command has to explain it")
    void everyToggleIsDescribed() {
        for (String key : Settings.keys()) {
            String does = Settings.describe(key);
            assertNotNull(does, key);
            assertFalse(does.isBlank(), key);
            // Long enough to be a sentence: the chat shows this to whoever is about to
            // switch something they may not know.
            assertTrue(does.length() > 20, key + ": " + does);
            assertFalse(does.endsWith("."), key + ": no full stop, it is a label");
        }
        assertNull(Settings.describe("there_is_no_such_thing"));
    }

    @Test
    @DisplayName("the keys are sorted and the defaults cover all of them")
    void keysAndDefaultsAgree() {
        assertEquals(Settings.keys().size(), Settings.defaults().size());
        assertEquals(Settings.keys(), new ArrayList<>(Settings.defaults().keySet()));
        // Ids in the same shape as everything else in the project: lowercase with
        // underscores, so a command can take them as one word.
        for (String key : Settings.keys()) {
            assertTrue(key.matches("[a-z][a-z0-9_]*"), key);
        }
    }
}
