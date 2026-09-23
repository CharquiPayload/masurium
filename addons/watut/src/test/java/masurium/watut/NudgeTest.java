package masurium.watut;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NudgeTest {

    @Test
    @DisplayName("standing still and doing nothing is no action: it can go away")
    void stillIsNothing() {
        assertFalse(new Nudge().due(0, false, 100));
        assertFalse(new Nudge().due(Nudge.STILL / 2, false, 100));
    }

    @Test
    @DisplayName("walking or acting is an action, told once a second at most")
    void movingOrActing() {
        Nudge n = new Nudge();
        assertTrue(n.due(0.04, false, 100));
        assertFalse(n.due(0.04, false, 101));
        assertFalse(n.due(0, true, 119));
        assertTrue(n.due(0, true, 120));
    }

    @Test
    @DisplayName("a tick counter that starts over (a new world) does not silence it")
    void counterStartsOver() {
        Nudge n = new Nudge();
        assertTrue(n.due(1, false, 5000));
        assertTrue(n.due(1, false, 3));
    }
}
