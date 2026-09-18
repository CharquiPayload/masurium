package marionette.common;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * What the bot has done, in order, with a fixed ceiling.
 *
 * <p>It was born from a death. A zombie killed the bot while the brain was thinking, and
 * when rebuilding what happened there was <b>nothing</b>: the whole mod had written a
 * single line in the session. The only honest answer to "what was it doing?" was "I do
 * not know, and I cannot know". It is the "did I do it?" problem one floor down: it is
 * not enough for the bot not to lie to itself, it has to leave a trail of what it did.
 *
 * <p><b>Bounded by design</b>, because this produces volume fast. Three brakes:
 * <ul>
 *   <li>it lives in memory and never touches the disk;
 *   <li>a ring of {@value #HOW_MANY} notes: the oldest falls off by itself;
 *   <li>repeats are counted, not stacked: placing thirty bridge blocks is one line with
 *       {@code times:30}, not thirty lines.
 * </ul>
 *
 * <p>And when something falls off the ring <b>it is said</b>, in {@code tossed}: whoever
 * reads knows there was a gap instead of believing nothing happened. A log that loses
 * things silently lies just like a bot that says "done" without doing it.
 *
 * <p>It does not depend on Minecraft on purpose: that way it can really be tested, and
 * {@code /logbook} answers even when the game thread is stuck, which is exactly when it
 * is needed most.
 */
public final class Logbook {

    /** Hard ceiling. At ~120 bytes per note that is about 50 KB, and it does not grow. */
    private static final int HOW_MANY = 400;

    private static final int DEFAULT = 50;
    private static final int CAP = 200;

    /**
     * @param times how many times in a row the same thing happened, with nothing in
     *              between
     */
    public record Memo(long id, long ms, String what, String text, int times) {}

    private static final Deque<Memo> ring = new ArrayDeque<>();
    private static long lastId;
    private static long tosses;

    private Logbook() {}

    /**
     * Notes something that just happened.
     *
     * <p>If it is identical to the previous note it is not noted again: its count goes up
     * and its time is refreshed, keeping the id. That way "placed a block" repeated forty
     * times takes one line and is still true.
     *
     * @param what short category: {@code route}, {@code damage}, {@code death}...
     * @param text plain words, with numbers: "I got stuck" alone is useless
     */
    public static synchronized void note(String what, String text) {
        String t = text == null ? "" : text;
        Memo last = ring.peekLast();
        if (last != null && last.what().equals(what) && last.text().equals(t)) {
            ring.removeLast();
            ring.addLast(new Memo(last.id(), System.currentTimeMillis(),
                                    what, t, last.times() + 1));
            return;
        }
        ring.addLast(new Memo(++lastId, System.currentTimeMillis(), what, t, 1));
        while (ring.size() > HOW_MANY) {
            ring.removeFirst();
            tosses++;
        }
    }

    /**
     * The notes with an id greater than {@code from}; the most recent ones are the last
     * to go if trimming is needed.
     *
     * <p>Trimmed from the start and not from the end on purpose: whoever asks "what just
     * happened?" wants the end of the story. What is left out is counted in {@code
     * omitted_by_limit}, so it does not look as if there was nothing more.
     */
    public static synchronized String json(long from, int limit) {
        int howMany = limit <= 0 ? DEFAULT : Math.min(limit, CAP);
        List<Memo> candidateSet = new ArrayList<>();
        for (Memo n : ring) {
            if (n.id() > from) candidateSet.add(n);
        }
        int cut = Math.max(0, candidateSet.size() - howMany);
        List<Memo> exitAll = candidateSet.subList(cut, candidateSet.size());

        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder();
        for (Memo n : exitAll) {
            if (sb.length() > 0) sb.append(',');
            sb.append(String.format(
                    "{\"id\":%d,\"ago_s\":%d,\"what\":\"%s\",\"text\":\"%s\","
                    + "\"times\":%d}",
                    n.id(), Math.max(0, (now - n.ms()) / 1000),
                    Request.escape(n.what()), Request.escape(n.text()),
                    n.times()));
        }
        Memo firstItem = ring.peekFirst();
        return String.format(
                "{\"ok\":true,\"last\":%d,\"first_saved\":%d,"
                + "\"tossed\":%d,\"omitted_by_limit\":%d,\"notes\":[%s]}",
                lastId, firstItem == null ? 0 : firstItem.id(), tosses, cut, sb);
    }

    /** How many are stored right now. For tests. */
    static synchronized int savedOnes() {
        return ring.size();
    }

    /** Empties the ring. For tests: the state is static. */
    static synchronized void restart() {
        ring.clear();
        lastId = 0;
        tosses = 0;
    }
}
