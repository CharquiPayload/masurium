package masurium.server;

import masurium.common.Request;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * The latest chat messages, with an incremental id.
 * The id is what lets the agent poll without losing or repeating anything: it asks with
 * {@code from=N} and gets only what was said after that. Reading the chat from the client
 * log by time windows made the messages of one step leak into the next, where they were
 * read as its own failures.
 * It is written from the server thread and read from the HTTP one, so everything is
 * synchronized on this same instance.
 */
final class ChatLog {

    /** Beyond this, the buffer grows without end on a busy server. */
    private final int maximum;
    private final Deque<Message> queue = new ArrayDeque<>();
    private long lastOne = 0;

    record Message(long id, String who, String text) {}

    ChatLog(int maximum) {
        this.maximum = maximum;
    }

    synchronized long add(String who, String text) {
        queue.addLast(new Message(++lastOne, who, text));
        while (queue.size() > maximum) queue.removeFirst();
        return lastOne;
    }

    synchronized long lastOne() {
        return lastOne;
    }

    /** What was said AFTER {@code from}. Empty if there is nothing new. */
    synchronized List<Message> from(long from) {
        List<Message> newItems = new ArrayList<>();
        for (Message m : queue) {
            if (m.id() > from) newItems.add(m);
        }
        return newItems;
    }

    /** The JSON returned by {@code /chat}. */
    synchronized String json(Long from) {
        if (from == null) {
            return String.format("{\"ok\":true,\"last\":%d}", lastOne);
        }
        List<String> rows = new ArrayList<>();
        for (Message m : from(from)) {
            rows.add(String.format(
                    "{\"id\":%d,\"who\":\"%s\",\"text\":\"%s\"}",
                    m.id(), Request.escape(m.who()),
                    Request.escape(m.text())));
        }
        return String.format("{\"ok\":true,\"last\":%d,\"messages\":[%s]}",
                             lastOne, String.join(",", rows));
    }
}
