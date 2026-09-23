package masurium.bot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThinkingTest {

    @Test
    @DisplayName("thinking from when the bridge says it starts until it says it ended")
    void startsAndEnds() {
        Thinking.set(true, 1000);
        assertTrue(Thinking.at(1001));
        Thinking.set(false, 2000);
        assertFalse(Thinking.at(2001));
    }

    @Test
    @DisplayName("a turn nobody ends is forgotten after two minutes: a bridge can die mid-turn")
    void forgottenAlone() {
        Thinking.set(true, 1000);
        assertTrue(Thinking.at(1000 + Thinking.FORGOTTEN_AFTER_MS - 1));
        assertFalse(Thinking.at(1000 + Thinking.FORGOTTEN_AFTER_MS));
        Thinking.set(false, 0);
    }
}
