package marionette.bot;

import marionette.common.Logbook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

import java.util.HashMap;
import java.util.Map;

/**
 * The voice: writing in the server chat on the bot's own initiative.
 *
 * <p>Otherwise the bot only speaks when spoken to: the bridge wakes the brain on hearing
 * its name, it answers, and it is quiet until the next time. That works for conversation
 * and not for anything urgent: when phantoms are harassing it or a creeper is coming,
 * there is nobody to ask because nobody has asked. It can, for instance, ask a player for
 * permission to sleep.
 *
 * <p>It lives in the mod and not in the bridge because urgency is measured in ticks: a
 * round trip to the model takes seconds, and by then the creeper has already blown up. It
 * is the same reason the {@link Guard} lives here.
 *
 * <p>Two brakes, because a bot that talks too much is worse than a quiet one: a topic is
 * not repeated until its period passes, and there is a breather between any two
 * sentences. And it never sends COMMANDS: a line starting with a slash would be a server
 * command, not a sentence.
 */
final class Voice {

    private Voice() {}

    /** Vanilla chat cap. Going over it gets cut by the server, not by us. */
    private static final int LENGTH_MAX = 250;
    /** Breather between two sentences in a row: speaking is interrupting. */
    private static final long BETWEEN_PHRASES_MS = 3_000;

    private static final Map<String, Long> lastByTopic = new HashMap<>();
    private static long lastPhrase;

    /**
     * Says something in the chat, if it is time.
     *
     * @param topic which other sentence it competes with ("creeper", "phantom"...)
     * @param everyMs how long must pass before repeating THAT topic
     * @return true if it really spoke, so the logbook does not note what the brake
     *         swallowed
     */
    static synchronized boolean say(String topic, long everyMs, String text) {
        long now = System.currentTimeMillis();
        Long before = lastByTopic.get(topic);
        if (before != null && now - before < everyMs) return false;
        if (now - lastPhrase < BETWEEN_PHRASES_MS) return false;

        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null || p.connection == null) return false;

        String line = text.trim();
        while (line.startsWith("/")) line = line.substring(1).trim();
        if (line.isEmpty()) return false;
        if (line.length() > LENGTH_MAX) line = line.substring(0, LENGTH_MAX);

        p.connection.sendChat(line);
        lastByTopic.put(topic, now);
        lastPhrase = now;
        Logbook.note("voice", "saying: " + line);
        return true;
    }

    /**
     * What the bot is called in here, to ask to be answered by name: the bridge only
     * passes to the brain what carries its name.
     */
    static String myName(LocalPlayer p) {
        return p.getGameProfile().getName();
    }
}
