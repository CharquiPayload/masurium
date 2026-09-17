package marionette.bot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * The bot's behaviour preferences, in a file its own AI manages through the chat: asking
 * the AI is enough for it to toggle them.
 *
 * <p>It is a file SEPARATE from the permissions on purpose: the whitelist is SAFETY (what
 * it may touch) and this is TASTE (how it behaves). Mixing them would invite a "change
 * how you follow me" to end up touching what it may break.
 *
 * <p>Only KNOWN keys: a typo does not create ghost preferences that look saved and do
 * nothing. For example:
 * <ul>
 *   <li>{@code build_while_following}: when following a player, whether it may place
 *       blocks (tower, bridge) to reach them ({@code true}) or waits where the path ends
 *       ({@code false}, the conservative choice). The cost of true is known and accepted:
 *       scaffolding pillars stay where they are.</li>
 * </ul>
 */
final class Preferences {

    private Preferences() {}

    /** Per server, like every toggle. */
    private static Path file() {
        return ServerIdentity.file("preferences");
    }

    /** The keys that exist, with their default value. */
    private static final TreeMap<String, Boolean> DEFAULTS = new TreeMap<>();
    static {
        // true by default: it should know it may build to move. The high cost of bridges
        // and towers in the Route table is the real brake; the preference stays to forbid
        // it through the chat.
        DEFAULTS.put("build_while_following", true);
        // With this set to true, the search may go through WHITELISTED blocks by paying
        // for the digging (expensive: it only tunnels when there is no way on foot).
        // false by default on purpose: breaking destroys other people's world; building
        // only adds.
        DEFAULTS.put("break_to_advance", false);
        // Whether hunting may target PLAYERS. It was born as an absolute lock in the code
        // and became a toggle: the owner's bot, the owner's world, the owner's explicit
        // decision. false by default is the safe state, and the brain only accepts
        // changing it from the owner.
        DEFAULTS.put("hunt_players", false);
        // Automatically shooting creepers in range with a bow. true by default because it
        // is DEFENSE, not hunting: the creeper nobody sees coming is one of the two usual
        // causes of death. Turning it off leaves only fleeing.
        DEFAULTS.put("shoot_creepers", true);
        // Bunny hopping goes faster on long trips, but running in jumps spends half again
        // as much food per block, and there are times (tight pantry, terrain full of
        // holes, lag, or simply wanting to watch the bot walk without bouncing) when it
        // does not pay off. Off by default; whoever wants it turns it on as a preference.
        DEFAULTS.put("bunny_hop", false);
        // Taming wild wolves on its own when it sees them and carries bones.
        DEFAULTS.put("tame_wolves", true);
        // It puts on armor by itself if it carries something better.
        DEFAULTS.put("dress_alone", true);
        // Harvesting and resowing its own farms by itself.
        DEFAULTS.put("harvest_alone", true);
        // Going to bed by itself when phantoms prowl at night.
        DEFAULTS.put("sleep_alone", true);
        // Torches only while mining or when a player asks, never all over the overworld.
        // The "only while mining" is not guaranteed by this preference but by WHO checks
        // it: the Miner, and nobody else. This is the switch to turn it off entirely.
        DEFAULTS.put("torches_while_mining", true);
        // Staying close to light or home when it has no errand, instead of standing in
        // the open field. It is one of the few things it does on its own initiative
        // without being asked, so it has its switch, but true by default, because the
        // alternative is spending the night outdoors surrounded by mobs.
        DEFAULTS.put("night_routine", true);
        // Otherwise the Miner and the FillWorker kept digging with two hearts until
        // something finished them off. true by default: it is survival, not taste, and
        // whoever wants to watch it die working has to turn it off by hand.
        DEFAULTS.put("retreat_when_hurt", true);
        // Bots go for their items automatically after dying. Without keepInventory
        // dropped items last five minutes, and the brain does not wake up in time. true
        // by default: it is not losing the gear, not taste. See ItemRecovery.
        DEFAULTS.put("recover_on_death", true);
        // After taming or breeding, one pass standing up its own pets that were left
        // sitting. true by default: a sitting pet neither follows nor fights.
        DEFAULTS.put("stand_when_done", true);
        // While escorting, it answers EVERYTHING that hurts whoever it escorts, like a
        // wolf, except PLAYERS, which go with this toggle: false by default, like
        // hunt_players, and for the same reason (hitting a person is the owner's
        // decision).
        DEFAULTS.put("defend_from_players", false);
    }

    private static TreeMap<String, Boolean> valueList;

    private static synchronized TreeMap<String, Boolean> load() {
        if (valueList != null) return valueList;
        valueList = new TreeMap<>(DEFAULTS);
        try {
            if (Files.exists(file())) {
                for (String line : Files.readAllLines(file())) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int i = line.indexOf('=');
                    if (i < 0) continue;
                    String k = line.substring(0, i).trim();
                    // Unknown keys are ignored without removing them from the map in
                    // memory: if an old version wrote something, it is not lost on
                    // saving... but it does not govern anything either.
                    if (DEFAULTS.containsKey(k)) {
                        valueList.put(k, Boolean.parseBoolean(
                                line.substring(i + 1).trim()));
                    }
                }
            } else {
                save();
            }
        } catch (IOException e) {
            // With an unreadable file the defaults rule: always the conservative choice.
        }
        return valueList;
    }

    private static void save() {
        try {
            Files.createDirectories(file().getParent());
            List<String> lines = new ArrayList<>();
            lines.add("# Behaviour preferences. Managed by the AI through the chat.");
            for (var e : valueList.entrySet()) {
                lines.add(e.getKey() + "=" + e.getValue());
            }
            Files.write(file(), lines);
        } catch (IOException e) {
            marionette.common.Logbook.note("preferences",
                    "could not save the file: " + e.getMessage());
        }
    }

    static synchronized boolean is(String key) {
        return load().getOrDefault(key, false);
    }

    /** @return null if it worked, or the reason (unknown key) */
    static synchronized String place(String key, boolean value) {
        if (!DEFAULTS.containsKey(key)) {
            return "there is no preference '" + key + "'; the ones there are: "
                    + String.join(", ", DEFAULTS.keySet());
        }
        load().put(key, value);
        save();
        return null;
    }

    static synchronized TreeMap<String, Boolean> allItems() {
        return new TreeMap<>(load());
    }
}
