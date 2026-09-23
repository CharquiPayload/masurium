package masurium.server;

import java.util.HashSet;
import java.util.Set;

/**
 * The rule "what a bot tosses is not picked up again by that same bot", without
 * Minecraft, so it can be tested in milliseconds.
 *
 * <p>A bot once tossed 768 cobblestone as asked, kept walking right next to the pile, and
 * three minutes later carried 704 again; then it said it had none left with a backpack
 * full of them. The game picks items up just by stepping on them, after a short delay,
 * and the client cannot refuse: only the server can. The pickup is vetoed when whoever
 * steps on it is a bot AND is the one that tossed it; anyone else, person or bot, picks
 * it up as usual, since that is what it was tossed for. It holds for the whole life of
 * the item (5 min): a bot that wants something of its own back must ask someone. What a
 * bot drops on DEATH carries no thrower, so that it does pick back up.
 */
final class PickupRule {

    private PickupRule() {}

    /** "Alice, Bob,Carol," -> {Alice, Bob, Carol}. Empty or null: nobody. */
    static Set<String> bots(String list) {
        Set<String> r = new HashSet<>();
        if (list == null) return r;
        for (String n : list.split(",")) {
            n = n.trim();
            if (!n.isEmpty()) r.add(n);
        }
        return r;
    }

    /** @return true if the pickup must be prevented */
    static boolean veto(Set<String> bots, String player, boolean droppedItHimself) {
        return droppedItHimself && bots.contains(player);
    }
}
