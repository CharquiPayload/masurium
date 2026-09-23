package masurium.server;

import masurium.common.Misses;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Not giving arrows away. A bot escorting someone emptied about twenty arrows into a
 * creeper it never touched, with a block in the way eating every shot.
 */
class MissesTest {

    private final UUID target = UUID.randomUUID();
    private final UUID another = UUID.randomUUID();

    @BeforeEach
    void clean() {
        Misses.forget();
    }

    @Test
    @DisplayName("three arrows that do not hurt it and that target is left alone")
    void threeArrowsThatDoNotHurtIt() {
        for (int i = 0; i < Misses.PATIENCE; i++) {
            assertTrue(Misses.worthIt(target), "arrow " + (i + 1));
            Misses.arrow(target, 20f);
        }
        assertFalse(Misses.worthIt(target));
        assertEquals(Misses.PATIENCE, Misses.arrowsAt(target));
    }

    @Test
    @DisplayName("an arrow that lands starts the count over")
    void arrowThatLandsStartsTheCountOver() {
        Misses.arrow(target, 20f);
        Misses.arrow(target, 20f);
        Misses.arrow(target, 16f);          // this one hurt it
        assertEquals(1, Misses.arrowsAt(target));
        assertTrue(Misses.worthIt(target));
        Misses.arrow(target, 16f);
        Misses.arrow(target, 16f);
        assertFalse(Misses.worthIt(target));
    }

    @Test
    @DisplayName("whoever hurts the bot earns arrows again")
    void whoeverHurtsTheBotEarnsArrowsAgain() {
        Misses.giveUp(target, 20f);
        assertFalse(Misses.worthIt(target));
        Misses.hurtMe(target);
        assertTrue(Misses.worthIt(target));
        assertEquals(0, Misses.arrowsAt(target));
    }

    @Test
    @DisplayName("the count belongs to the target, not to whoever shoots")
    void countBelongsToTheTarget() {
        Misses.giveUp(target, 20f);
        assertTrue(Misses.worthIt(another));
        Misses.hurtMe(another);
        assertFalse(Misses.worthIt(target), "forgiving one must not forgive the other");
    }

    @Test
    @DisplayName("it remembers a bounded number of targets, oldest out first")
    void remembersABoundedNumberOfTargets() {
        for (int i = 0; i < 500; i++) Misses.giveUp(UUID.randomUUID(), 20f);
        assertTrue(Misses.remembered() <= 64, "remembered: " + Misses.remembered());
        // Forgotten means shootable again, which is the safe way round.
        Misses.giveUp(target, 20f);
        assertFalse(Misses.worthIt(target));
    }
}
