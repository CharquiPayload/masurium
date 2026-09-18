package marionette.bot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the title screen says, which is the only thing a person actually reads. */
class TitleNoticeTest {

    @Test
    @DisplayName("a configured bot is shown nothing: there is nobody watching it")
    void aBotSaysNothing() {
        assertNull(TitleNotice.linesFor(true, false));
    }

    @Test
    @DisplayName("an empty flag reads as a problem, because it is one")
    void misconfiguredIsAWarning() {
        String[] lines = TitleNotice.linesFor(false, true);
        assertEquals(TitleNotice.MISCONFIGURED, lines[0]);
        // The fix has to be in the message. Being told something is wrong without
        // being told what to do about it is the same as not being told.
        assertTrue(lines[1].contains("-Dmarionette.name="), lines[1]);
    }

    @Test
    @DisplayName("a player is told the mod is idle, not that anything is broken")
    void aPlayerIsReassured() {
        String[] lines = TitleNotice.linesFor(false, false);
        assertEquals(TitleNotice.NOT_A_BOT, lines[0]);
        assertTrue(lines[1].contains("on purpose"), lines[1]);
        // Nothing here should read as an error: this person did nothing wrong.
        assertNotEquals(TitleNotice.MISCONFIGURED, lines[0]);
    }

    @Test
    @DisplayName("the two cases never say the same thing")
    void theTwoSilencesAreToldApart() {
        assertNotEquals(TitleNotice.linesFor(false, true)[0],
                TitleNotice.linesFor(false, false)[0]);
    }
}
