package masurium.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The logbook exists because one night there was no way to know what had happened.
 * What is tested here is not that it stores things (that is easy) but the two promises
 * that make it useful: that it <b>does not grow without limit</b>, and that it <b>does
 * not lose notes silently</b>. A log that swallows notes without saying so lies just like
 * a bot that says "done" without having done it.
 */
class LogbookTest {

    @BeforeEach
    void reset() {
        Logbook.restart();
    }

    @Test
    @DisplayName("repeats are counted, not stacked: 40 blocks are one line")
    void repeatsAreCountedNotStacked40Blocks() {
        for (int i = 0; i < 40; i++) Logbook.note("bridge", "block placed");

        assertEquals(1, Logbook.savedOnes());
        String json = Logbook.json(0, 50);
        assertTrue(json.contains("\"times\":40"), json);
        assertEquals(1, tally(json, "\"id\":"), json);
    }

    @Test
    @DisplayName("two different things do not collapse, even when alternating")
    void twoDifferentThingsDoNotCollapseEven() {
        Logbook.note("bridge", "block placed");
        Logbook.note("tower", "block placed");   // same text, another category
        Logbook.note("bridge", "block placed");

        assertEquals(3, Logbook.savedOnes());
    }

    @Test
    @DisplayName("the ring has a ceiling: 5000 notes do not take 5000 slots")
    void ringHasCeiling5000NotesDoNot() {
        for (int i = 0; i < 5000; i++) Logbook.note("route", "step " + i);

        assertTrue(Logbook.savedOnes() <= 400,
                   "the ring grew to " + Logbook.savedOnes());
        assertTrue(Logbook.json(0, 200).length() < 40_000);
    }

    @Test
    @DisplayName("what falls off the ring is SAID, it does not vanish in silence")
    void whatFallsOffRingIsSaidIt() {
        for (int i = 0; i < 500; i++) Logbook.note("route", "step " + i);

        String json = Logbook.json(0, 200);
        assertTrue(json.contains("\"tossed\":100"), json);
        // And it shows where what remains starts: whoever reads knows there is a gap
        // between id 1 and the first one kept.
        assertTrue(json.contains("\"first_saved\":101"), json);
    }

    @Test
    @DisplayName("when trimming by limit the LAST ones stay, and how many are missing is said")
    void whenTrimmingByLimitLastOnesStay() {
        for (int i = 1; i <= 30; i++) Logbook.note("route", "step " + i);

        String json = Logbook.json(0, 5);
        assertTrue(json.contains("step 30"), json);
        assertFalse(json.contains("step 25"), json);
        assertTrue(json.contains("\"omitted_by_limit\":25"), json);
    }

    @Test
    @DisplayName("since=N returns only what is new")
    void sinceNReturnsOnlyWhatIsNew() {
        Logbook.note("route", "old");
        Logbook.note("route", "new");

        String json = Logbook.json(1, 50);
        assertFalse(json.contains("old"), json);
        assertTrue(json.contains("new"), json);
        assertEquals(2, readLast(json));
    }

    @Test
    @DisplayName("the quotes of an odd block do not break the JSON")
    void quotesOfOddBlockDoNotBreak() {
        Logbook.note("route", "I went \"home\" and got\\stuck");

        String json = Logbook.json(0, 50);
        assertFalse(json.contains("went \"home\""), json);
        assertTrue(json.contains("\\\"home\\\""), json);
        assertTrue(json.contains("got\\\\stuck"), json);
    }

    private static int tally(String text, String fragment) {
        int n = 0;
        for (int i = text.indexOf(fragment); i >= 0; i = text.indexOf(fragment, i + 1)) n++;
        return n;
    }

    private static long readLast(String json) {
        int i = json.indexOf("\"last\":") + "\"last\":".length();
        return Long.parseLong(json.substring(i, json.indexOf(',', i)));
    }
}
