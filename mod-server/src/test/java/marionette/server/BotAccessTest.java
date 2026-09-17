package marionette.server;

import marionette.server.BotAccess.Action;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Who may take a bot out of the game and who it hears. Without Minecraft: the command is
 * a thin layer over this.
 */
class BotAccessTest {

    private static final long NOW = 1_000_000L;

    private static BotAccess withAlice(Path dir) {
        BotAccess a = new BotAccess(dir.resolve("bots.properties"));
        a.report("Alice", "Owner", NOW);
        return a;
    }

    @Test
    @DisplayName("the owner may do everything, an admin everything but managing admins")
    void ownerMayEverythingAdminAllButAdmins(@TempDir Path dir) {
        BotAccess a = withAlice(dir);
        assertNull(a.addAdmin("alice", "Helper"));
        for (Action action : Action.values()) {
            assertTrue(a.may("Alice", "owner", action), "owner: " + action);
        }
        assertTrue(a.may("Alice", "helper", Action.SHUTDOWN));
        assertTrue(a.may("Alice", "Helper", Action.HEAR));
        assertFalse(a.may("Alice", "Helper", Action.ADMINS));
    }

    @Test
    @DisplayName("a stranger, an empty name and an unknown bot may nothing")
    void strangerEmptyAndUnknownMayNothing(@TempDir Path dir) {
        BotAccess a = withAlice(dir);
        for (Action action : Action.values()) {
            assertFalse(a.may("Alice", "Stranger", action));
            assertFalse(a.may("Alice", "", action));
            assertFalse(a.may("Alice", null, action));
            assertFalse(a.may("Nobody", "Owner", action));
        }
    }

    @Test
    @DisplayName("a bot without an owner is commanded by nobody through the lists")
    void botWithoutOwnerIsCommandedByNobody(@TempDir Path dir) {
        BotAccess a = new BotAccess(dir.resolve("bots.properties"));
        a.report("Alice", "", NOW);
        // An empty owner must not match an empty or missing player name.
        assertFalse(a.may("Alice", "", Action.SHUTDOWN));
        assertFalse(a.may("Alice", " ", Action.SHUTDOWN));
    }

    @Test
    @DisplayName("the owner comes from the bot's report, and a new report replaces it")
    void ownerComesFromTheReport(@TempDir Path dir) {
        BotAccess a = withAlice(dir);
        assertEquals("Owner", a.owner("ALICE"));
        a.report("Alice", "Other", NOW + 1);
        assertEquals("Other", a.owner("alice"));
        assertFalse(a.may("Alice", "Owner", Action.SHUTDOWN));
    }

    @Test
    @DisplayName("names that are not Minecraft names are refused")
    void invalidNamesAreRefused(@TempDir Path dir) {
        BotAccess a = withAlice(dir);
        assertNotNull(a.addAdmin("Alice", "bad name"));
        assertNotNull(a.addHear("Alice", "x\",\"admins\":[\"y"));
        assertNotNull(a.addHear("Alice", "waytoolongplayername_17"));
        assertThrows(IllegalArgumentException.class, () -> a.report("A b", "Owner", NOW));
        assertThrows(IllegalArgumentException.class, () -> a.report("Alice", "O\"wner", NOW));
        assertTrue(a.admins("Alice").isEmpty());
    }

    @Test
    @DisplayName("lists refuse duplicates in any case, and the owner as admin")
    void listsRefuseDuplicates(@TempDir Path dir) {
        BotAccess a = withAlice(dir);
        assertNull(a.addHear("Alice", "Friend"));
        assertNotNull(a.addHear("Alice", "FRIEND"));
        assertNotNull(a.addAdmin("Alice", "owner"));
        assertNotNull(a.removeAdmin("Alice", "Nobody"));
        assertNull(a.removeHear("Alice", "friend"));
        assertTrue(a.hearList("Alice").isEmpty());
    }

    @Test
    @DisplayName("everything but the liveness survives a restart of the server")
    void listsSurviveRestart(@TempDir Path dir) {
        BotAccess a = withAlice(dir);
        a.addAdmin("Alice", "Helper");
        a.addHear("Alice", "Friend");
        a.hearOnlyList("Alice", true);

        BotAccess b = new BotAccess(dir.resolve("bots.properties"));
        assertEquals("Owner", b.owner("alice"));
        assertEquals(List.of("Helper"), b.admins("Alice"));
        assertEquals(List.of("Friend"), b.hearList("Alice"));
        assertTrue(b.onlyList("Alice"));
        assertEquals("Alice", b.display("alice"));
        assertFalse(b.alive("Alice", NOW));
    }

    @Test
    @DisplayName("the bridge is alive only while it keeps polling")
    void aliveOnlyWhilePolling(@TempDir Path dir) {
        BotAccess a = withAlice(dir);
        assertTrue(a.alive("Alice", NOW + BotAccess.ALIVE_MS));
        assertFalse(a.alive("Alice", NOW + BotAccess.ALIVE_MS + 1));
        a.declare(List.of("Carol"));
        assertTrue(a.knows("carol"));
        assertFalse(a.alive("Carol", NOW));
    }

    @Test
    @DisplayName("orders reach only their bot, once, and expire")
    void ordersReachTheirBotOnceAndExpire(@TempDir Path dir) {
        BotAccess a = withAlice(dir);
        a.report("Bob", "Owner", NOW);
        long first = a.order("alice", Action.SHUTDOWN, "Owner", NOW);

        String forAlice = a.controlJson("Alice", first - 1, NOW);
        assertTrue(forAlice.contains("\"action\":\"shutdown\""), forAlice);
        assertTrue(forAlice.contains("\"by\":\"Owner\""), forAlice);
        assertFalse(a.controlJson("Bob", first - 1, NOW).contains("shutdown"));
        // Already seen: asking from its id brings nothing.
        assertFalse(a.controlJson("Alice", first, NOW).contains("shutdown"));
        // Without since, only where to start from.
        assertFalse(a.controlJson("Alice", null, NOW).contains("shutdown"));
        assertTrue(a.controlJson("Alice", null, NOW).contains("\"last\":" + first));
        // Too old: the bridge was gone when it was given.
        assertFalse(a.controlJson("Alice", first - 1, NOW + BotAccess.ORDER_TTL_MS + 1)
                .contains("shutdown"));
    }

    @Test
    @DisplayName("order ids keep growing even if the clock goes back")
    void orderIdsKeepGrowing(@TempDir Path dir) {
        BotAccess a = withAlice(dir);
        long one = a.order("Alice", Action.RESTART, "Owner", NOW);
        long two = a.order("Alice", Action.RESTART, "Owner", NOW - 5000);
        assertTrue(two > one);
    }

    @Test
    @DisplayName("the lists go to the bridge as JSON, mode included")
    void listsGoToTheBridge(@TempDir Path dir) {
        BotAccess a = withAlice(dir);
        a.addAdmin("Alice", "Helper");
        a.addHear("Alice", "Friend");
        String everyone = a.accessJson("alice");
        assertTrue(everyone.contains("\"owner\":\"Owner\""), everyone);
        assertTrue(everyone.contains("\"admins\":[\"Helper\"]"), everyone);
        assertTrue(everyone.contains("\"mode\":\"everyone\""), everyone);
        a.hearOnlyList("Alice", true);
        String list = a.accessJson("Alice");
        assertTrue(list.contains("\"mode\":\"list\",\"players\":[\"Friend\"]"), list);
        assertTrue(a.accessJson("Nobody").contains("\"ok\":false"));
        assertEquals(Map.of("Alice", "Owner"), a.owners());
    }
}
