package marionette.bot;

/**
 * How long the bot has been doing nothing.
 *
 * <p>Two things need that answer: the night routine, which must only act when nobody is
 * asking the bot for anything, and the idle notice, which lets the bot offer to do
 * something (such as going out to explore) after a good while without orders.
 *
 * <p>It is marked from two places: every request that is NOT a plain question (looking at
 * the state is not having something to do) and every tick in which the feet move. With
 * that, "idle" means what it looks like: neither orders nor steps.
 */
final class Activity {

    private Activity() {}

    private static volatile long last = System.currentTimeMillis();

    /** Something is happening: the count restarts. */
    static void markPlace() {
        last = System.currentTimeMillis();
    }

    /** Milliseconds since the last sign of life. */
    static long idleMs() {
        return System.currentTimeMillis() - last;
    }
}
