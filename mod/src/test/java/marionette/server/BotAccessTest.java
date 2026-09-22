package marionette.server;

import marionette.server.BotAccess.Action;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
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
        BotAccess a = new BotAccess(dir.resolve("bots.json"));
        a.report("Alice", "Owner", "1.0.0", NOW);
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
        BotAccess a = new BotAccess(dir.resolve("bots.json"));
        a.report("Alice", "", "1.0.0", NOW);
        // An empty owner must not match an empty or missing player name.
        assertFalse(a.may("Alice", "", Action.SHUTDOWN));
        assertFalse(a.may("Alice", " ", Action.SHUTDOWN));
    }

    @Test
    @DisplayName("the owner comes from the bot's report, and a new report replaces it")
    void ownerComesFromTheReport(@TempDir Path dir) {
        BotAccess a = withAlice(dir);
        assertEquals("Owner", a.owner("ALICE"));
        a.report("Alice", "Other", "1.0.0", NOW + 1);
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
        assertThrows(IllegalArgumentException.class, () -> a.report("A b", "Owner", "1.0.0", NOW));
        assertThrows(IllegalArgumentException.class, () -> a.report("Alice", "O\"wner", "1.0.0", NOW));
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

        BotAccess b = new BotAccess(dir.resolve("bots.json"));
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
        a.report("Bob", "Owner", "1.0.0", NOW);
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
        assertNull(a.pref("alice", "HUNT_PLAYERS", true, "Owner", NOW));
        assertEquals(Map.of("hunt_players", true), a.prefs("Alice"));
        String json = a.controlJson("Alice", NOW - 1, NOW);
        assertTrue(json.contains("\"action\":\"pref\""), json);
        assertTrue(json.contains("\"argument\":\"hunt_players=true\""), json);
        // A typo must not become a setting that reads as saved and governs nothing.
        assertNotNull(a.pref("Alice", "hunt_playerz", true, "Owner", NOW));
        assertEquals(1, a.prefs("Alice").size());
        assertNotNull(a.pref("Nobody", "hunt_players", true, "Owner", NOW));
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
        // Player names are not item ids, and only the game's own namespace is: it is
        // the only one the body resolves, so it is taken off and any other refused.
        assertNotNull(a.food("Alice", "create:gear", true, "Owner", NOW));
        assertNotNull(a.food("Alice", "", true, "Owner", NOW));
        assertNull(a.food("Alice", "minecraft:beef", true, "Owner", NOW));
        assertEquals(List.of("beef"), a.foodList("Alice", true));
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
        Path file = dir.resolve("bots.json");
        BotAccess a = new BotAccess(file);
        a.report("Alice", "Owner", "1.0.0", NOW);
        a.pref("Alice", "hunt_players", true, "Owner", NOW);
        a.food("Alice", "salmon", true, "Owner", NOW);
        a.breaking("Alice", "oak_log", true, "Owner", NOW);

        // Read back from the file by a brand new instance, as after a restart.
        BotAccess back = new BotAccess(file);
        assertEquals(Map.of("hunt_players", true), back.prefs("Alice"));
        assertEquals(List.of("salmon"), back.foodList("Alice", true));
        assertEquals(List.of("oak_log"), back.breakList("Alice", true));

        // THE BUG THIS TEST EXISTS FOR. The settings were queued as orders when a
        // bridge reported fresh, and a live test showed the bridge never carried one
        // out: report() runs in the same request that answers "start from id N", and N
        // was already past the orders just queued. So they ride in the answer instead,
        // in EVERY answer, including the first one a bridge makes with no `since`.
        long later = NOW + BotAccess.ALIVE_MS + 1;
        back.report("Alice", "Owner", "1.0.0", later);
        String first = back.controlJson("Alice", null, later);
        assertTrue(first.contains("\"settings\":"), first);
        assertTrue(first.contains("\"hunt_players\":true"), first);
        assertTrue(first.contains("\"ban\":[\"salmon\"]"), first);
        assertTrue(first.contains("\"allow\":[\"oak_log\"]"), first);
        // And no orders are invented for them: a fresh bridge must not be handed a
        // queue it cannot see.
        assertFalse(first.contains("\"action\":\"pref\""), first);
        assertFalse(back.controlJson("Alice", later - 1, later).contains("hunt_players=true"));
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
    @DisplayName("the old properties file becomes the bots' own layer, once, and is set aside")
    void oldPropertiesAreMovedIn(@TempDir Path dir) throws Exception {
        Path old = dir.resolve("bots.properties");
        Files.write(old, List.of(
                "alice.name=Alice",
                "alice.owner=Owner",
                "alice.admins=Helper",
                "alice.hear.mode=list",
                "alice.hear.players=Friend",
                "alice.pref.hunt_players=true",
                // A setting whose key disappeared from the code is dropped on the way.
                "alice.pref.fly_to_the_moon=true",
                "alice.food.ban=salmon",
                "alice.food.allow=golden_apple",
                "alice.break.allow=oak_log",
                "alice.break.forbid=dirt"));
        BotAccess a = new BotAccess(dir.resolve("bots.json"));
        assertEquals("Owner", a.owner("alice"));
        assertEquals(List.of("Helper"), a.admins("Alice"));
        assertTrue(a.onlyList("Alice"));
        assertEquals(Map.of("hunt_players", true), a.prefs("Alice"));
        assertEquals(List.of("salmon"), a.foodList("Alice", true));
        assertEquals(List.of("golden_apple"), a.foodList("Alice", false));
        assertEquals(List.of("dirt"), a.breakList("Alice", false));
        assertTrue(Files.exists(dir.resolve("bots.json")));
        assertFalse(Files.exists(old));
        assertTrue(Files.exists(dir.resolve("bots.properties.migrated")));
        // And from then on it is the JSON that is read.
        BotAccess again = new BotAccess(dir.resolve("bots.json"));
        assertEquals(List.of("oak_log"), again.breakList("Alice", true));
    }

    @Test
    @DisplayName("a file that cannot be read is copied aside before anything writes over it")
    void brokenFileIsKept(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("bots.json");
        Files.writeString(file, "{\"bots\": {\"alice\": {\"name\": \"Alice\",}}");
        BotAccess a = new BotAccess(file);
        assertFalse(a.knows("Alice"));
        try (var listing = Files.list(dir)) {
            assertTrue(listing.anyMatch(f -> f.getFileName().toString().startsWith("bots.json.broken-")));
        }
    }

    // ------------------------------------------------------------------ layers

    private static final String IMPOSED = "{\"prefs\":{\"hunt_players\":false},"
            + "\"food\":{\"ban\":[\"beef\"]},\"from\":{\"prefs.hunt_players\":\"global\","
            + "\"food.beef\":\"group team\"}}";

    @Test
    @DisplayName("what is imposed cannot be changed from the game, and it says who imposes it")
    void imposedIsRefused(@TempDir Path dir) {
        BotAccess a = withAlice(dir);
        assertNull(a.setLayer("Alice", "imposed", IMPOSED));
        String no = a.pref("Alice", "hunt_players", true, "Owner", NOW);
        assertNotNull(no);
        assertTrue(no.contains("imposed by global"), no);
        no = a.food("Alice", "beef", false, "Owner", NOW);
        assertTrue(no != null && no.contains("imposed by group team"), no);
        // What is not imposed is still the owner's to change.
        assertNull(a.food("Alice", "salmon", true, "Owner", NOW));
        assertNull(a.pref("Alice", "tame_wolves", false, "Owner", NOW));
        // A list imposed whole refuses every id of it.
        assertNull(a.setLayer("Alice", "imposed", "{\"break\":{\"replace\":true},"
                + "\"from\":{\"break.*\":\"global\"}}"));
        no = a.breaking("Alice", "dirt", true, "Owner", NOW);
        assertTrue(no != null && no.contains("whole break list is imposed by global"), no);
    }

    @Test
    @DisplayName("default takes a thing out of the own layer: what is under it applies again")
    void defaultGoesBackToWhatIsUnder(@TempDir Path dir) {
        BotAccess a = withAlice(dir);
        assertNull(a.setLayer("Alice", "base", "{\"food\":{\"ban\":[\"cod\"]}}"));
        assertNull(a.food("Alice", "cod", false, "Owner", NOW));
        assertFalse(a.rules("Alice").effective().food().contains("cod"));
        assertNull(a.food("Alice", "cod", null, "Owner", NOW));
        assertTrue(a.rules("Alice").effective().food().contains("cod"));
        assertNotNull(a.food("Alice", "cod", null, "Owner", NOW));
        assertNull(a.pref("Alice", "hunt_players", true, "Owner", NOW));
        assertNull(a.pref("Alice", "hunt_players", null, "Owner", NOW));
        assertEquals(Map.of(), a.prefs("Alice"));
        assertNotNull(a.pref("Alice", "hunt_players", null, "Owner", NOW));
    }

    @Test
    @DisplayName("the rules ride in every answer, whole, the starting lists included")
    void rulesRideInTheAnswer(@TempDir Path dir) {
        BotAccess a = withAlice(dir);
        assertNull(a.setLayer("Alice", "base", "{\"food\":{\"ban\":[\"beef\"]}}"));
        String json = a.controlJson("Alice", null, NOW);
        assertTrue(json.contains("\"rules\":{\"prefs\":{"), json);
        assertTrue(json.contains("\"food_banned\":[\"beef\",\"enchanted_golden_apple\",\"golden_apple\"]"),
                json);
        assertTrue(json.contains("\"break_allowed\":[\"cobblestone\",\"dirt\",\"grass_block\",\"stone\"]"),
                json);
        // The older shape carries the same, as changes from the start.
        assertTrue(json.contains("\"food\":{\"ban\":[\"beef\"],\"allow\":[]}"), json);
    }

    @Test
    @DisplayName("the launcher sends whole layers, even for a bot not seen yet, and bad ones are refused")
    void layersFromTheLauncher(@TempDir Path dir) {
        BotAccess a = new BotAccess(dir.resolve("bots.json"));
        assertNull(a.setLayer("Carol", "base", "{\"prefs\":{\"tame_wolves\":false}}"));
        assertTrue(a.knows("carol"));
        assertFalse(a.rules("Carol").effective().prefs().get("tame_wolves"));
        assertNotNull(a.setLayer("Carol", "global", "{}"));
        assertNotNull(a.setLayer("Carol", "base", "{\"prefs\":{\"tame_wolvez\":false}}"));
        assertNotNull(a.setLayer("Carol", "base", "not json"));
        assertNotNull(a.setLayer("not a name", "base", "{}"));
        // A layer replaced whole: what it had and the new one does not, goes.
        assertNull(a.setLayer("Carol", "base", "{}"));
        assertTrue(a.rules("Carol").effective().prefs().get("tame_wolves"));
        String json = a.rulesJson("carol");
        assertTrue(json.startsWith("{\"ok\":true,\"bot\":\"Carol\",\"base\":{},\"own\":{},\"imposed\":{},"
                + "\"effective\":{"), json);
        assertTrue(a.rulesJson("Nobody").contains("\"ok\":false"));
        // And they survive a restart.
        a.setLayer("Carol", "imposed", IMPOSED);
        assertEquals(a.rulesJson("Carol"), new BotAccess(dir.resolve("bots.json")).rulesJson("Carol"));
    }

    @Test
    @DisplayName("the launcher edits the own layer a change at a time, as a command would")
    void ownEditsFromTheLauncher(@TempDir Path dir) {
        BotAccess a = withAlice(dir);
        assertNull(a.editOwn("Alice", "pref", "hunt_players", "on", "the launcher", NOW));
        assertNull(a.editOwn("Alice", "food", "beef", "ban", "the launcher", NOW));
        assertNull(a.editOwn("Alice", "break", "dirt", "forbid", "the launcher", NOW));
        assertNull(a.editOwn("Alice", "break", "*", "replace", "the launcher", NOW));
        Rules r = a.rules("Alice");
        assertTrue(r.effective().prefs().get("hunt_players"));
        assertTrue(r.effective().food().contains("beef"));
        assertEquals(List.of(), List.copyOf(r.effective().breaking()));
        assertNull(a.editOwn("Alice", "break", "*", "add", "the launcher", NOW));
        assertNull(a.editOwn("Alice", "pref", "hunt_players", "default", "the launcher", NOW));
        assertEquals(Map.of(), a.prefs("Alice"));
        assertNotNull(a.editOwn("Alice", "food", "beef", "forbid", "the launcher", NOW));
        assertNotNull(a.editOwn("Alice", "mood", "x", "on", "the launcher", NOW));
        assertNotNull(a.editOwn("Nobody", "food", "beef", "ban", "the launcher", NOW));
        assertThrows(IllegalArgumentException.class,
                () -> a.editOwn("Alice", "pref", "hunt_players", "maybe", "the launcher", NOW));
        // The older bridges still get the change as an order, as it came out.
        String json = a.controlJson("Alice", NOW - 1, NOW);
        assertTrue(json.contains("\"argument\":\"ban:beef\""), json);
    }

    @Test
    @DisplayName("a bot on another version is reported to the console, once")
    void aDifferentVersionWarnsOnce(@TempDir Path dir) {
        BotAccess a = new BotAccess(dir.resolve("bots.json"));
        a.serverVersion("1.1.0");
        // A bot is a SEPARATE installation: unifying the two jars stops them drifting
        // on one machine, not a bot joining a server built from another version.
        String said = a.report("Alice", "Owner", "1.0.0", NOW);
        assertNotNull(said);
        assertTrue(said.contains("1.0.0") && said.contains("1.1.0"), said);
        // Once, not on every poll: this is a one-second loop.
        assertNull(a.report("Alice", "Owner", "1.0.0", NOW + 1000));
        assertNull(a.report("Alice", "Owner", "1.0.0", NOW + 2000));
        // A different mismatch is news again.
        assertNotNull(a.report("Alice", "Owner", "0.9.0", NOW + 3000));
        assertEquals("0.9.0", a.version("Alice"));
    }

    @Test
    @DisplayName("agreeing, or having nothing to compare, says nothing at all")
    void agreementAndSilenceAreNotWarnings(@TempDir Path dir) {
        BotAccess a = new BotAccess(dir.resolve("bots.json"));
        a.serverVersion("1.1.0");
        assertNull(a.report("Alice", "Owner", "1.1.0", NOW));
        // An older bridge reports no version. Nothing to compare is NOT disagreeing,
        // and warning about it would cry wolf on every older bot that joins.
        assertNull(a.report("Bob", "Owner", "", NOW));
        assertNull(a.report("Carol", "Owner", null, NOW));
        assertEquals("", a.version("Bob"));
        // And a server that cannot read its own version warns about nobody.
        BotAccess mute = new BotAccess(dir.resolve("other.properties"));
        assertNull(mute.report("Alice", "Owner", "1.0.0", NOW));
    }
}
