package masurium.bot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** When a bot decides that a server has no Masurium on its side. */
class ServerCheckTest {

    @Test
    @DisplayName("the command is there: fine, at once")
    void commandThere() {
        assertEquals(ServerCheck.Verdict.FINE, ServerCheck.judge(true, 1));
        assertEquals(ServerCheck.Verdict.FINE, ServerCheck.judge(true, ServerCheck.PATIENCE + 50));
    }

    @Test
    @DisplayName("no command yet: it waits, the tree may come late")
    void waitsAWhile() {
        assertEquals(ServerCheck.Verdict.WAIT, ServerCheck.judge(false, 1));
        assertEquals(ServerCheck.Verdict.WAIT, ServerCheck.judge(false, ServerCheck.PATIENCE - 1));
    }

    @Test
    @DisplayName("no command after the patience: it leaves")
    void thenLeaves() {
        assertEquals(ServerCheck.Verdict.LEAVE, ServerCheck.judge(false, ServerCheck.PATIENCE));
    }

    @Test
    @DisplayName("the command it looks for is the one the server half registers")
    void sameCommand() {
        assertEquals("masurium", ServerCheck.COMMAND);
    }
}
