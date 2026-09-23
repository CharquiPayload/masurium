package masurium.server;

import masurium.common.Settings;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * A bot's rules on this server: its behaviour toggles, the food it does not eat on its
 * own and the blocks it may break on its own. Plain logic, without Minecraft.
 *
 * <p><b>Three layers</b>, weakest first:
 * <ul>
 *   <li><b>base</b>: what the bot is — its own config and its server's. The launcher
 *       sends it.
 *   <li><b>own</b>: this bot's, here. {@code /masurium bot} edits it in the game and the
 *       launcher edits the same one: there is one copy, and it is this.
 *   <li><b>imposed</b>: what a group or the launcher's global config imposes. The
 *       launcher sends it, and nothing in the game changes it: a command that tries is
 *       refused, saying who imposes it.
 * </ul>
 * Each layer names only what it decides, a toggle or an id put on or taken off a list, and
 * a stronger layer wins over a weaker one only where they name the same thing. Lists add
 * up: the base banning beef and the own layer banning salmon ban both. What no layer
 * names keeps what every bot starts with ({@link Settings}).
 *
 * <p>A layer may also <b>replace</b> a list: then its entries are the whole list, and
 * what the layers under it said, and what the bot starts with, no longer count. That is
 * the switch for "this group eats exactly this", as opposed to "also this".
 *
 * <p>The body is not told the layers: it is told the result ({@link Effective}), whole,
 * and holds exactly that. So a change here reaches it as it is, within a poll, and a
 * body that had drifted comes back in line within minutes (the bridge sends them again
 * now and then, changed or not).
 *
 * <p>The launcher composes base and imposed with the same rules (launcher/rules.py), and
 * both are held to the same cases (test resources, rules-cases.json).
 */
final class Rules {

    static final String BASE = "base";
    static final String OWN = "own";
    static final String IMPOSED = "imposed";
    /** Weakest first. */
    static final List<String> LAYERS = List.of(BASE, OWN, IMPOSED);
    /** Where something no layer names comes from: what every bot starts with. */
    static final String START = "start";

    /** Item and block ids, bare: that is what the body resolves against its registry. */
    static final Pattern ID = Pattern.compile("[a-z0-9_]{1,64}");
    private static final Pattern FROM_KEY = Pattern.compile("(prefs|food|break)\\.([a-z0-9_]{1,64}|\\*)");
    private static final int LABEL_MAX = 64;

    /** The two lists. {@code on} puts an id on the list, {@code off} takes it off. */
    enum Family {
        /** The food it does not eat on its own. */
        FOOD("food", "ban", "allow", Settings.FOOD_FACTORY),
        /** The blocks it may break on its own. */
        BREAK("break", "allow", "forbid", Settings.BREAK_SEED);

        final String id;
        final String on;
        final String off;
        /** What the list holds before any layer says anything. */
        final List<String> start;

        Family(String id, String on, String off, List<String> start) {
            this.id = id;
            this.on = on;
            this.off = off;
            this.start = start;
        }

        static Family of(String id) {
            for (Family f : values()) {
                if (f.id.equals(id)) return f;
            }
            return null;
        }
    }

    /** One layer: only what it decides. */
    static final class Layer {
        final TreeMap<String, Boolean> prefs = new TreeMap<>();
        /** Per list: id -> true to put it on the list (ban, allow), false to take it off. */
        final EnumMap<Family, TreeMap<String, Boolean>> lists = new EnumMap<>(Family.class);
        /** The lists this layer replaces instead of adding to. */
        final EnumSet<Family> replace = EnumSet.noneOf(Family.class);
        /**
         * Who put each thing in this layer, for the imposed one: "global", "group team".
         * Keys like {@code prefs.hunt_players}, {@code food.beef}, and {@code food.*}
         * for the replace switch.
         */
        final TreeMap<String, String> from = new TreeMap<>();

        Layer() {
            for (Family f : Family.values()) lists.put(f, new TreeMap<>());
        }

        TreeMap<String, Boolean> list(Family f) {
            return lists.get(f);
        }

        Layer copy() {
            Layer c = new Layer();
            c.prefs.putAll(prefs);
            for (Family f : Family.values()) c.list(f).putAll(list(f));
            c.replace.addAll(replace);
            c.from.putAll(from);
            return c;
        }

        boolean isEmpty() {
            return prefs.isEmpty() && replace.isEmpty() && from.isEmpty()
                    && lists.values().stream().allMatch(Map::isEmpty);
        }

        /** As it travels and is kept: only the parts it has, lists sorted. */
        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            if (!prefs.isEmpty()) m.put("prefs", new TreeMap<>(prefs));
            for (Family f : Family.values()) {
                Map<String, Object> l = new LinkedHashMap<>();
                List<String> on = new ArrayList<>();
                List<String> off = new ArrayList<>();
                list(f).forEach((id, yes) -> (yes ? on : off).add(id));
                if (!on.isEmpty()) l.put(f.on, on);
                if (!off.isEmpty()) l.put(f.off, off);
                if (replace.contains(f)) l.put("replace", true);
                if (!l.isEmpty()) m.put(f.id, l);
            }
            if (!from.isEmpty()) m.put("from", new TreeMap<>(from));
            return m;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Layer l && toJson().equals(l.toJson());
        }

