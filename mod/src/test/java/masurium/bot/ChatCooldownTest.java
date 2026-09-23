package masurium.bot;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatCooldownTest {

    @AfterEach
    void back() {
        ChatCooldown.reset();
    }

    @Test
    @DisplayName("once it spoke, it waits the cooldown before speaking on its own again")
    void waitsTheCooldown() {
        ChatCooldown.set(10_000);
        assertTrue(ChatCooldown.take(100_000, false));
        assertFalse(ChatCooldown.take(109_999, false));
        assertTrue(ChatCooldown.take(110_000, false));
    }

    @Test
    @DisplayName("an answer always goes out, and the cooldown starts again from it")
    void anAnswerAlwaysGoes() {
        ChatCooldown.set(10_000);
        assertTrue(ChatCooldown.take(100_000, false));
        assertTrue(ChatCooldown.take(102_000, true));
        assertFalse(ChatCooldown.take(111_000, false));
        assertTrue(ChatCooldown.take(112_000, false));
    }

    @Test
    @DisplayName("what the bridge said counts too, and 0 is no cooldown")
    void theBridgeCountsAndZeroIsNone() {
        ChatCooldown.spoke(100_000);
        assertFalse(ChatCooldown.take(105_000, false));
        assertEquals(5_000, ChatCooldown.sinceMs(105_000));
        ChatCooldown.set(0);
        assertTrue(ChatCooldown.take(105_000, false));
        assertTrue(ChatCooldown.take(105_001, false));
    }
}
