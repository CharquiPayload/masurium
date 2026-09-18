package marionette.bot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the title screen says, which is the only thing a person actually reads. */
class NoticeTextTest {

    @Test
    @DisplayName("a configured bot is shown nothing: there is nobody watching it")
    void aBotSaysNothing() {
        assertNull(NoticeText.linesFor(true, false));
    }

    @Test
    @DisplayName("an empty flag reads as a problem, because it is one")
    void misconfiguredIsAWarning() {
        String[] lines = NoticeText.linesFor(false, true);
        assertEquals(NoticeText.MISCONFIGURED, lines[0]);
        // The fix has to be in the message. Being told something is wrong without
        // being told what to do about it is the same as not being told.
        assertTrue(lines[1].contains("-Dmarionette.name="), lines[1]);
    }

    @Test
    @DisplayName("a player is told the mod is idle, not that anything is broken")
    void aPlayerIsReassured() {
        String[] lines = NoticeText.linesFor(false, false);
        assertEquals(NoticeText.NOT_A_BOT, lines[0]);
        assertTrue(lines[1].contains("on purpose"), lines[1]);
        assertNotEquals(NoticeText.MISCONFIGURED, lines[0]);
    }

    @Test
    @DisplayName("the two cases never say the same thing")
    void theTwoSilencesAreToldApart() {
        assertNotEquals(NoticeText.linesFor(false, true)[0],
                NoticeText.linesFor(false, false)[0]);
    }

    @Test
    @DisplayName("the error cannot be silenced, the calm one can")
    void onlyTheCalmOneIsDismissible() {
        assertFalse(NoticeText.dismissible(true));
        assertTrue(NoticeText.dismissible(false));
    }

    @Test
    @DisplayName("a click counts only inside the text it is meant for")
    void hitTestingHasEdges() {
        // left, right, top, height = 10..50, 20..30
        assertTrue(NoticeText.inside(10, 20, 10, 50, 20, 10));
        assertTrue(NoticeText.inside(50, 30, 10, 50, 20, 10));
        assertTrue(NoticeText.inside(30, 25, 10, 50, 20, 10));
        assertFalse(NoticeText.inside(9, 25, 10, 50, 20, 10));
        assertFalse(NoticeText.inside(51, 25, 10, 50, 20, 10));
        assertFalse(NoticeText.inside(30, 19, 10, 50, 20, 10));
        assertFalse(NoticeText.inside(30, 31, 10, 50, 20, 10));
        // A widget that was not drawn this frame has left = -1 and must never be hit.
        assertFalse(NoticeText.inside(30, 25, -1, 50, 20, 10));
    }

    @Test
    @DisplayName("the manual link points at the setup page, not at the repo root")
    void theLinkGoesWhereTheAnswerIs() {
        assertTrue(NoticeText.URL.startsWith("https://"), NoticeText.URL);
        assertTrue(NoticeText.URL.endsWith("docs/setup.md"), NoticeText.URL);
    }
}
