package marionette.bot;

import marionette.common.Request;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What the body lacks, so someone finds out.
 *
 * <p>When it is hungry and has no food, the question is whether the bot gets some itself
 * or a player provides it; the same when armor, weapons or the bow break, or it runs out
 * of arrows. And not asking when a player already gave a standing rule such as "get
 * arrows from the bow chest".
 *
 * <p>That is why this <b>notifies</b> and does not ask. Standing orders are free text the
 * mod does not interpret (that is the brain's, see {@link StandingOrders}), so the split
 * stays clean: the body notices what it lacks, the bridge wakes the brain with that
 * notice, and the brain checks whether a rule covers it. If there is one, it follows it
 * without bothering anyone; if not, it asks in the chat.
 *
 * <p>Without this there was no way to find out in time: between turns the bot does not
 * exist, the brain only wakes when someone names it, and hunger does not wait for someone
 * to feel like talking.
 *
 * <p>The brake matters as much as the notice: waking the brain costs money and two
 * minutes of wall time, so each thing is notified once and not mentioned again for
 * {@value #REST_MIN} minutes, even if it is still true.
 */
final class Needs {

    private Needs() {}

    /** How often, in ticks, it looks. None of this changes within a tick. */
    private static final int EVERY = 40;
    /** Minutes each notice rests before it can repeat. */
    private static final int REST_MIN = 10;
    private static final long REST_MS = REST_MIN * 60_000L;
    /**
     * The raw cookable food it carries, with quantities ("3 chicken, 1 salmon"), or null.
     */
    private static String rawCarried(LocalPlayer p) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (String c : RAW_FOODS) {
            int n = quantity(p, c);
            if (n > 0) parts.add(n + " " + c);
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    /** The fuel it carries, or null. */
    private static String fuelCarried(LocalPlayer p) {
        java.util.Map<String, Integer> counts = new java.util.TreeMap<>();
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            var s = inv.getItem(i);
            if (s.isEmpty()) continue;
            String id = net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(s.getItem()).getPath();
            if (FUELS.contains(id) || isFirewood(id)) counts.merge(id, s.getCount(), Integer::sum);
        }
        if (counts.isEmpty()) return null;
        java.util.List<String> parts = new java.util.ArrayList<>();
        counts.forEach((k, v) -> parts.add(v + " " + k));
        return String.join(", ", parts);
    }

    /** How many of an id it carries, hotbar and backpack. */
    private static int quantity(LocalPlayer p, String id) {
        int n = 0;
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            var s = inv.getItem(i);
            if (!s.isEmpty() && net.minecraft.core.registries.BuiltInRegistries.ITEM
                    .getKey(s.getItem()).getPath().equals(id)) n += s.getCount();
        }
        return n;
    }

    /** With hunger at this or less it is no longer a whim. */
    private static final int HUNGER_LOW = 8;
    /**
     * With hunger at this or less, and raw food in the backpack, it is time to STOP AND
     * COOK. Without it a bot went out exploring with half its hunger bar, three raw
     * chickens, firewood and nothing cooked, and came back at 5 of 20, unable to sprint
     * or regenerate. The notice carries what it has to cook with (known furnace, stone to
     * make one, firewood): the brain decides whether to stop now or when it finishes what
     * it has in hand. A pause taken ON ITS OWN, without the brain, is the next step.
     */
    private static final int HUNGER_COOK = 12;
    /** What is cooked in a furnace and really feeds afterwards. */
    private static final java.util.List<String> RAW_FOODS = java.util.List.of(
            "chicken", "beef", "porkchop", "mutton", "cod", "salmon",
            "rabbit", "potato");
    private static final java.util.List<String> FUELS = java.util.List.of(
            "coal", "charcoal", "coal_block", "dried_kelp_block", "blaze_rod");
    /** Any log or plank works as fuel: checked by suffix. */
    private static boolean isFirewood(String id) {
        return id.endsWith("_log") || id.endsWith("_planks") || id.endsWith("_wood")
                || id.equals("stick") || id.endsWith("_slab") && id.contains("oak");
    }
    /**
     * Below this fraction of uses left: notify BEFORE it breaks, which is when something
     * can still be done.
     */
    private static final double SPENT = 0.15;
    /** Notices kept for whoever arrives late. */
    private static final int MEMORY = 50;
    /**
     * Without orders or steps for this long, it offers to do something (such as asking
     * permission to go exploring). It ASKS, it does not do it: nobody authorized going
     * off alone for twenty minutes.
     */
    private static final long IDLE_MS = 20 * 60_000L;

    private record Notice(int id, String what, String text) {}

    private static final List<Notice> notices = new ArrayList<>();
    private static final Map<String, Long> resting = new HashMap<>();
    private static int lastId;
    private static int ticks;

    static synchronized void tick() {
        if (++ticks < EVERY) return;
        ticks = 0;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || !p.isAlive()) return;

        int hunger = p.getFoodData().getFoodLevel();
        if (hunger <= HUNGER_LOW) {
            boolean inHotbar = hasFood(p, 0, 9);
            boolean inBackpack = hasFood(p, 9, p.getInventory().getContainerSize());
            if (!inHotbar && inBackpack) {
                // A different problem with a different fix: eating only looks at the
                // hotbar, so this is fixed by bringing the food up with `wield`.
                warn("hunger", String.format("my hunger is at %d of 20 and "
                        + "the food is in the backpack, not in the hotbar",
                        hunger));
            } else if (!inHotbar) {
                // The odd case the blacklist creates: carrying food and not being allowed
                // to touch it. Staying quiet would mean starving with a full pantry, so
                // it asks.
                String vetoed = vetoedCarried(p);
                warn("hunger", vetoed != null ? String.format(
                        "my hunger is at %d of 20 and the only thing I carry to "
                        + "eat is %s, which is vetoed: tell me whether to eat it",
                        hunger, vetoed)
                        : String.format("my hunger is at %d of 20 and I "
                                + "carry nothing to eat", hunger));
            }
        }

        // Raw food in the backpack and hunger dropping: stop to cook. Only if it carries
        // nothing cooked to eat; with proper food, eating is faster than cooking and it
        // already does that on its own.
        if (hunger <= HUNGER_COOK && !hasFood(p, 0, p.getInventory().getContainerSize())) {
            String raw = rawCarried(p);
            if (raw != null) {
                String firewood = fuelCarried(p);
                int stone = quantity(p, "cobblestone") + quantity(p, "cobbled_deepslate");
                java.util.List<Places.Place> furnaces = Places.of("furnace");
                String withWhat;
                if (!furnaces.isEmpty()) {
                    var h = furnaces.get(0).where();
                    withWhat = String.format("there is a furnace in my memory at %d %d %d",
                            h.getX(), h.getY(), h.getZ());
                } else if (stone >= 8) {
                    withWhat = String.format("I carry %d stone: I can craft a "
                            + "furnace and place it right here", stone);
                } else {
                    withWhat = "I know no furnace nor carry 8 stone to "
                            + "make one; with `search_block furnace` or digging "
                            + "8 stone it is solved";
                }
                warn("cook:" + raw, String.format(
                        "my hunger is at %d of 20, nothing cooked and %s raw in the "
                        + "backpack; %s, and %s. A pause to cook is advisable "
                        + "before going on", hunger, raw, withWhat,
                        firewood == null ? "I carry no fuel (logs, planks "
                                + "or coal)" : "as fuel I carry " + firewood));
            }
        }

        // A full backpack in the middle of a job: what it digs does not fit and stays on
        // the ground, which after five minutes means it is lost.
        if (freeSpots(p) == 0) {
            // First the trash on the list; the notice only if even that leaves no room.
            int tosses = Trash.tossSurplus(p);
            if (tosses > 0) {
                warn("backpack:tossed", String.format("my backpack was full and "
                        + "I tossed %d stacks of trash to make room", tosses));
            } else {
                warn("backpack", "my backpack is full and I carry no trash to "
                        + "toss; whatever I pick up from now on stays on the "
                        + "ground. Tell me what to toss, or what to add to my trash list");
            }
        }

        if (Activity.idleMs() > IDLE_MS) {
            List<StandingOrders.Order> errand = StandingOrders.of(StandingOrders.INACTIVE);
            if (errand.isEmpty()) {
                warn("idle", "I have had nothing to do for a good while; if you want "
                        + "I go out exploring a little and come back, or you give me something to do");
            } else {
                // What to do when idle: a player gives a text such as "look for beef" and
                // the AI runs that same prompt. The text lives in the standing orders
                // (category 'idle') and is handed over here as an errand, not as a
                // question: without it, "not autonomous" is what the bot looked like
                // every time it got bored.
                warn("idle", "I have had nothing to do for a good while, and I have "
                        + "an errand for when I am idle: \""
                        + errand.get(errand.size() - 1).text()
                        + "\". Get on with it now, without asking anyone");
            }
        }

        ItemStack bow = search(p, Items.BOW);
        if (!bow.isEmpty() && p.getProjectile(bow).isEmpty()) {
            warn("arrows", "I carry a bow and have run out of arrows");
        }

        // What is worn (36..39 is the body) and what it holds in hand.
        for (int i = 36; i <= 39; i++) {
            check(p.getInventory().getItem(i), "placed");
        }
        check(p.getMainHandItem(), "in hand");
    }

    /**
     * Notify about what is about to break, not about what broke: once broken there is
     * nothing left to decide.
     */
    private static void check(ItemStack stack, String where) {
        if (stack.isEmpty() || !stack.isDamageableItem()) return;
        int remaining = stack.getMaxDamage() - stack.getDamageValue();
        if (remaining > stack.getMaxDamage() * SPENT) return;
        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        warn("worn:" + id, String.format(
                "my %s (%s) is about to break: it has %d uses left",
                id, where, remaining));
    }

    /**
     * Empty slots in the backpack and hotbar (0..35; the body and the other hand do not
     * count, they are not storage).
     */
    private static int freeSpots(LocalPlayer p) {
        int freeOnes = 0;
        for (int i = 0; i < 36; i++) {
            if (p.getInventory().getItem(i).isEmpty()) freeOnes++;
        }
        return freeOnes;
    }

    /**
     * Food it MAY eat on its own: vetoed food does not count, because for this purpose it
     * is as if it were not carried.
     */
    private static boolean hasFood(LocalPlayer p, int from, int until) {
        for (int i = from; i < until; i++) {
            ItemStack stack = p.getInventory().getItem(i);
            if (stack.isEmpty() || !stack.has(DataComponents.FOOD)) continue;
            if (!FoodBlacklist.vetoed(BuiltInRegistries.ITEM
                    .getKey(stack.getItem()).getPath())) {
                return true;
            }
        }
        return false;
    }

    /** The first VETOED food it carries, or null. */
    private static String vetoedCarried(LocalPlayer p) {
        for (int i = 0; i < 36; i++) {
            ItemStack stack = p.getInventory().getItem(i);
            if (stack.isEmpty() || !stack.has(DataComponents.FOOD)) continue;
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
            if (FoodBlacklist.vetoed(id)) return id;
        }
        return null;
    }

    private static ItemStack search(LocalPlayer p, net.minecraft.world.item.Item what) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).is(what)) return inv.getItem(i);
        }
        return ItemStack.EMPTY;
    }

    /**
     * Notifies something. {@code what} is the key it rests under: if a repeated case
     * SHOULD be told (two deaths in different places), put the detail in the key and they
     * become two things. @return true if the notice went in; false if its cooldown
     * swallowed it
     */
    static synchronized boolean warn(String what, String text) {
        long now = System.currentTimeMillis();
        Long before = resting.get(what);
        if (before != null && now - before < REST_MS) return false;
        resting.put(what, now);
        notices.add(new Notice(++lastId, what, text));
        while (notices.size() > MEMORY) notices.remove(0);
        marionette.common.Logbook.note("body", text);
        return true;
    }

    /**
     * What is new since {@code from}, with the last id for the next round. The same
     * treatment as the server chat: whoever asks keeps the count.
     */
    static synchronized String asJson(int from) {
        StringBuilder sb = new StringBuilder(String.format(
                "{\"ok\":true,\"last\":%d,\"notices\":[", lastId));
        boolean firstItem = true;
        for (Notice a : notices) {
            if (a.id() <= from) continue;
            if (!firstItem) sb.append(',');
            firstItem = false;
            sb.append(String.format("{\"id\":%d,\"what\":\"%s\",\"text\":\"%s\"}",
                    a.id(), Request.escape(a.what()),
                    Request.escape(a.text())));
        }
        return sb.append("]}").toString();
    }
}
