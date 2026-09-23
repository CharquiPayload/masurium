package masurium.common;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Every behaviour toggle a bot has, with its default and a line saying what it does.
 *
 * <p>It lives in {@code common/} because BOTH sides need the same list and they used to
 * keep their own: the body decided what the keys meant and the server had no idea they
 * existed. Now the server can offer them for tab completion and reject a typo before the
 * command runs, and there is one place to add a toggle instead of two that drift.
 *
 * <p>The descriptions are not decoration. These toggles used to be changed by asking the
 * bot, so the brain could explain them in the chat; now they are changed by command, and
 * the command has to be able to say what it is about to switch. Whoever adds a key writes
 * its line here.
 *
 * <p>The food list and the break whitelist are lists of ids, not toggles, but what they
 * start with is here too, for the same reason: the body starts from them and the server
 * works out, from them and a bot's rules, the whole list the body must hold. The trash
 * list is not: it only governs the bot's own backpack and stays with the bot — see
 * {@code docs/architecture.md}.
 */
public final class Settings {

    private Settings() {}

    /** One toggle: its default value and what it does, in one line. */
    public record Toggle(boolean byDefault, String does) {}

    private static final TreeMap<String, Toggle> TOGGLES = new TreeMap<>();

    private static void toggle(String key, boolean byDefault, String does) {
        TOGGLES.put(key, new Toggle(byDefault, does));
    }

    static {
        // true by default: it should know it may build to move. The high cost of bridges
        // and towers in the Route table is the real brake.
        toggle("build_while_following", true,
                "while following someone, place blocks (tower, bridge) to reach them "
                + "instead of stopping where the path ends");
        // false by default on purpose: breaking destroys other people's world; building
        // only adds.
        toggle("break_to_advance", false,
                "when there is no way on foot, tunnel through whitelisted blocks "
                + "instead of giving up on the route");
        // Born as an absolute lock in the code and became a toggle: the owner's bot, the
        // owner's world, the owner's explicit decision.
        toggle("hunt_players", false,
                "allow hunting to target PLAYERS, not only animals and monsters");
        // Defense, not hunting: the creeper nobody sees coming is one of the two usual
        // causes of death. Turning it off leaves only fleeing.
        toggle("shoot_creepers", true,
                "shoot creepers in range with a bow on its own initiative");
        toggle("tame_wolves", true,
                "tame wild wolves on its own when it sees them and carries bones");
        toggle("dress_alone", true,
                "put on armor by itself when it carries something better");
        toggle("harvest_alone", true,
                "harvest and resow its own farms by itself");
        toggle("sleep_alone", true,
                "go to bed by itself when phantoms prowl at night");
        // The "only while mining" is not guaranteed by this toggle but by WHO checks it:
        // the Miner, and nobody else. This is the switch to turn it off entirely.
        toggle("torches_while_mining", true,
                "place torches while mining (never out in the overworld)");
        // One of the few things it does unasked, so it has its switch, but true by
        // default: the alternative is spending the night outdoors surrounded by mobs.
        toggle("night_routine", true,
                "with no errand at night, stay near light or home instead of standing "
                + "in the open");
        // Otherwise the Miner and the FillWorker kept digging with two hearts until
        // something finished them off.
        toggle("retreat_when_hurt", true,
                "break off what it is doing and retreat when badly hurt");
        // Without keepInventory dropped items last five minutes and the brain does not
        // wake up in time.
        toggle("recover_on_death", true,
                "go for its own items by itself after dying");
        toggle("stand_when_done", true,
                "after taming or breeding, stand up its own pets that were left sitting");
        // Like hunt_players, and for the same reason: hitting a person is the owner's
        // decision.
        toggle("defend_from_players", false,
                "while escorting, hit back at PLAYERS who hurt the escorted one, not "
                + "only at monsters");
    }

    /** Whether that toggle exists. A typo must never become a ghost setting. */
    public static boolean known(String key) {
        return key != null && TOGGLES.containsKey(key);
    }

    /** The keys, sorted. This is what the command offers for tab completion. */
    public static List<String> keys() {
        return List.copyOf(TOGGLES.navigableKeySet());
    }

    /** What that toggle does, or null if there is no such toggle. */
    public static String describe(String key) {
        Toggle t = TOGGLES.get(key);
        return t == null ? null : t.does();
    }

    /** The value that rules when nobody has said otherwise. */
    public static boolean byDefault(String key) {
        Toggle t = TOGGLES.get(key);
        return t != null && t.byDefault();
    }

    /**
     * The food a bot does not eat on its own before anyone says anything: what is
     * expensive to replace and almost always meant for something else.
     */
    public static final List<String> FOOD_FACTORY =
            List.of("enchanted_golden_apple", "golden_apple");

    /**
     * The blocks a bot may break on its own before anyone says anything: the common
     * ground that is in the way everywhere and worth nothing.
     */
    public static final List<String> BREAK_SEED =
            List.of("cobblestone", "dirt", "grass_block", "stone");

    /** Every toggle with its default: what a bot behaves like out of the box. */
    public static Map<String, Boolean> defaults() {
        TreeMap<String, Boolean> out = new TreeMap<>();
        TOGGLES.forEach((k, t) -> out.put(k, t.byDefault()));
        return out;
    }
}
