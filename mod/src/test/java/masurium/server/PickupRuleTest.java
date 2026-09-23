package masurium.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a bot tosses is not picked up again by that same bot. */
class PickupRuleTest {

    @Test
    @DisplayName("the bot list is read with spaces, loose commas and blanks")
    void botListIsReadWithSpacesLoose() {
        assertEquals(Set.of("Alice", "Bob", "Carol"),
                PickupRule.bots(" Alice, Bob,Carol,"));
        assertTrue(PickupRule.bots("").isEmpty());
        assertTrue(PickupRule.bots(null).isEmpty());
    }

    @Test
    @DisplayName("only the bot that tossed it is vetoed; another bot or a person picks it up")
    void onlyBotThatTossedItIsVetoed() {
        Set<String> bots = PickupRule.bots("Alice,Bob");
        assertTrue(PickupRule.veto(bots, "Alice", true));
        assertFalse(PickupRule.veto(bots, "Alice", false));        // someone else tossed it (or it died)
        assertFalse(PickupRule.veto(bots, "Bob", false));     // Alice tossed it to Bob
        assertFalse(PickupRule.veto(bots, "Player1", true));  // a person: their own things
    }
}
