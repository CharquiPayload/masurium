package masurium.bot;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReceivedTest {

    private static final Object PLAYER = new Object();

    /** A player already settled at {@code t}, carrying {@code counts}. */
    private static Received settled(Map<String, Integer> counts, long t) {
        Received r = new Received();
        r.observe(PLAYER, counts, t - Received.SETTLE_MS);
        r.observe(PLAYER, counts, t);
        return r;
    }

    @Test
    @DisplayName("what came in is noted with when; asked since a moment, only what came after")
    void notesWhatCameIn() {
        Received r = settled(Map.of("bread", 10), 10_000);
        r.observe(PLAYER, Map.of("bread", 10, "cactus", 4), 20_000);
        r.observe(PLAYER, Map.of("bread", 12, "cactus", 4), 30_000);
        assertEquals(List.of(new Received.Gain(20_000, "cactus", 4),
                        new Received.Gain(30_000, "bread", 2)),
                r.since(15_000));
        assertEquals(List.of(new Received.Gain(30_000, "bread", 2)), r.since(25_000));
    }

    @Test
    @DisplayName("what went out is not a gift, and taking it back is only what it takes back")
    void goingOutIsNotComingIn() {
        Received r = settled(Map.of("bread", 10), 10_000);
        r.observe(PLAYER, Map.of("bread", 7), 11_000);
        r.observe(PLAYER, Map.of("bread", 9), 12_000);
        assertEquals(List.of(new Received.Gain(12_000, "bread", 2)), r.since(0));
    }

    @Test
    @DisplayName("a new player's backpack filling from the server is not a gift")
    void aNewPlayerSettlesFirst() {
        Received r = new Received();
        r.observe(PLAYER, Map.of(), 1_000);
        r.observe(PLAYER, Map.of("bread", 35, "iron_sword", 1), 1_500);
        assertTrue(r.since(0).isEmpty());
        Object respawned = new Object();
        r.observe(respawned, Map.of(), 5_000);
        r.observe(respawned, Map.of("bread", 35), 5_000 + Received.SETTLE_MS - 1);
        assertTrue(r.since(0).isEmpty());
        r.observe(respawned, Map.of("bread", 36), 5_000 + Received.SETTLE_MS);
        assertEquals(List.of(new Received.Gain(5_000 + Received.SETTLE_MS, "bread", 1)),
                r.since(0));
    }

    @Test
    @DisplayName("only the last few minutes are kept")
    void oldGainsGo() {
        Received r = settled(Map.of(), 10_000);
        r.observe(PLAYER, Map.of("cactus", 1), 20_000);
        r.observe(PLAYER, Map.of("cactus", 1), 20_000 + Received.KEPT_MS + 1);
        assertTrue(r.since(0).isEmpty());
    }
}
