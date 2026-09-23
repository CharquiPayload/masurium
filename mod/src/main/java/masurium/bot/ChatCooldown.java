package masurium.bot;

/**
 * How often the bot may speak in the chat on its own: once every so many seconds, the
 * launcher's {@code chat_cooldown}, which the bridge passes on ({@code /cooldown}). A bot
 * narrating a fight line by line (a creeper, the silverfish, cornered, down to two
 * hearts) filled the chat in a minute.
 *
 * <p>An answer to someone who has just spoken to it always goes out, and counts: whoever
 * asked is waiting, and a silence would look like a hang. What the brain tries to say
 * too soon is refused, saying so, and it decides whether it still matters later; what
 * the body would say on its own (a creeper, being cornered) is dropped, since the next
 * one comes with the news of then.
 */
public final class ChatCooldown {

    /** Until the bridge says otherwise: the launcher's default. */
    static final long DEFAULT_MS = 10_000;

    private static long cooldownMs = DEFAULT_MS;
    private static long lastMs = Long.MIN_VALUE / 2;

    private ChatCooldown() {
    }

    static synchronized void set(long ms) {
        cooldownMs = Math.max(0, ms);
    }

    static synchronized long cooldownMs() {
        return cooldownMs;
    }

    /** Whether it may speak now and, if so, it is taken as said. An answer always may. */
    static synchronized boolean take(long nowMs, boolean answer) {
        if (!answer && nowMs - lastMs < cooldownMs) return false;
        lastMs = nowMs;
        return true;
    }

    /** It said something some other way (the bridge, through the game's console). */
    static synchronized void spoke(long nowMs) {
        lastMs = nowMs;
    }

    /** How long ago it last spoke. */
    static synchronized long sinceMs(long nowMs) {
        return nowMs - lastMs;
    }

    /** Back to how it starts, for the tests. */
    static synchronized void reset() {
        cooldownMs = DEFAULT_MS;
        lastMs = Long.MIN_VALUE / 2;
    }
}
