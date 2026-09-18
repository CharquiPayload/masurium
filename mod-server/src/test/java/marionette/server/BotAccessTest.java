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

    @Test
    @DisplayName("a setting is stored, travels as an order and refuses a key that does not exist")
    void settingsTravelAsOrders(@TempDir Path dir) {
        BotAccess a = withAlice(dir);
        assertNull(a.pref("alice", "BUNNY_HOP", true, "Owner", NOW));
        assertEquals(Map.of("bunny_hop", true), a.prefs("Alice"));
        String json = a.controlJson("Alice", NOW - 1, NOW);
        assertTrue(json.contains("\"action\":\"pref\""), json);
        assertTrue(json.contains("\"argument\":\"bunny_hop=true\""), json);
        // A typo must not become a setting that reads as saved and governs nothing.
        assertNotNull(a.pref("Alice", "buny_hop", true, "Owner", NOW));
        assertEquals(1, a.prefs("Alice").size());
        assertNotNull(a.pref("Nobody", "bunny_hop", true, "Owner", NOW));
    }

    @Test
    @DisplayName("banning a food and allowing it back are two sides of one list")
    void banningAndAllowingFood(@TempDir Path dir) {
        BotAccess a = withAlice(dir);
        assertNull(a.food("Alice", "Rotten_Flesh", true, "Owner", NOW));
        assertEquals(List.of("rotten_flesh"), a.foodList("Alice", true));
        // The same decision twice says so instead of piling up.
        assertNotNull(a.food("Alice", "rotten_flesh", true, "Owner", NOW));
        // The opposite decision REPLACES it: it cannot be banned and allowed at once.
        assertNull(a.food("Alice", "rotten_flesh", false, "Owner", NOW));
        assertEquals(List.of(), a.foodList("Alice", true));
        assertEquals(List.of("rotten_flesh"), a.foodList("Alice", false));
        // Player names are not item ids, and neither are namespaces.
        assertNotNull(a.food("Alice", "minecraft:dirt", true, "Owner", NOW));
        assertNotNull(a.food("Alice", "", true, "Owner", NOW));
    }

    @Test
    @DisplayName("the break list works the same way, with its own verbs")
    void breakListWorksTheSameWay(@TempDir Path dir) {
        BotAccess a = withAlice(dir);
        assertNull(a.breaking("Alice", "dirt", true, "Owner", NOW));
        assertEquals(List.of("dirt"), a.breakList("Alice", true));
        assertNull(a.breaking("Alice", "dirt", false, "Owner", NOW));
        assertEquals(List.of("dirt"), a.breakList("Alice", false));
        assertEquals(List.of(), a.breakList("Alice", true));
        String json = a.controlJson("Alice", NOW - 1, NOW);
        assertTrue(json.contains("\"argument\":\"forbid:dirt\""), json);
    }

    @Test
    @DisplayName("settings survive a restart and ride in EVERY answer, not as orders")
    void settingsSurviveAndRideInTheAnswer(@TempDir Path dir) {
        Path file = dir.resolve("bots.properties");
        BotAccess a = new BotAccess(file);
        a.report("Alice", "Owner", NOW);
        a.pref("Alice", "bunny_hop", true, "Owner", NOW);
        a.food("Alice", "salmon", true, "Owner", NOW);
        a.breaking("Alice", "dirt", true, "Owner", NOW);

        // Read back from the file by a brand new instance, as after a restart.
        BotAccess back = new BotAccess(file);
        assertEquals(Map.of("bunny_hop", true), back.prefs("Alice"));
        assertEquals(List.of("salmon"), back.foodList("Alice", true));
        assertEquals(List.of("dirt"), back.breakList("Alice", true));

        // THE BUG THIS TEST EXISTS FOR. The settings were queued as orders when a
        // bridge reported fresh, and a live test showed the bridge never carried one
        // out: report() runs in the same request that answers "start from id N", and N
        // was already past the orders just queued. So they ride in the answer instead,
        // in EVERY answer, including the first one a bridge makes with no `since`.
        long later = NOW + BotAccess.ALIVE_MS + 1;
        back.report("Alice", "Owner", later);
        String first = back.controlJson("Alice", null, later);
        assertTrue(first.contains("\"settings\":"), first);
        assertTrue(first.contains("\"bunny_hop\":true"), first);
        assertTrue(first.contains("\"ban\":[\"salmon\"]"), first);
        assertTrue(first.contains("\"allow\":[\"dirt\"]"), first);
        // And no orders are invented for them: a fresh bridge must not be handed a
        // queue it cannot see.
        assertFalse(first.contains("\"action\":\"pref\""), first);
        assertFalse(back.controlJson("Alice", later - 1, later).contains("bunny_hop=true"));
    }

    @Test
    @DisplayName("a bot with nothing set says so, instead of leaving the field out")
    void nothingSetIsStillAnAnswer(@TempDir Path dir) {
        BotAccess a = withAlice(dir);
        String json = a.controlJson("Alice", null, NOW);
        assertTrue(json.contains("\"settings\":{\"prefs\":{}"), json);
        assertTrue(json.contains("\"ban\":[]"), json);
    }

    @Test
    @DisplayName("a setting whose key disappeared from the code is dropped on load")
    void unknownSettingIsDroppedOnLoad(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("bots.properties");
        java.nio.file.Files.write(file, List.of(
                "alice.name=Alice",
                "alice.owner=Owner",
                "alice.pref.bunny_hop=true",
                "alice.pref.fly_to_the_moon=true"));
        BotAccess a = new BotAccess(file);
        assertEquals(Map.of("bunny_hop", true), a.prefs("Alice"));
    }
}
