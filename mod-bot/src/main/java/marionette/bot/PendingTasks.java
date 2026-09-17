package marionette.bot;

import marionette.common.Request;

import java.util.ArrayList;
import java.util.List;

/**
 * What the bot said it was going to do, so something wakes it up for it.
 *
 * <p>It comes from a real failure: the bot said it was going to cook and never went. That
 * was not the brain being careless but the shape of the system: <b>a turn ends when the
 * brain answers</b>. An answer saying "going to the furnace" is an intention with nobody
 * to carry it on; nothing calls the brain again until someone talks to it.
 *
 * <p>This gives it the missing piece: it can leave itself a reminder. The bridge picks it
 * up when it is due, through the same channel as the body's notices, and the brain wakes
 * up and carries on.
 *
 * <p>It does not replace the rule of doing things within the turn: if it fits, it gets
 * done, full stop. This is for what does not fit (the slow furnace, the long fill job)
 * and for what has to be checked later.
 *
 * <p>In memory on purpose: a reminder that survives a restart belongs to another world,
 * another situation and probably another day. On startup the agenda is clean, and that is
 * the honest thing.
 */
final class PendingTasks {

    private PendingTasks() {}

    /** No instant reminders: that is doing it now, not remembering it. */
    private static final int MIN_S = 15;
    /** Nor for tomorrow: beyond half an hour, whoever wants it can ask for it. */
    private static final int MAX_S = 30 * 60;
    /** Cap on reminders at once, so it does not turn into a loop. */
    private static final int MAX = 8;

    private record Dispatch(int id, long when, String what) {}

    private static final List<Dispatch> schedule = new ArrayList<>();
    private static int lastId;

    /** @return null if it was noted, or the reason */
    static synchronized String note(int seconds, String what) {
        if (what == null || what.isBlank()) return "an empty reminder reminds of nothing";
        if (schedule.size() >= MAX) {
            return "I already have " + MAX + " pending reminders; first finish "
                   + "one";
        }
        int s = Math.max(MIN_S, Math.min(MAX_S, seconds));
        schedule.add(new Dispatch(++lastId,
                System.currentTimeMillis() + s * 1000L, what.strip()));
        marionette.common.Logbook.note("pending",
                String.format("I remind myself in %ds: %s", s, what.strip()));
        return null;
    }

    /** The ones already due are sent as body notices and deleted. */
    static synchronized void tick() {
        if (schedule.isEmpty()) return;
        long now = System.currentTimeMillis();
        schedule.removeIf(r -> {
            if (r.when() > now) return false;
            // With the id in the key: two equal reminders are two notices, not one
            // swallowed by the cooldown.
            Needs.warn("pending:" + r.id(),
                    "I left myself this note: " + r.what());
            return true;
        });
    }

    static synchronized String asJson() {
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"pending_tasks\":[");
        boolean firstItem = true;
        for (Dispatch r : schedule) {
            if (!firstItem) sb.append(',');
            firstItem = false;
            sb.append(String.format("{\"in_seconds\":%d,\"what\":\"%s\"}",
                    Math.max(0, (r.when() - now) / 1000),
                    Request.escape(r.what())));
        }
        return sb.append("]}").toString();
    }
}
