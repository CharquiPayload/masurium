package masurium.bot;

import masurium.common.Logbook;
import masurium.common.Request;
import masurium.common.Route;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.Cat;
import net.minecraft.world.entity.animal.horse.AbstractHorse;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Taming and breeding animals. On demand, like the {@link Hunter}.
 *
 * <p>A bot with a wolf as backup is useful, and so is breeding them. Both are the same
 * gesture (get close to a mob with something in hand and use it) with two different
 * endings, which is why they live together.
 *
 * <p><b>Taming</b>: wolves with bones, cats with raw fish and parrots with seeds. The
 * server decides whether the mob gives in (a wolf does one time in three), so it insists
 * until {@code isTame()} says yes. Cats are not chased: they flee from whoever approaches
 * and come by themselves to whoever stands still with fish in hand, so it stops six
 * blocks away and waits. Horses, donkeys and llamas are tamed by riding them, which is
 * another trade: for now it says it does not know how.
 *
 * <p><b>Breeding</b>: it gives their food ({@link Animal#isFood}) to two adults of the
 * type and waits to see the baby born. The animal's "in love" state does NOT reach the
 * client, so the only truth that can be checked is a new baby nearby; if none appears
 * within thirty seconds, it says so plainly.
 */
final class Tamer {

    /**
     * SIT and STAND are for pets already tamed: touching them with an empty hand toggles
     * between sitting (stays) and standing (follows).
     */
    enum Mode { TAME, BREED, SIT, STAND }

    /**
     * Search radius. Less than hunting: a wolf 128 blocks away is not "the wolf I saw",
     * it is an excursion.
     */
    private static final double VIEW = 48.0;
    /** Ticks between replans of the approach. */
    private static final int EVERY = 10;
    /**
     * Ticks between two uses of the item on the same mob: the server needs time to
     * answer, and one use per tick wastes bones.
     */
    private static final int BETWEEN_USES = 12;
    /** Ticks with the same mob without achieving anything before discarding it. */
    private static final int PATIENCE = 600;
    /**
     * Ticks waiting for the baby after feeding the pair. The adults look for each other,
     * get together and a few seconds later it is born; half a minute is plenty.
     */
    private static final int BABY_WAIT = 600;
    /** Cats are waited for from this distance, standing still. */
    private static final double NEAR_CAT = 6.0;
    /**
     * For breeding, the second adult has to be near the first or they do not find each
     * other.
     */
    private static final double PAIR = 10.0;
    private static final float HP_MIN = 6.0f;
    static final int MAX = 8;

    /** What each type is tamed with: mob id -> item ids. */
    private static final Map<String, List<String>> FOR_TAMING = Map.of(
            "wolf", List.of("bone"),
            "cat", List.of("cod", "salmon"),
            "parrot", List.of("wheat_seeds", "melon_seeds", "pumpkin_seeds",
                    "beetroot_seeds", "torchflower_seeds", "pitcher_pod"));

    private final Walker walker;

    private Mode mode;
    private String type;
    /** When breeding, the required item (id), or empty if I choose. */
    private String food = "";
    /** The last thing I fed, to report it as it was (id). */
    private String lastFood = "";
    private int goal;
    private int deeds;
    private int used;
    private boolean active;
    private String outcome = "I am neither taming nor breeding";
    private Entity aimTarget;
    private Entity firstOne;
    private int ticksSincePlan, ticksSinceUse, ticksWithThis, stuckTicks;
    private int waitingBaby;
    /** What has to be told when the chained stand-up pass ends. */
    private String pendingOutcome;
    private double blueprintX, blueprintZ;
    private final Set<Integer> discardedOnes = new HashSet<>();
    private final Set<Integer> fed = new HashSet<>();
    private final Set<Integer> babiesSeen = new HashSet<>();
    private Set<Route.Point> footsteps;

    Tamer(Walker walker) {
        this.walker = walker;
    }

    private boolean pets() {
        return mode == Mode.SIT || mode == Mode.STAND;
    }

    private String category() {
        return switch (mode) {
            case TAME -> "taming";
            case BREED -> "breeding";
            default -> "pets";
        };
    }

    private String modeName() {
        return switch (mode) {
            case TAME -> "tame";
            case BREED -> "breed";
            case SIT -> "sit";
            case STAND -> "stand";
        };
    }

    /** @return null if it started, or the reason if not */
    synchronized String begin(Mode mode, String type, int quantity) {
        return begin(mode, type, quantity, "");
    }

    /**
     * With {@code food} (an item id) when breeding, THAT is used and nothing else.
     * Otherwise the tool had no way of saying which, and a bot asked to use rotten flesh
     * ate two mutton while carrying two rotten flesh.
     */
    synchronized String begin(Mode mode, String type, int quantity,
                                String food) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return "I am in no world";
        if (!p.isAlive()) return "I am dead; I must respawn first";

        this.mode = mode;
        this.type = type;
        this.food = food == null ? "" : food;
        this.lastFood = "";
        this.goal = Math.max(1, Math.min(MAX, quantity));
        this.deeds = 0;
        this.used = 0;
        this.aimTarget = null;
        this.firstOne = null;
        this.waitingBaby = 0;
        this.discardedOnes.clear();
        this.fed.clear();
        this.babiesSeen.clear();
        this.footsteps = null;

        if (pets()) {
            // No food: a pet is touched with an empty hand. The only requirement is that
            // it is MINE, and the search filters that.
        } else if (mode == Mode.TAME) {
            if (!FOR_TAMING.containsKey(type)) {
                if (List.of("horse", "donkey", "mule", "llama", "trader_llama",
                        "camel", "skeleton_horse").contains(type)) {
                    return "horses, donkeys and llamas are tamed by mounting them, "
                           + "and I do not know how to do that yet";
                }
                return String.format("I do not know how to tame %s: wolves (wolf), "
                        + "cats (cat) and parrots (parrot)", type);
            }
            if (slot(p, tamingFood(), false) < 0) {
                return String.format("to tame a %s I need %s and I carry none",
                        type, String.join(" or ", FOR_TAMING.get(type)));
            }
            // Babies of the type already born do not count: they are noted to tell apart
            // the one born now.
        } else {
            Entity adult = null;
            for (Entity e : near(mc, p, VIEW)) {
                if (isTheType(e) && e instanceof Animal a && !a.isBaby()) {
                    adult = e;
                    break;
                }
            }
            if (adult == null) {
                return String.format("I see no adult %s within %d blocks",
                        type, (int) VIEW);
            }
            if (adult instanceof TamableAnimal) {
                boolean anyTamed = false;
                for (Entity e : near(mc, p, VIEW)) {
                    if (isTheType(e) && e instanceof TamableAnimal t
                            && t.isTame() && !t.isBaby()) {
                        anyTamed = true;
                        break;
                    }
                }
                if (!anyTamed) {
                    return String.format("%s only breed tamed, and I see "
                            + "no tamed one nearby", type);
                }
            }
            final Animal a = (Animal) adult;
            if (!this.food.isEmpty()) {
                int r = slot(p, stack -> id(stack).equals(this.food), false);
                if (r < 0) {
                    return String.format("I carry no %s", this.food);
                }
                if (!a.isFood(p.getInventory().getItem(r))) {
                    return String.format("a %s does not eat %s", type, this.food);
                }
            } else if (slot(p, a::isFood, false) < 0) {
                return String.format("I carry nothing a %s eats", type);
            }
            for (Entity e : near(mc, p, VIEW)) {
                if (isTheType(e) && e instanceof Animal b && b.isBaby()) {
                    babiesSeen.add(e.getId());
                }
            }
        }

        this.aimTarget = search(mc, p);
        if (aimTarget == null) {
            if (pets()) {
                return String.format("I see no pet of mine%s that is %s "
                        + "within %d blocks", type.isEmpty() ? "" : " (" + type + ")",
                        mode == Mode.SIT ? "standing" : "sitting", (int) VIEW);
            }
            return mode == Mode.TAME
                    ? String.format("I see no untamed %s within %d blocks",
                            type, (int) VIEW)
                    : String.format("I see no adult %s I can feed",
                            type);
        }
        this.active = true;
        this.outcome = null;
        this.ticksSincePlan = EVERY;
        this.ticksSinceUse = BETWEEN_USES;
        this.ticksWithThis = 0;
        this.stuckTicks = 0;
        this.blueprintX = Double.NaN;
        Logbook.note(category(), switch (mode) {
            case TAME -> String.format("going out to tame %d %s", goal, type);
            case BREED -> String.format("going out to breed %d pair(s) of %s",
                    goal, type);
            default -> String.format("going to %s my pets%s (up to %d)",
                    modeName(), type.isEmpty() ? "" : " " + type, goal);
        });
        return null;
    }

    synchronized void stop(String because) {
        if (active) {
            Logbook.note(category(),
                    "I leave it: " + because);
        }
        active = false;
        aimTarget = null;
        outcome = because;
    }

    /**
     * The job is over. If it was taming or breeding and MY pets were left sitting, a
     * {@link Mode#STAND} pass is chained before calling it done: ideally they are all
     * standing when breeding or healing ends. A sitting wolf neither follows anyone nor
     * fights, so leaving them like that is leaving the errand half done.
     *
     * <p>The outcome of the real work is NOT lost: it is kept and told together with the
     * stand-up pass's, because whoever asked for breeding wants to know how many babies
     * were born, not how many pets stood up.
     */
    private void finish(String how) {
        walker.stop("I finished with the animals");
        if (!pets() && Preferences.is("stand_when_done")
                && hasMineSitting()) {
            String before = how;
            Logbook.note(category(), "finished; now I stand up my own "
                    + "that were left sitting");
            if (begin(Mode.STAND, type, MAX) == null) {
                pendingOutcome = before;
                return;
            }
        }
        if (pendingOutcome != null) {
            how = pendingOutcome + "; also " + how;
            pendingOutcome = null;
        }
        stop(how);
    }

    /** Is any pet of mine of this type still sitting in sight? */
    private boolean hasMineSitting() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return false;
        for (Entity e : near(mc, p, VIEW)) {
            if (!isTheType(e)) continue;
            if (e instanceof TamableAnimal t && t.isTame() && t.isOwnedBy(p)
                    && t.isInSittingPose()) {
                return true;
            }
        }
        return false;
    }

    synchronized void tick() {
        if (!active) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) { stop("I left the world"); return; }
        if (!p.isAlive()) { stop("I was killed with the animals"); return; }
        if (p.getHealth() < HP_MIN) {
            finish(String.format("I leave it: I have very little hp left (%.1f); "
                    + "I have %d of %d", p.getHealth(), deeds, goal));
            return;
        }

        if (mode == Mode.BREED && waitingBaby > 0) {
            waitForBaby(mc, p);
            return;
        }

        if (aimTarget == null || aimTarget.isRemoved() || !aimTarget.isAlive()
                || ready(aimTarget)) {
            if (aimTarget != null && (mode == Mode.TAME || pets())
                    && ready(aimTarget)) {
                deeds++;
                Logbook.note(category(), pets()
                        ? String.format("%s a %s (%d of %d)",
                                mode == Mode.SIT ? "I sat down" : "I stood up",
                                mobName(aimTarget), deeds, goal)
                        : String.format("I tamed a %s (%d of %d)",
                                type, deeds, goal));
            }
            aimTarget = null;
            if (mode == Mode.TAME && deeds >= goal) {
                finish(String.format("I tamed %d %s; I spent %d %s", deeds, type,
                        used, String.join("/", FOR_TAMING.get(type))));
                return;
            }
            if (pets() && deeds >= goal) {
                finish(String.format("%s %d pet(s)",
                        mode == Mode.SIT ? "I sat down" : "I stood up", deeds));
                return;
            }
            if (mode == Mode.BREED && fed.size() >= 2) {
                waitingBaby = BABY_WAIT;
                walker.stop("pair fed");
                Logbook.note("breeding", "pair fed; waiting for the baby");
                return;
            }
            if (!pets() && slot(p, foodFor(null), false) < 0) {
                finish(String.format("I ran out of food: I have %d of %d "
                        + "and I spent %d", deeds, goal, used));
                return;
            }
            aimTarget = search(mc, p);
            if (aimTarget == null) {
                if (pets()) {
                    finish(deeds == 0
                            ? "I see no pet of mine to " + modeName()
                            : String.format("%s %d pet(s); I see no more %s "
                                    + "nearby", mode == Mode.SIT ? "I sat down"
                                    : "I stood up", deeds, mode == Mode.SIT
                                    ? "standing" : "sitting"));
                    return;
                }
                finish(mode == Mode.TAME
                        ? String.format("I tamed %d of %d %s; I see no more untamed "
                                + "nearby", deeds, goal, type)
                        : String.format("I bred %d of %d pair(s) of %s; I see "
                                + "no more adults to feed nearby",
                                deeds, goal, type));
                return;
            }
            ticksWithThis = 0;
            stuckTicks = 0;
            blueprintX = Double.NaN;
            footsteps = null;
        }

        if (++ticksWithThis > PATIENCE) {
            Logbook.note(category(),
                    "I get nowhere with this " + type + " in 30 s; I discard it");
            discardedOnes.add(aimTarget.getId());
            aimTarget = null;
            return;
        }

        // Within arm's reach: use the item on the mob.
        if (p.canInteractWithEntity(aimTarget, 0.0)) {
            if (walker.walking()) walker.stop("mob within reach");
            if (++ticksSinceUse < BETWEEN_USES) return;
            ticksSinceUse = 0;
            use(mc, p, aimTarget);
            return;
        }

        // Cats are not chased: fish in hand and stand still. (Only when taming: a cat
        // that is ALREADY MINE can be walked up to.)
        if (mode == Mode.TAME && aimTarget instanceof Cat
                && p.distanceTo(aimTarget) <= NEAR_CAT) {
            if (walker.walking()) walker.stop("waiting for the cat");
            int r = slot(p, foodFor(aimTarget), true);
            if (r >= 0) p.getInventory().selected = r;
            return;
        }

        approach(mc, p);
    }

    /**
     * Touches a pet with an EMPTY hand: in the game that toggles sitting/standing if it
     * is yours. With food in hand it would heal it, with dye it would change its collar
     * and with wolf armor it would put it on, so an empty hotbar slot is looked for; if
     * there is none, a block will do (it is neither food nor dye). After the touch it
     * waits longer than usual: the server takes a few ticks to sync the posture, and a
     * second touch before seeing it would put it back as it was.
     */
    private void useHand(Minecraft mc, LocalPlayer p, Entity e) {
        var inv = p.getInventory();
        int r = -1;
        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i).isEmpty()) { r = i; break; }
        }
        if (r < 0) {
            for (int i = 0; i < 9; i++) {
                if (inv.getItem(i).getItem() instanceof net.minecraft.world.item.BlockItem) {
                    r = i;
                    break;
                }
            }
        }
        if (r < 0) {
            finish("I have no empty slot nor a block in the hotbar "
                    + "to touch the pet with my hand; free a slot");
            return;
        }
        if (inv.selected != r) {
            inv.selected = r;
            return;   // the tick lost on purpose: let the server see the change
        }
        p.lookAt(EntityAnchorArgument.Anchor.EYES,
                e.position().add(0, e.getBbHeight() / 2, 0));
        InteractionResult res = mc.gameMode.interact(p, e, InteractionHand.MAIN_HAND);
        if (!res.consumesAction()) return;
        p.swing(InteractionHand.MAIN_HAND);
        used++;
        ticksSinceUse = -30;
    }

    /** One use of the item on the mob, looking at it. */
    private void use(Minecraft mc, LocalPlayer p, Entity e) {
        if (pets()) {
            useHand(mc, p, e);
            return;
        }
        int r = foodSlot(p, e);
        if (r < 0) {
            finish(String.format("I ran out of food: I have %d of %d and "
                    + "I spent %d", deeds, goal, used));
            return;
        }
        if (p.getInventory().selected != r) {
            p.getInventory().selected = r;
            return;   // the tick lost on purpose: let the server see the change
        }
        p.lookAt(EntityAnchorArgument.Anchor.EYES,
                e.position().add(0, e.getBbHeight() / 2, 0));
        InteractionResult res = mc.gameMode.interact(p, e, InteractionHand.MAIN_HAND);
        if (!res.consumesAction()) return;
        p.swing(InteractionHand.MAIN_HAND);
        used++;
        if (mode == Mode.BREED) {
            lastFood = id(p.getInventory().getItem(r));
            fed.add(e.getId());
            if (firstOne == null) firstOne = e;
            Logbook.note("breeding", String.format("I gave %s to a %s (%d of 2)",
                    name(p.getInventory().getItem(r)), type,
                    fed.size()));
            aimTarget = null;   // on to the next adult
        }
    }

    /** After feeding the pair: was anything born? */
    private void waitForBaby(Minecraft mc, LocalPlayer p) {
        for (Entity e : near(mc, p, 16)) {
            if (isTheType(e) && e instanceof Animal a && a.isBaby()
                    && babiesSeen.add(e.getId())) {
                deeds++;
                Logbook.note("breeding", String.format("a %s was born (%d of %d)",
                        type, deeds, goal));
                waitingBaby = 0;
                fed.clear();
                firstOne = null;
                if (deeds >= goal) {
                    // It says WHICH food it was: without this the brain reported what it
                    // had been asked, not what happened.
                    finish(String.format("I bred %d pair(s) of %s; "
                            + "%d were born and I spent %d of %s", goal, type, deeds, used,
                            lastFood.isEmpty() ? "food" : lastFood));
                }
                return;
            }
        }
        if (--waitingBaby <= 0) {
            Logbook.note("breeding", "I fed two, but in 30 s I saw "
                    + "nothing born");
            fed.clear();
            firstOne = null;
            finish(String.format("I fed the pair of %s, but saw "
                    + "no baby born in 30 s; %d of %d were born",
                    type, deeds, goal));
        }
    }

    /** Approach, with the Hunter's restraint. */
    private void approach(Minecraft mc, LocalPlayer p) {
        if (++ticksSincePlan < EVERY) return;
        boolean moved = Double.isNaN(blueprintX)
                || Math.abs(aimTarget.getX() - blueprintX)
                   + Math.abs(aimTarget.getZ() - blueprintZ) > 1.5;
        if (walker.walking() && !moved) return;
        ticksSincePlan = 0;

        ClientWorld world = new ClientWorld(mc.level);
        Route.Point here = MasuriumBot.whereAmI(world, p);
        Route.Point goal = new Route.Point((int) Math.floor(aimTarget.getX()),
                (int) Math.floor(aimTarget.getY()), (int) Math.floor(aimTarget.getZ()));
        Route.Result r = Route.search(world, here,
                Route.Meta.near(goal, 2.0),
                new Route.Options(MasuriumBot.safeFall(p.getHealth()), 8_000,
                        true, true, 30, footsteps)
                        .breaking(Preferences.is("break_to_advance")));
        if (r.hasRoute() && r.steps().size() > 1
                && walker.follow(r.steps(), x -> null) == null) {
            footsteps = new HashSet<>(r.steps());
            blueprintX = aimTarget.getX();
            blueprintZ = aimTarget.getZ();
            stuckTicks = 0;
            return;
        }
        double d = p.distanceTo(aimTarget);
        if (d > 30 && MasuriumBot.approachTo(p, world, here, goal.x(), goal.z(),
                walker)) {
            blueprintX = aimTarget.getX();
            blueprintZ = aimTarget.getZ();
            stuckTicks = 0;
            return;
        }
        stuckTicks += EVERY;
        if (stuckTicks >= 100) {
            Logbook.note(category(), String.format(
                    "I find no path to the %s; I discard it", type));
            discardedOnes.add(aimTarget.getId());
            aimTarget = null;
            stuckTicks = 0;
        }
    }

    // --- who, with what ------------------------------------------------------

    private boolean ready(Entity e) {
        if (mode == Mode.TAME) {
            return e instanceof TamableAnimal t && t.isTame();
        }
        if (mode == Mode.SIT) {
            return e instanceof TamableAnimal t && t.isInSittingPose();
        }
        if (mode == Mode.STAND) {
            return e instanceof TamableAnimal t && !t.isInSittingPose();
        }
        return fed.contains(e.getId());
    }

    private Entity search(Minecraft mc, LocalPlayer p) {
        Entity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity e : near(mc, p, VIEW)) {
            if (!isTheType(e) || discardedOnes.contains(e.getId())) continue;
            if (pets()) {
                // Only MINE: someone else's is not touched, and without a posture to
                // change there is nothing to do.
                if (!(e instanceof TamableAnimal t) || !t.isTame()
                        || !t.isOwnedBy(p) || ready(e)) continue;
            } else if (mode == Mode.TAME) {
                if (!(e instanceof TamableAnimal t) || t.isTame()) continue;
                if (e instanceof NeutralMob n && n.isAngry()) continue;
            } else {
                if (!(e instanceof Animal a) || a.isBaby()) continue;
                if (fed.contains(e.getId())) continue;
                if (e instanceof TamableAnimal t && !t.isTame()) continue;
                if (e instanceof AbstractHorse h && !h.isTamed()) continue;
                if (firstOne != null && e.distanceTo(firstOne) > PAIR) continue;
            }
            double dist = p.distanceTo(e);
            if (dist < bestDist) {
                bestDist = dist;
                best = e;
            }
        }
        return best;
    }

    private static List<Entity> near(Minecraft mc, LocalPlayer p, double radius) {
        return mc.level.getEntities(p, new AABB(p.blockPosition()).inflate(radius),
                x -> x.isAlive() && !(x instanceof net.minecraft.world.entity.player.Player));
    }

    /** Without a type (only happens with pets) any one will do. */
    private boolean isTheType(Entity e) {
        return type.isEmpty() || mobName(e).equals(type);
    }

    private static String mobName(Entity e) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
    }

    private Predicate<ItemStack> tamingFood() {
        List<String> ids = FOR_TAMING.get(type);
        return stack -> ids.contains(id(stack));
    }

    /**
     * The slot with food for this mob. When breeding without a chosen food, first what
     * poisons me (rotten flesh, spider eye...): it does nothing to the mob and is useless
     * to me. Before, it took the first slot that would do, and that was good mutton.
     */
    private int foodSlot(LocalPlayer p, Entity e) {
        Predicate<ItemStack> valid = foodFor(e);
        if (mode == Mode.BREED && food.isEmpty()) {
            int r = slot(p, stack -> valid.test(stack)
                    && MasuriumBot.poisons(id(stack)), true);
            if (r >= 0) return r;
        }
        return slot(p, valid, true);
    }

    /** What is given to THIS mob (or, without a mob, to any one of the type). */
    private Predicate<ItemStack> foodFor(Entity e) {
        if (mode == Mode.TAME) return tamingFood();
        if (mode == Mode.BREED && !food.isEmpty()) {
            return stack -> id(stack).equals(food);
        }
        if (e instanceof Animal a) return a::isFood;
        Minecraft mc = Minecraft.getInstance();
        for (Entity other : near(mc, mc.player, VIEW)) {
            if (isTheType(other) && other instanceof Animal a) return a::isFood;
        }
        return stack -> false;
    }

    /**
     * The hotbar slot with something matching the criterion, or -1. With {@code climb},
     * if it is only in the backpack it is brought up to the hotbar (the same swap a
     * player does with the inventory open).
     */
    private static int slot(LocalPlayer p, Predicate<ItemStack> valid,
                              boolean climb) {
        var inv = p.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && valid.test(stack)) return i;
        }
        for (int i = 9; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && valid.test(stack)) {
                if (!climb) return i;
                return MasuriumBot.takeFromBackpack(p, id(stack));
            }
        }
        return -1;
    }

    private static String id(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
    }

    private static String name(ItemStack stack) {
        return stack.isEmpty() ? "none" : id(stack);
    }

    synchronized String state() {
        if (!active) {
            return String.format("{\"active\":false,\"outcome\":\"%s\"}",
                    Request.escape(outcome));
        }
        return String.format(
                "{\"active\":true,\"mode\":\"%s\",\"type\":\"%s\",\"deeds\":%d,"
                + "\"of\":%d,\"used\":%d%s}",
                modeName(), Request.escape(type),
                deeds, goal, used,
                waitingBaby > 0 ? ",\"waiting_baby\":true" : "");
    }

    synchronized boolean active() {
        return active;
    }
}
