package masurium.server;

import masurium.common.Request;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests of what does not need Minecraft. They run in milliseconds with {@code ./gradlew
 * test}, without starting a server or joining a world.
 * Each one defends something that really broke in an earlier version.
 */
class ServerTest {

    // --- reading the URL query ----------------------------------------------

    @Test
    @DisplayName("the query is parsed, and the decoded text comes back whole")
    void queryIsParsedAndDecodedTextComes() {
        Map<String, String> q = Request.query("block=oak_log&radius=32");
        assertEquals("oak_log", q.get("block"));
        assertEquals("32", q.get("radius"));

        assertEquals("odd world", Request.query("m=odd%20world").get("m"));
        assertTrue(Request.query(null).isEmpty());
        assertTrue(Request.query("").isEmpty());
    }

    @Test
    @DisplayName("a parameter without '=' is ignored instead of breaking")
    void parameterWithoutIsIgnoredInsteadOfBreaking() {
        Map<String, String> q = Request.query("loose&block=stone&=empty");
        assertEquals(Map.of("block", "stone"), q);
    }

    @Test
    @DisplayName("/mods: sorted by id and escaped, so two servers' answers compare as text")
    void modsAreListedSortedAndEscaped() {
        String json = ModsJson.of(
                Map.of("sable", "2.0.5", "create", "6.0.10", "odd\"id", "1"));
        assertEquals("{\"ok\":true,\"mods\":[{\"id\":\"create\",\"version\":\"6.0.10\"},"
                + "{\"id\":\"odd\\\"id\",\"version\":\"1\"},{\"id\":\"sable\",\"version\":\"2.0.5\"}]}",
                json);
    }

    @Test
    @DisplayName("negative and decimal coordinates are truncated downwards")
    void negativeAndDecimalCoordinatesAreTruncatedDownwards() {
        // The model often sends decimals, and half the world has a negative X.
        Map<String, String> q = Map.of("x", "-850.9", "y", "64", "z", " 922 ");
        assertEquals(-851, Request.whole(q, "x"));
        assertEquals(64, Request.whole(q, "y"));
        assertEquals(922, Request.whole(q, "z"));
    }

    @Test
    @DisplayName("a missing or non-numeric parameter complains, it is not 0")
    void missingOrNonNumericParameterComplainsIt() {
        // A silent 0 would send the bot to the other side of the world.
        assertThrows(IllegalArgumentException.class,
                () -> Request.whole(Map.of(), "x"));
        assertThrows(IllegalArgumentException.class,
                () -> Request.whole(Map.of("x", "hello"), "x"));
        assertThrows(IllegalArgumentException.class,
                () -> Request.whole(Map.of("x", ""), "x"));
    }

    // --- escaping for JSON --------------------------------------------------

    @Test
    @DisplayName("quotes and slashes are escaped: a chat cannot break the JSON")
    void quotesAndSlashesAreEscapedChatCannot() {
        assertEquals("said \\\"hello\\\"", Request.escape("said \"hello\""));
        assertEquals("c:\\\\path", Request.escape("c:\\path"));
        assertEquals("one line another", Request.escape("one line\nanother"));
        assertEquals("", Request.escape(null));
    }

    @Test
    @DisplayName("a malicious message does not escape its JSON field")
    void maliciousMessageDoesNotEscapeItsJson() {
        // Someone typing this in the chat cannot inject new fields.
        ChatLog r = new ChatLog(10);
        r.add("Player2", "\",\"admin\":true,\"x\":\"");
        String json = r.json(0L);

        assertTrue(json.contains("\\\",\\\"admin"), json);
        // If escaping failed, more than one key would appear per message.
        assertEquals(1, json.split("\"who\"", -1).length - 1, json);
        assertEquals(1, json.split("\"text\"", -1).length - 1, json);
    }

    // --- the chat log -------------------------------------------------------

    @Test
    @DisplayName("since(N) returns only what is new, without losing or repeating")
    void sinceNReturnsOnlyWhatIsNew() {
        ChatLog r = new ChatLog(200);
        r.add("Player1", "hello");
        r.add("Player1", "bot come here");

        assertEquals(2, r.from(0).size());
        assertEquals(1, r.from(1).size());
        assertEquals("bot come here", r.from(1).get(0).text());
        assertTrue(r.from(2).isEmpty());

        // What arrives after emptying does show up.
        r.add("Player2", "hi");
        assertEquals(1, r.from(2).size());
        assertEquals(3, r.lastOne());
    }

    @Test
    @DisplayName("the buffer is trimmed, but the ids keep growing")
    void bufferIsTrimmedButIdsKeepGrowing() {
        ChatLog r = new ChatLog(3);
        for (int i = 1; i <= 5; i++) r.add("someone", "msg" + i);

        List<ChatLog.Message> everyone = r.from(0);
        assertEquals(3, everyone.size(), "only the last 3 are kept");
        assertEquals("msg3", everyone.get(0).text());
        assertEquals(5, r.lastOne(), "the id does not reset on trimming");
        // Key: asking from an already forgotten id neither blows up nor repeats.
        assertEquals(2, r.from(3).size());
    }

    @Test
    @DisplayName("without 'since' only the last id is reported")
    void withoutSinceOnlyLastIdIsReported() {
        ChatLog r = new ChatLog(10);
        r.add("Player1", "hello");
        assertEquals("{\"ok\":true,\"last\":1}", r.json(null));
    }
}