        @Override
        public int hashCode() {
            return toJson().hashCode();
        }

        @Override
        public String toString() {
            return masurium.common.Json.write(toJson());
        }

        /**
         * A layer from its JSON. {@code strict} for what comes from outside (the launcher,
         * a command): anything it does not understand is refused, saying what. Not strict
         * for this server's own file, where a toggle the code no longer has is dropped
         * instead: it would read as saved and govern nothing.
         */
        static Layer fromJson(Object json, boolean strict) {
            Layer l = new Layer();
            if (json == null) return l;
            if (!(json instanceof Map<?, ?> m)) {
                if (strict) throw new IllegalArgumentException("rules are a JSON object");
                return l;
            }
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String part = String.valueOf(e.getKey());
                Object v = e.getValue();
                Family f = Family.of(part);
                if (part.equals("prefs")) {
                    readPrefs(l, v, strict);
                } else if (f != null) {
                    readList(l, f, v, strict);
                } else if (part.equals("from")) {
                    readFrom(l, v, strict);
                } else if (strict) {
                    throw new IllegalArgumentException("rules have no '" + part
                            + "': they have prefs, food, break and from");
                }
            }
            return l;
        }

        private static void readPrefs(Layer l, Object v, boolean strict) {
            if (!(v instanceof Map<?, ?> prefs)) {
                if (strict) throw new IllegalArgumentException("prefs is an object: {\"toggle\": true}");
                return;
            }
            for (Map.Entry<?, ?> p : prefs.entrySet()) {
                String key = String.valueOf(p.getKey()).strip().toLowerCase();
                if (!Settings.known(key)) {
                    if (strict) {
                        throw new IllegalArgumentException("there is no toggle '" + key + "'. There are: "
                                + String.join(", ", Settings.keys()));
                    }
                    continue;
                }
                if (!(p.getValue() instanceof Boolean b)) {
                    if (strict) throw new IllegalArgumentException(key + " is true or false");
                    continue;
                }
                l.prefs.put(key, b);
            }
        }

        private static void readList(Layer l, Family f, Object v, boolean strict) {
            if (!(v instanceof Map<?, ?> parts)) {
                if (strict) {
                    throw new IllegalArgumentException(f.id + " is an object: {\"" + f.on + "\": [...], \""
                            + f.off + "\": [...], \"replace\": false}");
                }
                return;
            }
            for (Map.Entry<?, ?> p : parts.entrySet()) {
                String verb = String.valueOf(p.getKey());
                if (verb.equals("replace")) {
                    if (p.getValue() instanceof Boolean b) {
                        if (b) l.replace.add(f);
                    } else if (strict) {
                        throw new IllegalArgumentException(f.id + ".replace is true or false");
                    }
                    continue;
                }
                if (!verb.equals(f.on) && !verb.equals(f.off)) {
                    if (strict) {
                        throw new IllegalArgumentException(f.id + " has " + f.on + ", " + f.off
                                + " and replace, not '" + verb + "'");
                    }
                    continue;
                }
                if (!(p.getValue() instanceof List<?> ids)) {
                    if (strict) throw new IllegalArgumentException(f.id + "." + verb + " is a list of ids");
                    continue;
                }
                boolean on = verb.equals(f.on);
                for (Object o : ids) {
                    String id = id(o);
                    if (id == null) {
                        if (strict) {
                            throw new IllegalArgumentException("'" + o + "' is not an id; they go in English "
                                    + "and without a namespace, like rotten_flesh or dirt");
                        }
                        continue;
                    }
                    Boolean before = l.list(f).put(id, on);
                    if (strict && before != null && before != on) {
                        throw new IllegalArgumentException(id + " is both " + f.on + " and " + f.off
                                + " in the same " + f.id + " list");
                    }
                }
            }
        }

        private static void readFrom(Layer l, Object v, boolean strict) {
            if (!(v instanceof Map<?, ?> from)) {
                if (strict) throw new IllegalArgumentException("from is an object: {\"food.beef\": \"global\"}");
                return;
            }
            for (Map.Entry<?, ?> p : from.entrySet()) {
                String key = String.valueOf(p.getKey());
                String label = p.getValue() == null ? "" : String.valueOf(p.getValue()).strip();
                boolean fine = FROM_KEY.matcher(key).matches() && !label.isEmpty()
                        && label.length() <= LABEL_MAX && label.chars().noneMatch(c -> c < 0x20);
                if (!fine) {
                    if (strict) {
                        throw new IllegalArgumentException("from: '" + key + "' -> '" + label
                                + "' is not like \"food.beef\": \"global\"");
                    }
                    continue;
                }
                l.from.put(key, label);
            }
        }
    }

    /**
     * An id as a layer holds it, or null if it is not one. {@code minecraft:} is taken
     * off, since that is the only namespace the body resolves; any other is refused.
     */
    static String id(Object o) {
        if (!(o instanceof String s)) return null;
        String id = s.strip().toLowerCase();
        if (id.startsWith("minecraft:")) id = id.substring("minecraft:".length());
        return ID.matcher(id).matches() ? id : null;
    }

    /** {@code upper} on top of {@code lower}: a new layer, neither is touched. */
    static Layer merge(Layer lower, Layer upper) {
        Layer m = lower.copy();
        m.prefs.putAll(upper.prefs);
        for (Family f : Family.values()) {
            if (upper.replace.contains(f)) {
                m.list(f).clear();
                m.replace.add(f);
                m.from.keySet().removeIf(k -> k.startsWith(f.id + "."));
            }
            m.list(f).putAll(upper.list(f));
        }
        m.from.putAll(upper.from);
        return m;
    }

    /** What the body holds: every toggle, and each list whole. */
    record Effective(TreeMap<String, Boolean> prefs, TreeSet<String> food, TreeSet<String> breaking) {

        TreeSet<String> list(Family f) {
            return f == Family.FOOD ? food : breaking;
        }

        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("prefs", new TreeMap<>(prefs));
            m.put("food_banned", new ArrayList<>(food));
            m.put("break_allowed", new ArrayList<>(breaking));
            return m;
        }
    }

    /** Which layer decides something, and who put it there. */
    record Source(String layer, String label) {

        /** For a person: "set here", "its config", "imposed by global". */
        String say() {
            return switch (layer) {
                case IMPOSED -> "imposed by " + (label.isEmpty() ? "the launcher" : label);
                case OWN -> "set here";
                case BASE -> "its config";
                default -> "";
            };
        }
    }

    // ------------------------------------------------------------ one bot's three

    private final EnumMap<Kind, Layer> layers = new EnumMap<>(Kind.class);

    private enum Kind { BASE, OWN, IMPOSED }

    Rules() {
        for (Kind k : Kind.values()) layers.put(k, new Layer());
    }

    private static Kind kind(String layer) {
        return switch (layer == null ? "" : layer) {
            case BASE -> Kind.BASE;
            case OWN -> Kind.OWN;
            case IMPOSED -> Kind.IMPOSED;
            default -> throw new IllegalArgumentException("there is no layer '" + layer
                    + "': they are base, own and imposed");
        };
    }

    /** The layer itself, to change it in place. */
    Layer layer(String name) {
        return layers.get(kind(name));
    }

    void set(String name, Layer layer) {
        layers.put(kind(name), layer.copy());
    }

    Rules copy() {
        Rules r = new Rules();
        layers.forEach((k, l) -> r.layers.put(k, l.copy()));
        return r;
    }

    boolean isEmpty() {
        return layers.values().stream().allMatch(Layer::isEmpty);
    }

    /** Every layer merged, weakest first. */
    Layer merged() {
        return merge(merge(layer(BASE), layer(OWN)), layer(IMPOSED));
    }

    Effective effective() {
        Layer all = merged();
        TreeMap<String, Boolean> prefs = new TreeMap<>(Settings.defaults());
        prefs.putAll(all.prefs);
        TreeSet<String> food = listOf(all, Family.FOOD);
        TreeSet<String> breaking = listOf(all, Family.BREAK);
        return new Effective(prefs, food, breaking);
    }

    private static TreeSet<String> listOf(Layer all, Family f) {
        TreeSet<String> s = all.replace.contains(f) ? new TreeSet<>() : new TreeSet<>(f.start);
        all.list(f).forEach((id, on) -> {
            if (on) s.add(id);
            else s.remove(id);
        });
        return s;
    }

    /**
     * Who decides a toggle ({@code family} null) or an id of a list: the strongest layer
     * that names it, or that replaces its list. {@link #START} if none does.
     */
    Source source(Family family, String key) {
        for (String name : List.of(IMPOSED, OWN, BASE)) {
            Layer l = layer(name);
            if (family == null) {
                if (l.prefs.containsKey(key)) return new Source(name, label(l, "prefs." + key, null));
            } else {
                if (l.list(family).containsKey(key)) {
                    return new Source(name, label(l, family.id + "." + key, family.id + ".*"));
                }
                if (l.replace.contains(family)) {
                    return new Source(name, label(l, family.id + ".*", null));
                }
            }
        }
        return new Source(START, "");
    }

    private static String label(Layer l, String key, String orElse) {
        String s = l.from.get(key);
        if (s == null && orElse != null) s = l.from.get(orElse);
        return s == null ? "" : s;
    }

    /** Whether the imposed layer decides it: then nothing in the game may change it. */
    Source imposedOn(Family family, String key) {
        Source s = source(family, key);
        return s.layer().equals(IMPOSED) ? s : null;
    }

    Map<String, Object> toJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String name : LAYERS) m.put(name, layer(name).toJson());
        return m;
    }

    /** From this server's own file: lenient, see {@link Layer#fromJson}. */
    static Rules fromJson(Object json) {
        Rules r = new Rules();
        if (json instanceof Map<?, ?> m) {
            for (String name : LAYERS) r.set(name, Layer.fromJson(m.get(name), false));
        }
        return r;
    }
}
