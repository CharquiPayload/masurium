package marionette.bot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What is written over the black screen.
 *
 * <p>A black window is the one symptom everybody reads as a crash, so getting this
 * wrong costs someone an evening of debugging something that was working.
 */
class BotHudTest {

    private static String all(List<String> lines) {
        return String.join(" | ", lines);
    }

    @Test
    @DisplayName("the name is there: with several windows open it is the only question")
    void theNameIsAlwaysShown() {
        assertTrue(all(BotHud.lines("Alice", "10.0.0.5:25565", true, false))
                .contains("Alice"));
        assertTrue(all(BotHud.lines("Bob", null, false, false)).contains("Bob"));
    }

    @Test
    @DisplayName("it says the black is deliberate, whatever else is going on")
    void theBlackIsExplained() {
        for (boolean connected : new boolean[] {true, false}) {
            String text = all(BotHud.lines("Alice", "10.0.0.5:25565", connected, false));
            assertTrue(text.contains("Not drawing the world"), text);
            assertTrue(text.contains("save GPU"), text);
        }
    }

    @Test
    @DisplayName("both keys are offered, and the render one flips its wording")
    void theKeysAreOffered() {
        String off = all(BotHud.lines("Alice", "10.0.0.5:25565", true, false));
        assertTrue(off.contains("Press " + BotHud.KEY_MINIMISE), off);
        assertTrue(off.contains("Press " + BotHud.KEY_RENDER + " to look"), off);

        String on = all(BotHud.lines("Alice", "10.0.0.5:25565", true, true));
        // While it IS drawing, offering "press R to look" would be nonsense.
        assertTrue(on.contains("Drawing the world"), on);
        assertFalse(on.contains("Not drawing"), on);
    }

    @Test
    @DisplayName("where it is says something different from where it was told to go")
    void joiningIsNotTheSameAsBeingThere() {
        assertTrue(all(BotHud.lines("Alice", "10.0.0.5:25565", false, false))
                .contains("Joining 10.0.0.5:25565"));
        assertTrue(all(BotHud.lines("Alice", "10.0.0.5:25565", true, false))
                .contains("In a world on 10.0.0.5:25565"));
        // No server and not in a world: a bot waiting at the menu, and the title screen
        // already explains that one, so this stays short.
        assertTrue(all(BotHud.lines("Alice", null, false, false))
                .contains("Not in a world"));
    }
}
