package marionette.bot;

import marionette.bot.NoticeText.Notice;
import marionette.bot.NoticeText.Severity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the title screen says, which is the only thing a person actually reads. */
class NoticeTextTest {

    private static List<String> ids(List<Notice> notices) {
        return notices.stream().map(Notice::id).toList();
    }

    @Test
    @DisplayName("a bot with everything set is TOLD it worked, not left in silence")
    void successIsSaidOutLoud() {
        List<Notice> n = NoticeText.noticesFor(true, false, true, "Alice", "10.0.0.5:25565");
        assertEquals(List.of("ready"), ids(n));
        assertEquals(Severity.SUCCESS, n.get(0).severity());
        // The name has to be in it: "a bot is ready" does not tell you WHICH, and with
        // several instances open that is the whole question.
        assertTrue(n.get(0).text().contains("Alice"), n.get(0).text());
        assertTrue(n.get(0).hint().contains("10.0.0.5:25565"), n.get(0).hint());
    }

    @Test
    @DisplayName("an empty name is fatal, says so alone, and cannot be silenced")
    void blankNameIsFatalAndAlone() {
        List<Notice> n = NoticeText.noticesFor(false, true, false, null, null);
        // Alone on purpose: nothing else matters when this client is not a bot at all,
        // and a second line would bury the one that has to be fixed first.
        assertEquals(List.of("blank-name"), ids(n));
        assertEquals(Severity.ERROR, n.get(0).severity());
        assertFalse(n.get(0).dismissible());
    }

    @Test
    @DisplayName("a player is told the mod is idle, calmly, and can silence it")
    void aPlayerIsReassured() {
        List<Notice> n = NoticeText.noticesFor(false, false, false, null, null);
        assertEquals(List.of("not-a-bot"), ids(n));
        assertEquals(Severity.INFO, n.get(0).severity());
        assertTrue(n.get(0).dismissible());
    }

    @Test
    @DisplayName("good news first, then what is wrong")
    void successComesBeforeTheProblem() {
        List<Notice> n = NoticeText.noticesFor(true, false, false, "Alice", null);
        assertEquals(List.of("ready", "no-server"), ids(n));
        assertEquals(Severity.SUCCESS, n.get(0).severity());
        assertEquals(Severity.ERROR, n.get(1).severity());
        // It loaded AND it will not join: both true, and saying only the second reads
        // as if the mod had failed.
        assertTrue(n.get(0).hint().contains("not join"), n.get(0).hint());
    }

    @Test
    @DisplayName("a defaulted port is NOT put on screen: that one belongs in the log")
    void theDefaultPortIsNotNagged() {
        // With one bot the default is the right answer, and warning someone about a
        // correct setup teaches them to ignore this box entirely.
        for (List<Notice> n : List.of(
                NoticeText.noticesFor(true, false, true, "Alice", "10.0.0.5:25565"),
                NoticeText.noticesFor(true, false, false, "Alice", null))) {
            assertFalse(ids(n).contains("default-port"), ids(n).toString());
        }
    }

    @Test
    @DisplayName("every notice offers a way out, because a complaint alone is noise")
    void everyNoticeCarriesItsFix() {
        for (boolean bot : new boolean[] {true, false}) {
            for (boolean blank : new boolean[] {true, false}) {
                for (Notice n : NoticeText.noticesFor(bot, blank, false, "Alice", null)) {
                    assertFalse(n.hint().isBlank(), n.id());
                    assertFalse(n.text().isBlank(), n.id());
                }
            }
        }
    }

    @Test
    @DisplayName("silencing one leaves the others, and never silences an error")
    void silencingIsPerNotice() {
        List<Notice> both = NoticeText.noticesFor(true, false, false, "Alice", null);
        assertEquals(List.of("no-server"),
                ids(NoticeText.notSilenced(both, List.of("ready"))));
        assertEquals(List.of("ready"),
                ids(NoticeText.notSilenced(both, List.of("no-server"))));

        List<Notice> fatal = NoticeText.noticesFor(false, true, false, null, null);
        // Even asked to hide it, the one that cannot be dismissed stays.
        assertEquals(List.of("blank-name"),
                ids(NoticeText.notSilenced(fatal, List.of("blank-name"))));
    }

    @Test
    @DisplayName("a click counts only inside the text it is meant for")
    void hitTestingHasEdges() {
        assertTrue(NoticeText.inside(10, 20, 10, 50, 20, 10));
        assertTrue(NoticeText.inside(50, 30, 10, 50, 20, 10));
        assertFalse(NoticeText.inside(9, 25, 10, 50, 20, 10));
        assertFalse(NoticeText.inside(51, 25, 10, 50, 20, 10));
        assertFalse(NoticeText.inside(30, 19, 10, 50, 20, 10));
        assertFalse(NoticeText.inside(30, 31, 10, 50, 20, 10));
        // A button that was not drawn this frame has left = -1 and must never be hit.
        assertFalse(NoticeText.inside(30, 25, -1, 50, 20, 10));
    }

    @Test
    @DisplayName("the manual link points at the setup page, not at the repo root")
    void theLinkGoesWhereTheAnswerIs() {
        assertTrue(NoticeText.URL.startsWith("https://"), NoticeText.URL);
        assertTrue(NoticeText.URL.endsWith("docs/setup.md"), NoticeText.URL);
    }
}
