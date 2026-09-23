package masurium.bot;

/**
 * Whether its brain is thinking an answer right now. The body cannot know it: the
 * brain is another program, run by the bridge, which says when a turn starts and when
 * it ends ({@code /thinking}). Add-ons read it to show it, as a player typing is shown.
 *
 * <p>Forgotten on its own after two minutes: a bridge that died mid-turn would never
 * say the turn ended, and a bot that looks like it is typing forever is worse than one
 * that stops looking so.
 */
public final class Thinking {

    static final long FORGOTTEN_AFTER_MS = 120_000;
    private static volatile long until;

    private Thinking() {
    }

    static void set(boolean on, long nowMs) {
        until = on ? nowMs + FORGOTTEN_AFTER_MS : 0;
    }

    static boolean at(long nowMs) {
        return nowMs < until;
    }

    /** Whether the brain is thinking now. */
    public static boolean now() {
        return at(System.currentTimeMillis());
    }
}
