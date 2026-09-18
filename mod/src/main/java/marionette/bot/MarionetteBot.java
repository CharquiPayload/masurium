package marionette.bot;

import marionette.common.Logbook;
import marionette.common.Request;
import marionette.common.Route;
import com.mojang.logging.LogUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The hands: what can only be done from inside the client.
 * The split with the server mod is deliberate and not mixed: <b>if it is a question the
 * server answers it, if it is an action the bot does it</b>. "Where is there coal?" is
 * not answered here: the server knows that, and better. Here the bot hits, looks and
 * walks.
 *
 * <p><b>It only listens on 127.0.0.1</b>, and this time that is right and not an
 * oversight: the agent runs on the SAME machine as this client. The server mod's
 * localhost belongs to another machine; this one is the right one.
 *
 * <p>Everything that touches the game goes through {@link #inGame}. HTTP handlers run on
 * their own thread, and reading or moving the player from there gives half-baked state:
 * the same "timing, not logic" mistake that once cost three bugs in a single night.
 */
@Mod(value = MarionetteBot.ID, dist = Dist.CLIENT)
public class MarionetteBot {

    public static final String ID = "marionette_bot";
    private static final Logger LOG = LogUtils.getLogger();

    /** Its own port: 8477 belongs to the server mod, possibly on another machine. */
    private static final int PORT = Integer.getInteger("marionette.bot.port", 8478);

    /**
     * Reach of a melee hit in 1.21. Farther, the server rejects it and the bot keeps
     * swinging at the air believing it is fighting.
     */
    private static final double REACH = 3.0;

    /**
     * How far it looks so it can say "there is nothing, the nearest is N away" instead of
     * a bare "no".
     */
    private static final double VIEW = 24.0;

    /** Placing reach in 1.21. The same as for breaking. */
    private static final double PLACE_REACH = 4.5;

    /** How many deaths in a row are tolerated before it stops respawning. */
    private static final int CONSECUTIVE_DEATHS = 5;
    /**
     * The streak is forgotten after this: dying twice in an afternoon is not a loop, and
     * it must not use up the allowance for the time it really is one.
     */
    private static final long DEATHS_WINDOW = 5 * 60 * 1000L;

    /** Below this it eats on its own no matter what: it is not starving to death. */
    private static final int HUNGER_ALONE = 10;
    /** At 18 or more Minecraft regenerates health; below, it does not. */
    private static final int HUNGER_REGEN = 18;
    /** Hunger cannot fill any further: at 20 the game does not even let you eat. */
    private static final int HUNGER_FULL = 20;

    /** Until when to keep insisting on eating. See {@link #eat}. */
    private long eatingUntil;
    /**
     * Whether it is eating RIGHT NOW. Behaviours that change what is in hand check it
     * (staircase, strip mine, fill job, torches): eating holds the use key down during
     * the bite, and if another behaviour wields cobblestone at that instant to plug a
     * gap, the key "uses" the cobblestone (the bot was seen eating cobblestone). A bite
     * is 32 ticks: the others wait.
     */
    private static volatile boolean EATING;

    static boolean eating() {
        return EATING;
    }
    /** So the automatic bite is not retried every tick when it cannot happen. */
    private long nextEatAttempt;
    /** Hunger when the bite started: when it goes up, it is over. */
    private int hungerWhenEating;

    private int respawnWait;
    private int consecutiveDeaths;
    private long firstDeath;

    private HttpServer http;
    private final Walker walker = new Walker();
    private final Miner miner = new Miner();
    private final FillWorker fillWorker = new FillWorker(walker, miner);
    private final StripMiner stripMiner = new StripMiner(walker, miner);
    private final StairDigger staircase = new StairDigger(walker, miner);
    private final Guard guard = new Guard();
    private final Lookout lookout = new Lookout(walker);
    private final Follower follower = new Follower(walker);
    private final Escort escort = new Escort(follower, walker);
    private final Farmer farmer = new Farmer(walker, miner, fillWorker);
    /**
     * Whom it escorts BY DEFAULT: a guard of another bot. The launcher writes it into
     * {@code config/marionette-escort.txt} from {@code bots/<bot>/escort}. On joining the
     * world it finds that bot and escorts it; if given something else to do, once free it
     * goes back to its side on its own. Null = nobody's guard.
     */
    private final String defaultEscort = readDefaultEscort();
    /** The same boss, for those without the instance at hand (Escort). */
    private static volatile String BOSS;

    static boolean isMyBoss(String name) {
        return BOSS != null && name != null && BOSS.equalsIgnoreCase(name);
    }

    /** My boss, or null if I am nobody's guard. */
    static String boss() {
        return BOSS;
    }
    /** A "stop escorting" suspends it until further notice (or a restart). */
    private volatile boolean escortSuspended;
    private int defaultEscortTicks;
    private final Routine routine = new Routine(walker);
    private final FurnaceHandler furnaceHandler = new FurnaceHandler();
    private final ChestHandler chestHandler = new ChestHandler();
    private final Hunter hunter = new Hunter(walker);
    private final Tamer tamer = new Tamer(walker);
    private final Rider rider = new Rider(walker);
    private final Shepherd shepherd = new Shepherd(walker);
    private final Archer archerUnit = new Archer(walker);
    private final Fisher fisher = new Fisher();
    private final Traveler traveler = new Traveler(walker);
    private final Explorer explorer = new Explorer(traveler);
    private final ItemRecovery itemRecovery = new ItemRecovery(traveler, walker);

    public MarionetteBot(IEventBus bus) {
        // A client that was not told to be a bot is left alone. Not a disabled mod: no
        // listener, no port, nothing to notice. See Bot.
        if (!Bot.isBot()) {
            // The ONE listener a non-bot registers. It draws on the title screen and
            // nowhere else, so a person who installed this jar is told what it is doing
            // instead of having to guess from a log they will never open.
            NeoForge.EVENT_BUS.register(TitleNotice.class);
            if (Bot.misconfigured()) {
                // Loud, because someone MEANT to start a bot here.
                LOG.error("[marionette-bot] -D{} is set but empty: this client is not "
                        + "going to be a bot. Give it a name.", Bot.NAME_PROPERTY);
            } else {
                LOG.info("[marionette-bot] no -D{}, staying out of the way",
                        Bot.NAME_PROPERTY);
            }
            return;
        }
        BOSS = defaultEscort;
        NeoForge.EVENT_BUS.register(this);
        try {
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
            http.createContext("/state", x -> attend(x, this::state));
            http.createContext("/look", x -> attend(x, this::look));
            http.createContext("/attack", x -> attend(x, this::attack));
            http.createContext("/respawn", x -> attend(x, this::respawn));
            http.createContext("/go", x -> attend(x, this::go));
            http.createContext("/pick_up", x -> attend(x, this::pickUp));
            http.createContext("/place", x -> attend(x, this::place));
            http.createContext("/stop", x -> attend(x, this::stop));
            http.createContext("/inventory", x -> attend(x, this::inventory));
            http.createContext("/wield", x -> attend(x, this::wield));
            http.createContext("/mount", x -> attend(x, this::mount));
            http.createContext("/lead", x -> attend(x, this::lead));
            http.createContext("/dismount", x -> attend(x, this::dismount));
            http.createContext("/toss", x -> attend(x, this::toss));
            http.createContext("/sleep", x -> attend(x, this::sleep));
            http.createContext("/asleep", x -> attend(x, this::sleeps));
            http.createContext("/spawn", x -> attend(x, this::spawn));
            http.createContext("/needs",
                    x -> attend(x, this::needs));
            http.createContext("/pending",
                    x -> attend(x, this::pending));
            http.createContext("/light", x -> attend(x, this::light));
            http.createContext("/explore", x -> attend(x, this::explore));
            http.createContext("/diary", x -> attend(x, this::diary));
            http.createContext("/people", x -> attend(x, this::people));
            http.createContext("/eat", x -> attend(x, this::eat));
            http.createContext("/version", x -> attend(x, this::version));
            http.createContext("/food", x -> attend(x, this::food));
            http.createContext("/trash", x -> attend(x, this::trash));
            http.createContext("/dig", x -> attend(x, this::dig));
            http.createContext("/mark", x -> attend(x, this::markPlace));
            http.createContext("/selection", x -> attend(x, this::selection));
            http.createContext("/fill", x -> attend(x, this::fill));
            http.createContext("/farm", x -> attend(x, this::farm));
            http.createContext("/harvest", x -> attend(x, this::harvest));
            http.createContext("/water", x -> attend(x, this::water));
            http.createContext("/seeds", x -> attend(x, this::seeds));
            http.createContext("/gather", x -> attend(x, this::gather));
            http.createContext("/blueprint", x -> attend(x, this::blueprint));
            http.createContext("/strip_mine", x -> attend(x, this::strip));
            http.createContext("/staircase", x -> attend(x, this::staircase));
            http.createContext("/say", x -> attend(x, this::say));
            http.createContext("/logbook", x -> attend(x, this::logbook));
            http.createContext("/permissions", x -> attend(x, this::permissions));
            http.createContext("/follow", x -> attend(x, this::follow));
            http.createContext("/escort", x -> attend(x, this::startEscort));
            http.createContext("/step_aside", x -> attend(x, this::stepAsideNow));
            http.createContext("/preferences",
                    x -> attend(x, this::preferences));
            http.createContext("/furnace", x -> attend(x, this::furnace));
            http.createContext("/places", x -> attend(x, this::places));
            http.createContext("/chest", x -> attend(x, this::chest));
            http.createContext("/hunt", x -> attend(x, this::hunt));
            http.createContext("/shear", x -> attend(x, this::shear));
            http.createContext("/tame", x -> attend(x, this::tame));
            http.createContext("/breed", x -> attend(x, this::breed));
            http.createContext("/pets", x -> attend(x, this::pets));
            http.createContext("/kill", x -> attend(x, this::kill));
            http.createContext("/orders", x -> attend(x, this::orders));
            http.createContext("/armor", x -> attend(x, this::armor));
            http.createContext("/fish", x -> attend(x, this::fish));
            http.createContext("/disconnect",
                    x -> attend(x, this::disconnect));
            http.setExecutor(null);
            http.start();
            LOG.info("[marionette-bot] listening on http://127.0.0.1:{}", PORT);
        } catch (IOException e) {
            // Loud: if this fails silently, the agent talks to a door that does not exist
            // and nothing gives it away.
            LOG.error("[marionette-bot] could NOT open port {}", PORT, e);
        }
    }

    /** The worlds that exist, so a typo is not accepted as a dimension. */
    private static final List<String> DIMENSIONS =
            List.of("overworld", "the_nether", "the_end");

    /**
     * The player it is attacking right now, or null. The guard needs it so it does not
     * complain about being hit when the bot started the fight (except when the bot hunts
     * the player, of course).
     */
    private String playerInCrosshair() {
        String x = hunter.playerInCrosshair();
        return x != null ? x : archerUnit.playerInCrosshair();
    }

    /**
     * The walker's step goes here: it is the only place where the game is in a consistent
     * state to touch the player.
     */
    /**
     * If I am inside a block (gravel or sand fell on me), I dig the one covering my head
     * and then the one at my feet. Without this it stayed there suffocating, and the
     * strip mine died with "I cannot see it: there is gravel in the way".
     *
     * @return true if this tick went to that (nothing else moves)
     */
    private boolean digOut() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || !p.isAlive() || !p.isInWall()) return false;
        BlockPos feet = p.blockPosition();
        BlockPos head = feet.above();
        BlockPos lid = StripMiner.solid(mc, head) ? head
                : StripMiner.solid(mc, feet) ? feet : null;
        if (lid == null) return false;
        if (lid.equals(miner.target())) {
            miner.tick();
            return true;
        }
        if (miner.digging()) miner.stop("something fell on me; first I get out");
        String failure = miner.begin(lid);
        if (failure == null) {
            Logbook.note("suffocation", String.format(
                    "I am suffocating inside %s at %d %d %d: I dig it",
                    Miner.nameOf(mc.level.getBlockState(lid)),
                    lid.getX(), lid.getY(), lid.getZ()));
            miner.tick();
            return true;
        }
        Logbook.note("suffocation", "I am suffocating and cannot dig what "
                + "covers me: " + failure);
        return false;
    }

    @SubscribeEvent
    public void onClientTick(ClientTickEvent.Post event) {
        // LIGHT. The light the server sends arrives in packets the client leaves in a
        // queue, and that queue is drained by LevelRenderer.renderLevel (pollLightUpdates
        // + runLightUpdates). Nothing is rendered here, so nobody drained it: block light
        // was 0 everywhere, even on top of a torch, and that is why the Torchbearer
        // planted torches blindly. It is drained here, every tick, which is what the
        // renderer would do.
        Minecraft mcLight = Minecraft.getInstance();
        if (mcLight.level != null) {
            mcLight.level.pollLightUpdates();
            mcLight.level.getLightEngine().runLightUpdates();
        }
        respawnAlone();
        eatAlone();
        keepEating();
        walker.tick();
        // What the body lacks is always checked, emergency or not: it is looking, not
        // acting, and the brain decides what to do about it.
        Needs.tick();
        PendingTasks.tick();
        Firsts.tick();
        escortByDefault();
        tameWolvesAlone();
        dressAlone();
        harvestAlone();
        sleepAlone();
        // Walking counts as being busy: the night routine and the idle notice look at
        // this so they do not interrupt anyone.
        if (walker.walking()) Activity.markPlace();
        // The lookout goes FIRST and may keep the whole tick: with a creeper ten blocks
        // away no job, hunt or trip matters, and two behaviours fighting over the legs
        // would be two that do not get it out of there. When there is no emergency it
        // returns false and gets in nobody's way. Buried (gravel or sand fell on it):
        // getting out comes before everything, even the lookout.
        if (digOut()) return;
        if (!lookout.tick()) {
            miner.tick();
            fillWorker.tick();
            farmer.tick();
            stripMiner.tick();
            staircase.tick();
            follower.tick();
            escort.tick();
            explorer.tick();
            itemRecovery.tick();
            // The very last, and only if nobody else did anything: it is the only thing
            // the bot does on its own.
            routine.tick();
            closeForeignMenu();
            furnaceHandler.tick();
            chestHandler.tick();
            hunter.tick();
            tamer.tick();
            rider.tick();
            shepherd.tick();
            archerUnit.tick();
            fisher.tick();
            traveler.tick();
        }
        // The guard goes AFTER the walker and knowing whether it is walking: if it turned
        // the head while walking, the forward push would carry the bot into the mob
        // instead of to the destination. While fleeing a creeper the guard stays quiet:
        // stopping to hit back next to a bomb is how one dies. It runs outside the if
        // (!lookout.tick()) on purpose (it must hit back while walking), but that
        // exception does not apply to fleeing.
        if (!lookout.fleeingACreeper()) {
            guard.tick(walker.walking(), playerInCrosshair());
        }

        // The head, level when STANDING too. While walking the walker levels it, but
        // after digging, placing or eating the pitch stays where the last action left it:
        // staring at the floor until the next order. It decays smoothly and only when
        // nobody is really aiming (digging, fishing, bow, animals).
        LocalPlayer me = mcLight.player;
        if (me != null && Math.abs(me.getXRot()) > 1.0f
                && !walker.walking() && !miner.digging()
                && !tamer.active() && !stripMiner.mining()
                && !staircase.descending() && !fillWorker.working()
                && !furnaceHandler.working() && !hunter.hunting()
                && !archerUnit.state().contains("\"killing\":true")
                && !fisher.state().contains("\"fishing\":true")) {
            me.setXRot(me.getXRot() * 0.85f);
        }
    }

    /**
     * Keep the bite going. The client releases the item use as soon as the key is not
     * pressed, and there is no key here: without this it starts eating and stops at once.
     * It is reasserted while the window opened by {@link #eat} lasts.
     */
    /**
     * Eating on its own when needed.
     *
     * <p>One of the few things it does without being asked, after self-rescue and
     * respawning, and for the same reason: <b>between turns the bot does not exist</b>.
     * The bridge only wakes up when someone names the bot, so if hunger drops while
     * nobody talks to it, nobody tells it, and at zero it starves silently. It is
     * survival, not a decision.
     *
     * <p><b>Three thresholds.</b> There used to be two and the second required HALF
     * health, so as not to waste food (a steak fills 8 points, eating at 17 throws 5
     * away). Bots should always eat when they lack health: better a steak spent than a
     * bot with two hearts less for half an hour:
     * <ul>
     *   <li><b>hunger below {@value #HUNGER_ALONE}</b>: it does not starve, whatever its
     *       health;</li>
     *   <li><b>missing health and hunger below {@value #HUNGER_REGEN}</b>: 18 is where
     *       Minecraft stops regenerating you. With ANY health point missing, eating is
     *       what turns healing back on;</li>
     *   <li><b>half health and hunger below {@value #HUNGER_FULL}</b>: with hunger at 20
     *       and saturation the game heals a point every half second instead of every
     *       four. Badly hurt, that bite is worth the food it wastes.</li>
     * </ul>
     *
     * <p>During a {@link Lookout} emergency it does NOT eat: the bite keeps the use key
     * for 32 ticks and the other behaviours wait, and fleeing a creeper with a steak in
     * hand is the silliest way to die. The lookout runs later in the same tick, so what
     * it said in the previous one is checked.
     *
     * <p>On its own it never eats what poisons, even if it is all it carries: healing by
     * hurting yourself is not healing. By hand yes, if asked.
     */
    private void eatAlone() {
        if (eatingUntil != 0) return;                  // already eating
        long now = System.currentTimeMillis();
        if (now < nextEatAttempt) return;         // do not insist in vain
        Minecraft mc = Minecraft.getInstance();
        var p = mc.player;
        if (p == null || !p.isAlive() || p.isSleeping()) return;

        int hunger = p.getFoodData().getFoodLevel();
        float hp = p.getHealth(), cap = p.getMaxHealth();
        boolean starving = hunger < HUNGER_ALONE;
        boolean noRegen = hp < cap && hunger < HUNGER_REGEN;
        boolean slowHeal = hp < cap / 2 && hunger < HUNGER_FULL;
        if (!starving && !noRegen && !slowHeal) return;
        if (lookout.inEmergency() && !starving) return;

        int slot = foodSlotOf(p, "", false);
        nextEatAttempt = now + 5000;
        // Nothing in the hotbar: the backpack. A bot stayed at hunger 5 with twelve pork
        // chops in the backpack because this only looked at the hotbar and the notice to
        // the brain arrived late. Bringing it up is the same thing the Tamer does with
        // the mobs' food.
        if (slot < 0) slot = moveFoodFromBackpack(p);
        if (slot < 0) return;                          // it carries nothing wholesome

        p.getInventory().selected = slot;
        hungerWhenEating = hunger;
        eatingUntil = now + 5000;
        EATING = true;
        mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
        Logbook.note("eat", String.format(
                "I eat %s on my own (hunger %d/20, hp %.1f)",
                BuiltInRegistries.ITEM.getKey(
                        p.getInventory().getItem(slot).getItem()).getPath(),
                hunger, p.getHealth()));
    }

    private void keepEating() {
        if (eatingUntil == 0) return;
        Minecraft mc = Minecraft.getInstance();
        var p = mc.player;
        boolean ate = p != null && p.getFoodData().getFoodLevel() > hungerWhenEating;
        boolean justDid = p == null
                || System.currentTimeMillis() > eatingUntil
                || ate
                || !p.getMainHandItem().has(DataComponents.FOOD);
        if (justDid) {
            // If it did NOT eat, say why it was cut: a bot tried to eat every five
            // seconds for minutes without gaining a hunger point and the logbook only
            // said "I eat on my own".
            if (!ate && p != null) {
                var hand = p.getMainHandItem();
                Logbook.note("eat", hand.has(DataComponents.FOOD)
                        ? "I did not manage to eat in 5 s (I still had "
                          + BuiltInRegistries.ITEM.getKey(hand.getItem()).getPath()
                          + " in hand)"
                        : "my meal was cut short: someone put "
                          + (hand.isEmpty() ? "none"
                             : BuiltInRegistries.ITEM.getKey(hand.getItem()).getPath())
                          + " in my hand");
            }
            eatingUntil = 0;
            EATING = false;
            mc.options.keyUse.setDown(false);
            return;
        }
        // **Keep the use key pressed.** Here was the bug of the first attempt: a bite
        // lasts ~32 ticks, but the client releases it as soon as the key stops being
        // pressed, and in a bot it never is. It started eating and let go in the same
        // tick, in a loop, without hunger rising a point. It is the same trick the walker
        // already uses to walk: fake the key, do not call the action over and over.
        mc.options.keyUse.setDown(true);
        if (!p.isUsingItem()) {
            mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
        }
    }

    /**
     * Coming back from death on its own.
     *
     * <p>One of the few things it does without being asked, together with self-rescue. A
     * dead bot is not a bot with a problem: it is a bot that does not exist. It does not
     * answer, does not see, cannot even say it is dead, and since the bridge only reacts
     * when someone names it, it can lie there for hours without anyone noticing. With
     * keepInventory there is nothing to lose by respawning.
     *
     * <p><b>The brake matters more than the feature.</b> Respawning inside lava or next
     * to what killed it is dying again, and without a cap that is a loop hammering the
     * server forever. After {@link #CONSECUTIVE_DEATHS} within {@link #DEATHS_WINDOW} it
     * stops trying and waits for someone to look: it prefers staying dead and noticed to
     * insisting silently.
     */
    private void respawnAlone() {
        var p = Minecraft.getInstance().player;
        if (p == null || p.isAlive()) {
            if (p != null) respawnWait = 0;
            return;
        }
        long now = System.currentTimeMillis();
        if (now - firstDeath > DEATHS_WINDOW) {
            firstDeath = now;
            consecutiveDeaths = 0;
        }
        if (consecutiveDeaths >= CONSECUTIVE_DEATHS) return;
        // A breather before asking: the server takes a few ticks to accept the death, and
        // asking to respawn within that gap does nothing.
        if (++respawnWait < 40) return;
        respawnWait = 0;
        consecutiveDeaths++;
        p.respawn();
        if (defaultEscort != null) {
            // A guard that dies takes no initiative: neither escorting again nor going to
            // its grave. It tells its boss through the internal channel (its brain does
            // that) and waits for instructions.
            escortSuspended = true;
            itemRecovery.noInitiative(true);
            Needs.warn("guard-dead", String.format(
                    "I died and respawned. As a guard I do nothing on my own: tell %s "
                    + "through `internal` where I died and what I am missing, and wait for their "
                    + "instructions in silence; when told to come back, "
                    + "use `escort` with %s", defaultEscort, defaultEscort));
        }
        Logbook.note("respawn", String.format(
                "I died and respawned on my own (%d of %d in this streak)",
                consecutiveDeaths, CONSECUTIVE_DEATHS));
        if (consecutiveDeaths >= CONSECUTIVE_DEATHS) {
            Logbook.note("respawn", "I stay dead: I have "
                    + CONSECUTIVE_DEATHS + " deaths in a row and coming back is coming back "
                    + "to die. Someone should look into it.");
        }
    }

    /**
     * What it has done, in order.
     *
     * <p>It does not go through {@link #inGame} on purpose: the logbook lives in memory,
     * so it can be read even when the game thread is stuck, which is exactly when knowing
     * what it was doing matters most.
     */
    private String logbook(Map<String, String> q) {
        long from = q.containsKey("since") ? Request.whole(q, "since") : 0;
        int limit = q.containsKey("limit") ? Request.whole(q, "limit") : 0;
        return Logbook.json(from, limit);
    }

    // --- what touches the game ---------------------------------------------------

    private <T> T inGame(Supplier<T> task) throws Exception {
        return Minecraft.getInstance().submit(task::get).get(5, TimeUnit.SECONDS);
    }

    /** Null if there is no game yet: it happens between starting up and joining. */
    private static String noGame() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return "{\"ok\":false,\"error\":\"I am not in any world yet\"}";
        }
        return null;
    }

    /**
     * What the body lacks, so the bridge wakes the brain without anyone having talked.
     * Whoever asks keeps count with {@code from}, just like with the server chat.
     */
    private String needs(Map<String, String> q) throws Exception {
        int from;
        try {
            from = Integer.parseInt(q.getOrDefault("since", "0").trim());
        } catch (NumberFormatException e) {
            return "{\"ok\":false,\"error\":\"since has to be a number\"}";
        }
        return Needs.asJson(from);
    }

    /**
     * How much light there is. Without coordinates, where the bot is.
     *
     * <p>Answered from the CLIENT, breaking the "questions go to the server" rule, and
     * with reason: light is exactly what the client computes well (it draws it on
     * screen), and the server mod would have to reimplement it to say the same.
     *
     * <p>What matters is BLOCK light: since 1.18 monsters only spawn where it is ZERO,
     * and sky light does not count for that at night. That is why both are returned, but
     * the answer says which one rules.
     */
    private String light(Map<String, String> q) throws Exception {
        return inGame(() -> {
            String negative = noGame();
            if (negative != null) return negative;
            Minecraft mc = Minecraft.getInstance();
            var p = mc.player;
            BlockPos where = q.containsKey("x")
                    ? new BlockPos(Integer.parseInt(require(q, "x")),
                            Integer.parseInt(require(q, "y")),
                            Integer.parseInt(require(q, "z")))
                    : p.blockPosition();
            int block = mc.level.getBrightness(
                    net.minecraft.world.level.LightLayer.BLOCK, where);
            int sky = mc.level.getBrightness(
                    net.minecraft.world.level.LightLayer.SKY, where);
            return String.format("{\"ok\":true,\"x\":%d,\"y\":%d,\"z\":%d,"
                    + "\"block_light\":%d,\"sky_light\":%d,"
                    + "\"mobs_can_spawn\":%b,"
                    + "\"note\":\"what decides is BLOCK light: since "
                    + "1.18 monsters only spawn where it is 0\"}",
                    where.getX(), where.getY(), where.getZ(),
                    block, sky, block == 0);
        });
    }

    /**
     * Going out to explore and coming back. The {@link Traveler} makes the trip; this
     * decides where and tells what it saw.
     */
    private String explore(Map<String, String> q) throws Exception {
        int radius = 0;
        try {
            radius = Integer.parseInt(q.getOrDefault("radius", "0").trim());
        } catch (NumberFormatException ignored) { /* the default will do */ }
        String toward = q.getOrDefault("toward", "").trim().toLowerCase();
        boolean leave = "1".equals(q.get("stop_flag"));
        String searching = q.getOrDefault("searching", "").trim().toLowerCase();
        String biome = q.getOrDefault("biome", "").trim().toLowerCase();
        int branches = 0;
        try {
            branches = Integer.parseInt(q.getOrDefault("branches", "0").trim());
        } catch (NumberFormatException ignored) { /* the default will do */ }
        int nBranches = branches;
        int r = radius;
        return inGame(() -> {
            if (leave) {
                explorer.stop("I was asked to stop exploring");
                traveler.stop("I was asked to stop exploring");
                return "{\"ok\":true,\"exploring\":false}";
            }
            String negative = noGame();
            if (negative != null) return negative;
            fillWorker.stop("going out exploring");
            stripMiner.stop("going out exploring");
            staircase.stop("going out exploring");
            miner.stop("going out exploring");
            follower.stop("going out exploring");
            escort.stop("going out exploring");
            fisher.stop("going out exploring");
            tamer.stop("going out exploring");
            String failure = explorer.begin(r, toward, searching, biome, nBranches);
            if (failure != null) {
                return String.format("{\"ok\":false,\"error\":\"%s\"}",
                        Request.escape(failure));
            }
            return "{\"ok\":true,\"exploring\":true,\"note\":\"I go and "
                   + "come back alone; it takes time, look at 'exploration' in the state\"}";
        });
    }


    /**
     * Strip mining: the endless tunnel of the {@link StripMiner}. It starts with one call
     * and carries on alone; stopped with stop_flag=1 or /stop.
     */
    private String strip(Map<String, String> q) throws Exception {
        String toward = q.getOrDefault("toward", "").trim().toLowerCase();
        boolean branches = !"0".equals(q.get("branches")) && !"false".equals(q.get("branches"));
        boolean leave = "1".equals(q.get("stop_flag"));
        return inGame(() -> {
            if (leave) {
                stripMiner.stop("I was asked to stop mining");
                walker.stop("I was asked to stop mining");
                return "{\"ok\":true,\"mining\":false}";
            }
            String negative = noGame();
            if (negative != null) return negative;
            fillWorker.stop("going strip mining");
            miner.stop("going strip mining");
            follower.stop("going strip mining");
            escort.stop("going strip mining");
            fisher.stop("going strip mining");
            tamer.stop("going strip mining");
            traveler.stop("going strip mining");
            explorer.stop("going strip mining");
            itemRecovery.stop("going strip mining");
            staircase.stop("going strip mining");
            String failure = stripMiner.begin(toward, branches);
            if (failure != null) {
                return String.format("{\"ok\":false,\"error\":\"%s\"}",
                        Request.escape(failure));
            }
            return "{\"ok\":true,\"mining\":true,\"note\":\"I carry on alone until "
                   + "I am stopped; look at 'strip_mine' in the state\"}";
        });
    }

    /**
     * Says a sentence in the chat NOW, at the brain's request.
     *
     * <p>Otherwise the brain only spoke at the end of its turn, so on "go to that place"
     * it stayed quiet until arriving. When a bot accepts an order, it should first say
     * something before doing it. It goes straight to the chat, without the {@link
     * Voice}'s breather: this is not speaking on its own initiative, it is answering.
     */
    private String say(Map<String, String> q) throws Exception {
        String text = q.getOrDefault("text", "").replace('\n', ' ')
                .replace('\r', ' ').trim();
        return inGame(() -> {
            String negative = noGame();
            if (negative != null) return negative;
            if (text.isEmpty()) {
                return "{\"ok\":false,\"error\":\"there is nothing to say\"}";
            }
            if (text.startsWith("/")) {
                return "{\"ok\":false,\"error\":\"that is a command, not a "
                       + "sentence; I do not say it\"}";
            }
            String line = text.length() > 250 ? text.substring(0, 249) + "\u2026" : text;
            Minecraft.getInstance().player.connection.sendChat(line);
            Logbook.note("say", line);
            return String.format("{\"ok\":true,\"said\":\"%s\"}",
                    Request.escape(line));
        });
    }


    /**
     * Going down to an elevation by a zig-zag staircase ({@link StairDigger}). Like the
     * strip mine: it starts with one call and carries on alone; stops with stop_flag=1 or
     * /stop.
     */
    private String staircase(Map<String, String> q) throws Exception {
        String toward = q.getOrDefault("toward", "").trim().toLowerCase();
        boolean leave = "1".equals(q.get("stop_flag"));
        Integer until = null;
        if (!leave) {
            try {
                until = Integer.parseInt(q.getOrDefault("until", "").trim());
            } catch (NumberFormatException e) {
                return "{\"ok\":false,\"error\":\"tell me which Y to go down to (a "
                       + "number, like 16 or -58)\"}";
            }
        }
        Integer elevation = until;
        return inGame(() -> {
            if (leave) {
                staircase.stop("I was asked to stop going down");
                walker.stop("I was asked to stop going down");
                return "{\"ok\":true,\"descending\":false}";
            }
            String negative = noGame();
            if (negative != null) return negative;
            fillWorker.stop("going down a staircase");
            miner.stop("going down a staircase");
            stripMiner.stop("going down a staircase");
            follower.stop("going down a staircase");
            escort.stop("going down a staircase");
            fisher.stop("going down a staircase");
            tamer.stop("going down a staircase");
            traveler.stop("going down a staircase");
            explorer.stop("going down a staircase");
            itemRecovery.stop("going down a staircase");
            String failure = staircase.begin(elevation, toward);
            if (failure != null) {
                return String.format("{\"ok\":false,\"error\":\"%s\"}",
                        Request.escape(failure));
            }
            return String.format("{\"ok\":true,\"descending\":true,\"until\":%d,"
                    + "\"note\":\"I carry on alone to the elevation; look at 'staircase' in the "
                    + "state\"}", elevation);
        });
    }

    /** The diary: the memorable things of this world, which survive restarts. */
    private String diary(Map<String, String> q) throws Exception {
        String memo = q.getOrDefault("annotate", "").trim();
        if (!memo.isEmpty()) {
            Diary.note(memo);
            return "{\"ok\":true,\"noted\":true}";
        }
        int howMany = 20;
        try {
            howMany = Integer.parseInt(q.getOrDefault("how_many", "20").trim());
        } catch (NumberFormatException ignored) { /* the default */ }
        return Diary.asJson(howMany);
    }

    /** What it knows about the people of this world. */
    private String people(Map<String, String> q) throws Exception {
        String who = q.getOrDefault("who", "").trim();
        String memo = q.getOrDefault("note", "").trim();
        if (!memo.isEmpty()) {
            if (who.isEmpty()) {
                return "{\"ok\":false,\"error\":\"a note is ABOUT someone: "
                       + "tell me who\"}";
            }
            People.note(who, memo);
            return "{\"ok\":true,\"noted\":true}";
        }
        return People.asJson(who);
    }

    /**
     * Tossing things on the ground. It knew how to pick up, store and craft, but not
     * drop: with the backpack full of stone and an errand half done, a chest was the only
     * way out.
     *
     * <p>It reports what really LEFT, not what was asked: the inventory is checked before
     * and after. A `drop` can go silently if the stack changed in between, and "tossed
     * 64" when 12 left is the kind of lie this project does not allow itself.
     *
     * <p>What it WEARS is not tossed by mistake: taking armor off is another order, asked
     * for separately.
     */
    private String toss(Map<String, String> q) throws Exception {
        String what = require(q, "what").trim().toLowerCase();
        String quantityText = q.getOrDefault("count", "").trim();
        String halt = q.getOrDefault("to", "").trim();
        // For someone: look at them FIRST, and in another tick. The throw direction is
        // set by the SERVER with the rotation it has of me, and that travels in the
        // tick's movement packet; turning and dropping in the same tick throws towards
        // where it was looking before. A bot throwing items to someone first looks at
        // them.
        String gaze = "";
        if (!halt.isEmpty()) {
            gaze = inGame(() -> lookAt(halt));
            Thread.sleep(150);
        }
        final String finalGaze = gaze;
        return inGame(() -> {
            String negative = noGame();
            if (negative != null) return negative;
            Minecraft mc = Minecraft.getInstance();
            var p = mc.player;
            var inv = p.getInventory();

            int iHad = quantityCarried(p, what);
            if (iHad == 0) {
                return String.format("{\"ok\":false,\"error\":\"I carry no "
                        + "%s on me\"}", Request.escape(what));
            }
            int requests = iHad;
            if (!quantityText.isEmpty() && !quantityText.equals("everything")) {
                try {
                    requests = Math.max(1, Math.min(iHad,
                            Integer.parseInt(quantityText)));
                } catch (NumberFormatException bad) {
                    return "{\"ok\":false,\"error\":\"count has to be "
                           + "a number, or 'everything'\"}";
                }
            }

            int looseItems = 0;
            // Up to 40 rounds: each drops a whole stack or whatever is left. A safety
            // cap, not a business one: without it, an accounting failure would be an
            // infinite loop inside the tick.
            for (int wayBack = 0; wayBack < 40 && looseItems < requests; wayBack++) {
                int slot = slotWith(p, what);
                if (slot < 0) slot = takeFromBackpack(p, what);
                if (slot < 0) break;
                wieldNow(p, slot);
                int inStack = inv.getItem(slot).getCount();
                int missingCount = requests - looseItems;
                if (missingCount >= inStack) {
                    if (!p.drop(true)) break;      // the whole stack
                    looseItems += inStack;
                } else {
                    for (int i = 0; i < missingCount; i++) {
                        if (!p.drop(false)) break; // one at a time
                        looseItems++;
                    }
                }
            }
            int remaining = quantityCarried(p, what);
            Logbook.note("toss", String.format("I toss %d %s on the ground",
                    iHad - remaining, what));
            return String.format("{\"ok\":true,\"what\":\"%s\","
                    + "\"tossed_items\":%d,\"i_have_left\":%d%s,\"note\":\"it stays on "
                    + "the ground and vanishes after 5 minutes\"}",
                    Request.escape(what), iHad - remaining, remaining,
                    finalGaze.isEmpty() ? "" : "," + finalGaze);
        });
    }

    /**
     * Turns towards a player (or bot) by name. Returns the JSON fragment with what
     * happened: seen and at what distance, or not seen.
     */
    private static String lookAt(String name) {
        Minecraft mc = Minecraft.getInstance();
        var p = mc.player;
        if (p == null || mc.level == null) return "\"seen\":false";
        for (net.minecraft.world.entity.player.Player o : mc.level.players()) {
            if (o != p && o.getGameProfile().getName().equalsIgnoreCase(name)) {
                p.lookAt(EntityAnchorArgument.Anchor.EYES, o.getEyePosition());
                return String.format("\"to\":\"%s\",\"seen\":true,"
                        + "\"distance\":%.1f", Request.escape(
                        o.getGameProfile().getName()), p.distanceTo(o));
            }
        }
        return String.format("\"to\":\"%s\",\"seen\":false",
                Request.escape(name));
    }

    /**
     * Switches slot AND TELLS THE SERVER right away.
     *
     * <p>A costly lesson: the client reports the slot change in ITS tick, not when the
     * field is touched. Setting the slot and acting in the same tick leaves the server
     * believing it is still on the previous one, and it acts on what was there BEFORE:
     * asked for rotten flesh, the bot dropped cooked fish, which was exactly what it held
     * in hand.
     *
     * <p>The rest of the mod avoids this by losing a tick on purpose ("switching resets
     * the hit"); where waiting is not possible (a request resolved entirely in one tick)
     * the packet is sent by hand.
     */
    static void wieldNow(LocalPlayer p, int slot) {
        p.getInventory().selected = slot;
        var connection = Minecraft.getInstance().getConnection();
        if (connection != null) {
            connection.send(new net.minecraft.network.protocol.game
                    .ServerboundSetCarriedItemPacket(slot));
        }
    }

    /**
     * How many of that item it carries in the hotbar and backpack (first 36 slots): what
     * is worn and the off hand do not count, they are not tossed.
     */
    static int quantityCarried(LocalPlayer p, String what) {
        int total = 0;
        for (int i = 0; i < 36; i++) {
            var stack = p.getInventory().getItem(i);
            if (!stack.isEmpty() && BuiltInRegistries.ITEM
                    .getKey(stack.getItem()).getPath().equals(what)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /** The first HOTBAR slot with that item, or -1. */
    private static int slotWith(LocalPlayer p, String what) {
        for (int i = 0; i < 9; i++) {
            var stack = p.getInventory().getItem(i);
            if (!stack.isEmpty() && BuiltInRegistries.ITEM
                    .getKey(stack.getItem()).getPath().equals(what)) {
                return i;
            }
        }
        return -1;
    }

    /** The food it does not touch on its own: view it, veto or unveto. */
    /**
     * This jar's version, so the server can say something when a bot joins running a
     * different one. It does NOT go through the game thread and does not need to be in
     * a world: the bridge asks for it while starting, before the client has joined.
     */
    private String version(Map<String, String> q) {
        String v = "";
        try {
            v = net.neoforged.fml.ModList.get().getModContainerById("marionette_bot")
                    .map(c -> c.getModInfo().getVersion().toString())
                    .orElse("");
        } catch (RuntimeException e) {
            // With no version there is nothing to compare; the server warns about
            // nothing, which beats failing the poll over a cosmetic field.
            Logbook.note("version", "could not read my own version: " + e.getMessage());
        }
        return String.format("{\"ok\":true,\"version\":\"%s\"}", Request.escape(v));
    }

    private String food(Map<String, String> q) throws Exception {
        String ban = q.getOrDefault("ban", "").trim().toLowerCase();
        String allow = q.getOrDefault("allow", "").trim().toLowerCase();
        if (!ban.isEmpty()) {
            if (FoodBlacklist.ban(ban)) {
                Logbook.note("food", "I no longer eat " + ban + " on my "
                        + "own");
            }
        } else if (!allow.isEmpty()) {
            if (FoodBlacklist.allow(allow)) {
                Logbook.note("food", "I can eat again " + allow
                        + " on my own");
            }
        }
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"banned\":[");
        boolean firstItem = true;
        for (String id : FoodBlacklist.list()) {
            if (!firstItem) sb.append(',');
            firstItem = false;
            sb.append('"').append(Request.escape(id)).append('"');
        }
        return sb.append("]}").toString();
    }

    /**
     * The trash list: what it tosses on its own with the backpack full. See {@link
     * Trash}.
     */
    private String trash(Map<String, String> q) throws Exception {
        String add = q.getOrDefault("add", "").trim().toLowerCase();
        String remove = q.getOrDefault("remove", "").trim().toLowerCase();
        if (!add.isEmpty()) {
            if (Trash.add(add)) {
                Logbook.note("trash", add + " is now trash: I toss it if I fill up");
            }
        } else if (!remove.isEmpty()) {
            if (Trash.remove(remove)) {
                Logbook.note("trash", remove + " is no longer trash");
            }
        }
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"trash\":[");
        boolean firstItem = true;
        for (String id : Trash.list()) {
            if (!firstItem) sb.append(',');
            firstItem = false;
            sb.append('"').append(Request.escape(id)).append('"');
        }
        return sb.append("]}").toString();
    }

    /** A reminder for itself: the bridge hands it back when it is due. */
    private String pending(Map<String, String> q) throws Exception {
        String what = q.getOrDefault("what", "").trim();
        if (what.isEmpty()) return PendingTasks.asJson();
        int at;
        try {
            at = Integer.parseInt(q.getOrDefault("at", "60").trim());
        } catch (NumberFormatException e) {
            return "{\"ok\":false,\"error\":\"at has to be seconds\"}";
        }
        String failure = PendingTasks.note(at, what);
        if (failure != null) {
            return String.format("{\"ok\":false,\"error\":\"%s\"}",
                    Request.escape(failure));
        }
        return PendingTasks.asJson();
    }

    private String state(Map<String, String> q) throws Exception {
        return inGame(() -> {
            String negative = noGame();
            if (negative != null) return negative;
            Minecraft mc = Minecraft.getInstance();
            var p = mc.player;
            var hand = p.getMainHandItem();
            return String.format(
                    "{\"ok\":true,\"x\":%.1f,\"y\":%.1f,\"z\":%.1f,"
                    + "\"hp\":%.1f,\"dead\":%b,\"sleeping\":%b,"
                    + "\"is_night\":%b,\"hunger\":%d,"
                    + "\"in_hand\":\"%s\"%s,\"world\":\"%s\","
                    + "\"walk\":%s,\"digging\":%s,\"fill_job\":%s,\"farm\":%s,\"strip_mine\":%s,\"staircase\":%s,"
                    + "\"follow\":%s,\"furnace\":%s,\"chest\":%s,"
                    + "\"hunt\":%s,\"shear_job\":%s,\"breeding\":%s,\"archery\":%s,\"armor\":%s,\"fishing_job\":%s,"
                    + "\"travel\":%s,\"lookout\":%s,\"escort_status\":%s,"
                    + "\"exploration\":%s,\"recovery\":%s,\"mount_status\":%s,\"lead\":%s,"
                    + "\"mood\":%s}",
                    p.getX(), p.getY(), p.getZ(),
                    p.getHealth(), !p.isAlive(), p.isSleeping(),
                    isNight(p.level()),
                    p.getFoodData().getFoodLevel(),
                    hand.isEmpty() ? "none"
                            : BuiltInRegistries.ITEM.getKey(hand.getItem()).getPath(),
                    // Here and not only in /inventory: what wears out is what is held in
                    // hand, and the state is looked at much more often.
                    wear(hand),
                    p.level().dimension().location(), walker.state(),
                    miner.state(), fillWorker.state(), farmer.state(), stripMiner.state(),
                    staircase.state(),
                    follower.state(),
                    furnaceHandler.state(), chestHandler.state(), hunter.state(),
                    hunter.shearState(), tamer.state(),
                    archerUnit.state(), Armor.placement(p), fisher.state(),
                    traveler.state(), lookout.state(), escort.state(),
                    explorer.state(), itemRecovery.state(), rider.state(),
                    shepherd.state(),
                    Mood.asJson(mc, p));
        });
    }

    private String look(Map<String, String> q) throws Exception {
        double x = Double.parseDouble(require(q, "x"));
        double y = Double.parseDouble(require(q, "y"));
        double z = Double.parseDouble(require(q, "z"));
        return inGame(() -> {
            String negative = noGame();
            if (negative != null) return negative;
            Minecraft.getInstance().player.lookAt(
                    net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,
                    new Vec3(x, y, z));
            return String.format(
                    "{\"ok\":true,\"looking\":{\"x\":%.1f,\"y\":%.1f,\"z\":%.1f}}",
                    x, y, z);
        });
    }

    /**
     * Hits the nearest hostile within reach.
     * One hit per call, on purpose: whoever decides whether to keep hitting is the agent,
     * with the result in front of it. A loop in here would be an action that cannot be
     * stopped from outside.
     */
    private String attack(Map<String, String> q) throws Exception {
        return inGame(() -> {
            String negative = noGame();
            if (negative != null) return negative;
            Minecraft mc = Minecraft.getInstance();
            var p = mc.player;

            // inflate() gives a CUBE, not a sphere: without the distance cut, hostiles 36
            // blocks away slipped in while saying "within 24". The number has to mean
            // what it says.
            List<Entity> hostiles = new ArrayList<>(mc.level.getEntities(
                    p, new AABB(p.blockPosition()).inflate(VIEW),
                    e -> e instanceof Monster && e.isAlive()
                         && p.distanceTo(e) <= VIEW));
            if (hostiles.isEmpty()) {
                return String.format(
                        "{\"ok\":true,\"hit\":false,"
                        + "\"reason\":\"there is no hostile within %.0f blocks\"}",
                        VIEW);
            }

            Entity best = null;
            double bestDist = Double.MAX_VALUE;
            for (Entity e : hostiles) {
                double d = p.distanceTo(e);
                if (d < bestDist) { bestDist = d; best = e; }
            }

            String name = BuiltInRegistries.ENTITY_TYPE
                    .getKey(best.getType()).getPath();

            // Out of reach it does not hit "just in case": the server would reject it and
            // the bot would believe it was fighting. It says where the mob is.
            if (bestDist > REACH) {
                return String.format(
                        "{\"ok\":true,\"hit\":false,\"target\":\"%s\","
                        + "\"distance\":%.1f,\"x\":%d,\"y\":%d,\"z\":%d,"
                        + "\"reason\":\"it is %.1f away and I only reach %.1f; "
                        + "I have to get closer\"}",
                        name, bestDist,
                        best.blockPosition().getX(), best.blockPosition().getY(),
                        best.blockPosition().getZ(), bestDist, REACH);
            }

            p.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,
                     best.position().add(0, best.getBbHeight() / 2, 0));
            // BEFORE EVERY HIT, not once at the start of the fight: building and digging
            // change what is in hand, and without this it would keep hitting with the
            // cobblestone from the last bridge.
            String weapon = WeaponPicker.wieldBest(mc, p);

            // The attack bar: hitting before it recharges does a fraction of the damage,
            // and SWITCHING ITEMS resets it, so wielding the weapon and hitting at the
            // same instant guarantees a weak hit. Better not to spend it and say how much
            // is missing.
            float force = p.getAttackStrengthScale(0.0f);
            if (force < 0.9f) {
                int missingCount = Math.max(1, Math.round(
                        (0.9f - force) * p.getCurrentItemAttackStrengthDelay()));
                return String.format(
                        "{\"ok\":true,\"hit\":false,\"target\":\"%s\","
                        + "\"distance\":%.1f,\"wielding\":\"%s\","
                        + "\"force\":%.2f,\"reason\":\"the hit is at %d%% of "
                        + "charge; with %s it would do a fraction of the damage. Wait "
                        + "~%d ticks\"}",
                        name, bestDist, weapon, force,
                        Math.round(force * 100), weapon, missingCount);
            }

            mc.gameMode.attack(p, best);
            p.swing(InteractionHand.MAIN_HAND);

            // The mob's health is NOT returned. The client's copy lags a few ticks behind
            // the server, so right after hitting it says a number that is not true yet:
            // the usual "timing, not logic" mistake. If the agent wants to know whether
            // it died, it asks the server through /entities, which knows.
            return String.format(
                    "{\"ok\":true,\"hit\":true,\"target\":\"%s\","
                    + "\"distance\":%.1f,\"id\":%d,\"with\":\"%s\","
                    + "\"force\":1.0,"
                    + "\"note\":\"whether it died is for the server to say, not me\"}",
                    name, bestDist, best.getId(), weapon);
        });
    }

    /**
     * Respawning after death.
     * A third-party mod used to do this, and dying left the bot in a half state nobody
     * cleaned up: it respawned standing, bow raised, motionless forever. Here it is an
     * explicit action of the agent, so it can decide whether to return to the same place
     * or change its mind.
     */
    private String respawn(Map<String, String> q) throws Exception {
        return inGame(() -> {
            String negative = noGame();
            if (negative != null) return negative;
            var p = Minecraft.getInstance().player;
            if (p.isAlive()) {
                return "{\"ok\":true,\"respawned\":false,"
                       + "\"reason\":\"I am alive, there is nothing to respawn\"}";
            }
            p.respawn();
            Logbook.note("respawn", "I asked to respawn");
            // The SERVER confirms the respawn a few ticks later; here it only says it was
            // requested. Ask /players for the truth.
            return "{\"ok\":true,\"respawned\":true,"
                   + "\"note\":\"requested; where I appear is for the server to say\"}";
        });
    }

    /**
     * Computes a route and starts walking it. Returns right away.
     * It does not block on purpose: whoever waits and decides when to give up is the
     * agent, which also knows how to cancel. A long block in here would be an action
     * impossible to stop from outside, the worst bug of an earlier version.
     */
    /**
     * Goes for what lies on the ground and steps on it.
     *
     * <p>Picking up in Minecraft is not an action: it happens by itself when the player's
     * and the item's boxes almost touch. That is why this does not "pick up" anything; it
     * just brings the body on top with tight slack. With the usual one (1.4) it stayed
     * next to the log and never took it.
     *
     * <p>The CLIENT finds the item, not the server, on purpose: here there are exact
     * coordinates, and to step on something a rounded block is not enough. Whoever
     * decides WHAT to pick up is still the one upstairs, with /objects.
     */
    private String pickUp(Map<String, String> q) throws Exception {
        String what = q.getOrDefault("what", "").trim();
        double radius = Math.min(32, Math.max(1,
                Double.parseDouble(q.getOrDefault("radius", "16"))));
        return inGame(() -> {
            String negative = noGame();
            if (negative != null) return negative;
            Minecraft mc = Minecraft.getInstance();
            var p = mc.player;

            ItemEntity best = null;
            double nearer = Double.MAX_VALUE;
            for (ItemEntity e : mc.level.getEntitiesOfClass(ItemEntity.class,
                    p.getBoundingBox().inflate(radius))) {
                String id = BuiltInRegistries.ITEM.getKey(
                        e.getItem().getItem()).getPath();
                if (!what.isEmpty() && !what.equalsIgnoreCase(id)) continue;
                double d = p.distanceTo(e);
                if (d < nearer) { nearer = d; best = e; }
            }
            if (best == null) {
                return String.format("{\"ok\":false,\"error\":\"I see no %s lying "
                        + "within %.0f blocks; what drops vanishes after 5 min\"}",
                        what.isEmpty() ? "none" : Request.escape(what), radius);
            }

            String id = BuiltInRegistries.ITEM.getKey(
                    best.getItem().getItem()).getPath();
            int quantity = best.getItem().getCount();
            Vec3 where = best.position();
            ClientWorld world = new ClientWorld(mc.level);
            Route.Point here = whereAmI(world, p);
            Route.Point goal = inBlocks(where.x, where.y, where.z);
            // The same as /go, and for the same reason: a freshly chopped log spends a
            // moment falling, and while in the air its position is not walkable. Without
            // the neighbours, "there is no path" to the item right in front of it.
            List<Route.Point> candidates = possibleDestinations(
                    world, goal, land(world, goal), false);
            if (candidates.isEmpty()) candidates.add(goal);

            Route.Point adjusted = null;
            Route.Result r = null;
            int budget = candidates.size() > 1 ? 8_000 : 20_000;
            for (Route.Point c : candidates) {
                Route.Result attempt = Route.search(world, here, c,
                        new Route.Options(safeFall(p.getHealth()),
                                          budget, false));
                if (attempt.hasRoute()) { adjusted = c; r = attempt; break; }
                if (r == null) r = attempt;
            }
            if (adjusted == null) {
                return String.format("{\"ok\":false,\"error\":\"I see %dx %s at %.1f "
                        + "but there is no path to it or next to it: %s\"}",
                        quantity, Request.escape(id), nearer,
                        Request.escape(r.reason()));
            }
            final Route.Point destinationObj = adjusted;
            Logbook.note("pick_up", String.format("going for %dx %s at %.1f",
                    quantity, id, nearer));
            // 0.4: just enough for the boxes to touch. It is the number this function
            // exists for.
            String problem = walker.follow(r.steps(),
                    from -> replan(from, destinationObj, false), 0.4);
            if (problem != null) {
                return "{\"ok\":false,\"error\":\"" + Request.escape(problem) + "\"}";
            }
            return String.format(
                    "{\"ok\":true,\"going_for\":{\"what\":\"%s\",\"count\":%d},"
                    + "\"distance\":%.1f,\"steps\":%d,"
                    + "\"note\":\"ask the inventory to know whether I took it\"}",
                    Request.escape(id), quantity, nearer, r.steps().size());
        });
    }

    /**
     * Places ONE specific block at some coordinates.
     *
     * <p>What was missing to close the circle: the bot could craft a table and could not
     * put it down. {@link Builder#place} picks the material itself from the scaffolding
     * list, which is right for a bridge and useless for "put the table there".
     *
     * <p>It does not break the rule that building is opt-in (nothing is placed on its own
     * initiative), because here someone is explicitly asking for it, which is exactly the
     * condition the rule requires.
     *
     * <p>In Minecraft blocks are not placed in the air: they need a solid neighbour to
     * rest on. That is why all six are tried, starting from below, which is almost always
     * where the floor is.
     */
    private String place(Map<String, String> q) throws Exception {
        String what = q.getOrDefault("what", "").trim().toLowerCase();
        int x = Request.whole(q, "x");
        int y = Request.whole(q, "y");
        int z = Request.whole(q, "z");
        return inGame(() -> {
            String negative = noGame();
            if (negative != null) return negative;
            Minecraft mc = Minecraft.getInstance();
            var p = mc.player;
            BlockPos where = new BlockPos(x, y, z);

            int slot = -1;
            for (int i = 0; i < 9; i++) {
                var stack = p.getInventory().getItem(i);
                if (stack.isEmpty()) continue;
                if (BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath()
                        .equals(what)) { slot = i; break; }
            }
            if (slot < 0) {
                // Telling "I do not have it" from "I have it but not at hand" saves
                // crafting another one it already has.
                return String.format("{\"ok\":false,\"error\":\"I do not carry %s in "
                        + "the hotbar; if I have it stored, I cannot reach it from "
                        + "here\"}", Request.escape(what));
            }
            if (!mc.level.getBlockState(where).canBeReplaced()) {
                return String.format("{\"ok\":false,\"error\":\"at %d %d %d there is already "
                        + "%s; that spot is taken\"}", x, y, z,
                        Request.escape(Miner.nameOf(
                                mc.level.getBlockState(where))));
            }
            double d = Math.sqrt(p.distanceToSqr(Vec3.atCenterOf(where)));
            if (d > PLACE_REACH) {
                return String.format("{\"ok\":false,\"error\":\"it is %.1f away and "
                        + "I only reach %.1f; I have to get closer\"}",
                        d, PLACE_REACH);
            }
            // Minecraft does not let you place a block inside someone, and rejects it
            // WITHOUT SAYING ANYTHING: the click goes out, nothing happens, and the gap
            // stays empty. Caught on the first test, placing the table right where the
            // bot was standing. Saying so is worth more than the silent gesture.
            List<Entity> obstructions = mc.level.getEntities((Entity) null,
                    new AABB(where), e -> e.blocksBuilding);
            if (!obstructions.isEmpty()) {
                Entity e = obstructions.get(0);
                if (e == p) {
                    return String.format("{\"ok\":false,\"error\":\"I am "
                            + "standing at %d %d %d; I cannot place a block where "
                            + "I am, I have to step aside\"}", x, y, z);
                }
                return String.format("{\"ok\":false,\"error\":\"there is a %s at "
                        + "%d %d %d and it does not let me place anything\"}",
                        Request.escape(BuiltInRegistries.ENTITY_TYPE
                                .getKey(e.getType()).getPath()), x, y, z);
            }

            Direction support = null;
            String withMenu = null;
            for (Direction c : new Direction[]{Direction.DOWN, Direction.NORTH,
                    Direction.SOUTH, Direction.EAST, Direction.WEST,
                    Direction.UP}) {
                var neighbor = mc.level.getBlockState(where.relative(c));
                if (neighbor.isAir()) continue;
                // A neighbour with a menu (furnace, chest, table) is no support: the
                // click OPENS it instead of resting the block, and the open menu makes
                // the game ignore inventory clicks.
                if (neighbor.getMenuProvider(mc.level, where.relative(c)) != null) {
                    withMenu = BuiltInRegistries.BLOCK.getKey(neighbor.getBlock()).getPath();
                    continue;
                }
                support = c;
                break;
            }
            if (support == null && withMenu != null) {
                return String.format("{\"ok\":false,\"error\":\"I can only rest it "
                        + "on the %s next to it and the click would open it instead of placing; "
                        + "rest it on another block\"}", Request.escape(withMenu));
            }
            if (support == null) {
                return String.format("{\"ok\":false,\"error\":\"%d %d %d is "
                        + "in the air and there is no neighbour to rest it on\"}", x, y, z);
            }

            String problem = Builder.placeOf(where, support, slot);
            if (problem != null) {
                return "{\"ok\":false,\"error\":\"" + Request.escape(problem) + "\"}";
            }
            Logbook.note("place", String.format("I placed %s at %d %d %d",
                    what, x, y, z));
            // If what it just placed is a useful place, into memory: automatic capture,
            // so tables, furnaces, beds and chests are remembered as soon as they are
            // placed. What I place I may break: it is noted in PlacedBlocks, which is
            // what the Miner checks.
            PlacedBlocks.note(new BlockPos(x, y, z), what);
            String placeKind = placeType(what);
            if (placeKind != null
                    && Places.remember(placeKind, new BlockPos(x, y, z))) {
                Logbook.note("places", String.format(
                        "I remember %s at %d %d %d", placeKind, x, y, z));
            }
            // What is really in that spot is said by the server a moment later; here it
            // only says the gesture was made.
            return String.format("{\"ok\":true,\"i_placed\":\"%s\","
                    + "\"at\":{\"x\":%d,\"y\":%d,\"z\":%d},"
                    + "\"note\":\"ask what_is_at to confirm it\"}",
                    Request.escape(what), x, y, z);
        });
    }

    /**
     * Where it can really go when asked for a specific spot.
     *
     * <p>"Go to the log" cannot mean "get inside the log". A tree, a wall or a chest are
     * not places to stand, and asking for them returned <i>the destination is not a spot
     * where one can stand</i>: technically true and useless, because what is wanted is to
     * stand NEXT to it. So if the destination itself is no good, its neighbours are.
     *
     * <p>{@code /go} and {@code /pick_up} use it: the second had the same bug and it was
     * found right after fixing the first (a freshly chopped log falls and stays in the
     * air for a moment, and its position is not walkable). A single copy so there is no
     * third.
     */
    private List<Route.Point> possibleDestinations(ClientWorld world, Route.Point goal,
                                              Route.Point landed,
                                              boolean build) {
        List<Route.Point> candidates = new ArrayList<>();
        if (landed != null) candidates.add(landed);
        // The SIDES count also when it can build, and that was a bug of the loose leash:
        // when building became the default, this list stayed at the exact destination and
        // "go to the bed" answered "no route" (a bed is a solid block, nobody can be
        // inside it). The exact one goes FIRST, so "climb up there" still climbs; the
        // sides are plan B, not the first choice.
        for (int[] v : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            Route.Point side = land(world, new Route.Point(
                    goal.x() + v[0], goal.y(), goal.z() + v[1]));
            if (side != null && !candidates.contains(side)) candidates.add(side);
        }
        return candidates;
    }

    private String go(Map<String, String> q) throws Exception {
        int x = Request.whole(q, "x");
        int y = Request.whole(q, "y");
        int z = Request.whole(q, "z");
        return inGame(() -> {
            String negative = noGame();
            if (negative != null) return negative;
            Minecraft mc = Minecraft.getInstance();
            var p = mc.player;

            // An explicit trip rules: if it was following someone, it stops (the follower
            // replans every second and would overwrite this route). CAREFUL: the escort
            // is NOT stopped here. Going somewhere for something is a detour within the
            // escort, not abandoning it: the follower stops (the feet are its own) and
            // the Escort takes them back when done.
            follower.stop("I was asked to go somewhere else");
            fisher.stop("I was asked to go somewhere else");
            tamer.stop("I was asked to go somewhere else");
            traveler.stop("I was given another destination");
            stripMiner.stop("I was asked to go somewhere else");
            staircase.stop("I was asked to go somewhere else");
            ClientWorld world = new ClientWorld(mc.level);
            Route.Point here = whereAmI(world, p);
            Route.Point goal = new Route.Point(x, y, z);

            // The loose leash: building to MOVE is the default. The real lock is the cost
            // table (bridge 23 and tower 28 ticks against 4.6 per step: the path finder
            // only pays them with no alternative on foot). The full back and forth:
            // opt-in after a broken window, then the brain abused it (40 blocks for a
            // "come here"), it had to be asked for in words, and finally it was let
            // loose: by default it knows it may build to move. build=0 forbids it for a
            // specific trip.
            boolean build = !"0".equals(q.get("build"))
                             && !"false".equals(q.get("build"));

            // Is the destination in ANOTHER dimension? It can go to Nether coordinates
            // from the overworld through the nearest Nether portal. Nothing about the
            // destination is validated here, nor can it be: that piece of world does not
            // exist in this world. It is handed to the Traveler, which crosses first and
            // argues about the ground afterwards, as in any segment.
            String dimension = q.getOrDefault("dimension", "").trim()
                    .toLowerCase().replace("minecraft:", "");
            if (!dimension.isEmpty()) {
                if (!DIMENSIONS.contains(dimension)) {
                    return String.format("{\"ok\":false,\"error\":\"I do not know "
                            + "the dimension '%s'; the ones there are: %s\"}",
                            Request.escape(dimension),
                            String.join(", ", DIMENSIONS));
                }
                if (!dimension.equals(Places.currentDimension())) {
                    itemRecovery.stop("I was sent somewhere else");
                    traveler.begin(new Route.Point(x, y, z), dimension,
                            build);
                    return String.format("{\"ok\":true,\"crossing\":true,"
                            + "\"dimension\":\"%s\",\"destination\":{\"x\":%d,"
                            + "\"y\":%d,\"z\":%d},\"note\":\"going to the nearest "
                            + "noted portal, crossing and carrying on to the destination; "
                            + "look at 'travel' in the state\"}",
                            dimension, x, y, z);
                }
            }

            // A useful courtesy: if the requested point is not walkable, the ground right
            // below it is looked for. Asking "go to 100 80 -40" from a map and having the
            // bot answer "that is air" would be right and useless. CAREFUL: it only drops
            // to the ground if it can NOT build. If it can, a destination in the air is
            // legitimate: it climbs up to it. Lowering it anyway would turn "climb up
            // there" into "stay where you are".
            Route.Point landed = build ? goal : land(world, goal);
            List<Route.Point> candidates = possibleDestinations(
                    world, goal, landed, build);
            if (candidates.isEmpty()) {
                // Being trapped beats the destination being bad. Caught while testing:
                // shut in a shaft and with a destination without ground, it answered
                // "there is no ground there" and stayed inside. First it gets out of the
                // hole; the destination is discussed outside.
                String rescued = tryRescue(world, here, build);
                if (rescued != null) return rescued;
                return String.format(
                        "{\"ok\":false,\"error\":\"I find no ground at %d %d %d "
                        + "neither right below nor next to it\"}", x, y, z);
            }

            long t0 = System.nanoTime();
            // The budget is shared: five searches of 20,000 tiles each would be a hundred
            // thousand for a single "go there", and the first one usually gets it right.
            // Whoever fails, fails fast.
            int budget = candidates.size() > 1 ? 8_000 : 20_000;
            // Except when building UPWARDS: the heuristic does not see height (on
            // purpose, because of falls), so a goal on top of a wall forces A* to flood
            // the plain before paying for the tower (a 17-block wall of logs needed ~30k
            // and with 20k it gave up). See the long comment in FillWorker.goToward.
            if (build && goal.y() > here.y() + 3) budget = 60_000;
            Route.Point adjusted = null;
            Route.Result r = null;
            for (Route.Point c : candidates) {
                // With PARTIAL routes: a distant destination is no longer "I find no
                // route", it is the first segment of a trip, and the Traveler continues
                // the next ones. Practice asked for it: "come here" 84 blocks away
                // returned a no after looking at 60k tiles.
                Route.Result attempt = Route.search(world, here, c,
                        new Route.Options(safeFall(p.getHealth()),
                                          budget, build, true)
                                .withDeadline(40)
                                .breaking(Preferences.is(
                                        "break_to_advance")));
                if (attempt.hasRoute()) { adjusted = c; r = attempt; break; }
                if (r == null) r = attempt;   // the first reason, to report it
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;

            if (adjusted == null) {
                String rescued = tryRescue(world, here, build);
                if (rescued != null) return rescued;
                // In water and without a route: cross by swimming in a straight line
                // (Traveler.swimSegment), which the path finder cannot plan on the open
                // sea.
                Route.Point swimming = candidates.get(0);
                if (p.isInWater() && traveler.beginSwimming(swimming, build)) {
                    itemRecovery.stop("I was sent somewhere else");
                    Logbook.note("route", String.format(
                            "no walking path to %d %d %d: crossing by swimming",
                            x, y, z));
                    return String.format(
                            "{\"ok\":true,\"going_to\":{\"x\":%d,\"y\":%d,\"z\":%d},"
                            + "\"swimming\":true,\"looked_at\":%d,\"ms\":%d,"
                            + "\"note\":\"there is no walking route: crossing by swimming in "
                            + "a straight line along the surface; look at 'travel' in "
                            + "the state\"}", swimming.x(), swimming.y(), swimming.z(),
                            r.looked(), ms);
                }
                Logbook.note("route", String.format(
                        "no path to %d %d %d nor to its %d neighbours: %s",
                        x, y, z, candidates.size() - 1, r.reason()));
                return String.format(
                        "{\"ok\":false,\"error\":\"neither to %d %d %d nor next to it: "
                        + "%s\",\"looked_at\":%d,\"ms\":%d}", x, y, z,
                        Request.escape(r.reason()), r.looked(), ms);
            }
            boolean beside = !adjusted.equals(landed);
            Logbook.note("route", String.format(
                    "going to %d %d %d (%d steps%s%s%s)", adjusted.x(), adjusted.y(),
                    adjusted.z(), r.steps().size(),
                    build ? ", building" : "",
                    beside ? ", next to what I was asked" : "",
                    r.isPartial() ? ", by segments: it is far" : ""));
            final Route.Point destination = adjusted;
            String problem = walker.follow(r.steps(),
                    from -> replan(from, destination, build));
            if (problem != null) {
                return "{\"ok\":false,\"error\":\"" + Request.escape(problem) + "\"}";
            }
            // The trip stays supervised: if this segment (partial or not) ends without
            // arriving, the Traveler looks for the next one on its own.
            itemRecovery.stop("I was sent somewhere else");
            traveler.begin(destination, build);
            return String.format(
                    "{\"ok\":true,\"going_to\":{\"x\":%d,\"y\":%d,\"z\":%d},"
                    + "\"beside\":%b,\"by_segments\":%b,"
                    + "\"steps\":%d,\"looked_at\":%d,\"ms\":%d,\"blocks_read\":%d,"
                    + "\"building\":%b,"
                    + "\"note\":\"ask /state to know whether I arrived\"}",
                    adjusted.x(), adjusted.y(), adjusted.z(), beside,
                    r.isPartial(),
                    r.steps().size(), r.looked(), ms, world.queried(),
                    build);
        });
    }

    /**
     * If it is trapped in a hole, it gets out. Null if that was not the case.
     *
     * <p>It is the only time it places a block without being asked, and it is written so
     * it stays the only one: there must be <b>no</b> route at all, it must be enclosed on
     * all four sides, and there must be a way out from above. If any of that fails, it
     * does not build: it says it is trapped and a person will see. The building rule is
     * still the rule; this is its only exception, and narrow on purpose.
     */
    private String tryRescue(ClientWorld world, Route.Point here,
                                   boolean build) {
        if (build) return null;   // able to build, it would already have done so
        if (!Rescue.inAHole(world, here)) return null;

        int tall = Rescue.exitHeight(world, here);
        if (tall < 0) {
            Logbook.note("rescue", String.format(
                    "trapped at %d %d %d with no way out in sight",
                    here.x(), here.y(), here.z()));
            return "{\"ok\":false,\"trapped\":true,\"error\":\"I am "
                   + "trapped in a hole and see no way out within 8 blocks "
                   + "upwards; get me out or tell me to dig\"}";
        }
        String problem = walker.follow(Rescue.staircase(here, tall), null);
        if (problem != null) return null;

        Logbook.note("rescue", String.format(
                "trapped at %d %d %d: climbing %d blocks to get out",
                here.x(), here.y(), here.z(), tall));
        return String.format(
                "{\"ok\":true,\"rescue\":true,\"i_climb\":%d,\"note\":"
                + "\"I was trapped and there was no route: I get out placing blocks under "
                + "my feet, which is the only thing I do without permission. Look at /state and "
                + "when I get up ask me for the destination again\"}", tall);
    }

    /**
     * Another route from wherever it stopped. It already runs on the game thread. With
     * partial routes: stuck halfway through a long trip, a segment that gets closer will
     * do; the Traveler supervises the whole.
     */
    private List<Route.Point> replan(Route.Point from, Route.Point until,
                                          boolean build) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return null;
        Route.Result r = Route.search(new ClientWorld(mc.level), from, until,
                new Route.Options(safeFall(mc.player.getHealth()), 20_000,
                                  build, true).withDeadline(40)
                        .breaking(Preferences.is("break_to_advance")));
        return r.hasRoute() ? r.steps() : null;
    }

    /** Drops to the first walkable spot. Null if there is none nearby. */
    private static Route.Point land(ClientWorld m, Route.Point p) {
        for (int fall = 0; fall <= 8; fall++) {
            int y = p.y() - fall;
            if (m.canStand(p.x(), y, p.z())) return new Route.Point(p.x(), y, p.z());
        }
        return null;
    }

    private static Route.Point inBlocks(double x, double y, double z) {
        return new Route.Point((int) Math.floor(x), (int) Math.floor(y),
                              (int) Math.floor(z));
    }

    /** Half the width of the player's box: it measures 0.6 per side. */
    private static final double HALF_WIDTH = 0.3;

    /**
     * Which tile it starts walking from. <b>It is not {@code floor(position)}.</b>
     *
     * <p>A player is held up by its BOX, not its center: one foot touching the next block
     * is enough. At the edge of a column (on top of the dirt pillar it places itself, for
     * example) the center falls into an empty column while the body rests on the one
     * beside. {@code floor} returned that empty tile, the path finder answered <i>"where
     * I am is not a spot where one can stand"</i> and from then on <b>every</b> trip
     * failed: it stayed stuck, unable even to start walking.
     *
     * <p>It was hard to find because it does not look like a routing error. It looks like
     * a job skipping every block "because I could not reach", which is exactly what
     * happened with a copper cube: 0 of 27, standing at the edge of its own pillar.
     *
     * <p>The exact tile is tried first (the normal case), then the ones the box stands
     * on, and finally one block lower, in case it is falling.
     */
    static Route.Point whereAmI(ClientWorld m, LocalPlayer p) {
        // Mounted, the feet touching the ground are the horse's.
        int y0 = (int) Math.floor((p.isPassenger() ? p.getVehicle() : p).getY());
        double[] offsets = {0, -HALF_WIDTH, HALF_WIDTH};
        // y0+1 too: on a PARTIAL block (a dirt path is 15/16, a slab half) the feet are
        // INSIDE the block's cell, which counts as solid, so the standing tile is the one
        // above. Standing on the spawn path without this, every trip failed again: 463
        // skips of "I cannot start".
        for (int y : new int[]{y0, y0 + 1, y0 - 1}) {
            for (double dx : offsets) {
                for (double dz : offsets) {
                    int x = (int) Math.floor(p.getX() + dx);
                    int z = (int) Math.floor(p.getZ() + dz);
                    // Without the StuckSpots veto: the start tile is where I am, vetoed
                    // or not.
                    if (m.canStandWithoutVeto(x, y, z)) return new Route.Point(x, y, z);
                }
            }
        }
        // None works: the usual one is returned so the error that comes out is the real
        // one ("one cannot stand there") and not one made up here.
        return inBlocks(p.getX(), p.getY(), p.getZ());
    }

    /**
     * How many blocks of fall are accepted according to the health left.
     * The damage is {@code (blocks - 3)} half hearts, so with full health quite a lot can
     * be taken, but half is left as margin on purpose: the plan may chain several falls,
     * and arriving alive with one heart is not arriving well.
     */
    static int safeFall(float hp) {
        int means = (int) Math.floor(hp);          // health already comes in halves
        return Math.max(3, Math.min(12, 3 + means / 4));
    }

    /**
     * Digs the block at some coordinates. Returns right away.
     * Like everything that takes time: it starts and answers. Whoever waits and decides
     * when to give up is the agent, which knows how to cancel. Checked with /state.
     */
    private String dig(Map<String, String> q) throws Exception {
        int x = Request.whole(q, "x");
        int y = Request.whole(q, "y");
        int z = Request.whole(q, "z");
        return inGame(() -> {
            String negative = noGame();
            if (negative != null) return negative;
            Minecraft mc = Minecraft.getInstance();
            BlockPos where = new BlockPos(x, y, z);
            String what = Miner.nameOf(mc.level.getBlockState(where));
            Logbook.note("dig", String.format(
                    "starting to dig %s at %d %d %d", what, x, y, z));
            stripMiner.stop("I was asked to dig something else");
            staircase.stop("I was asked to dig something else");
            String problem = miner.begin(where);
            if (problem != null) {
                return "{\"ok\":false,\"error\":\"" + Request.escape(problem)
                       + "\",\"block\":\"" + what + "\"}";
            }
            return String.format(
                    "{\"ok\":true,\"digging\":\"%s\",\"at\":{\"x\":%d,\"y\":%d,"
                    + "\"z\":%d},\"note\":\"whether it fell is for the server to say\"}",
                    what, x, y, z);
        });
    }

    /**
     * Marks a corner of the selection. Without coordinates, its own position, so it can
     * go to a place and say "here", as a player would.
     */
    private String markPlace(Map<String, String> q) throws Exception {
        int which = Integer.parseInt(q.getOrDefault("corner", "1"));
        return inGame(() -> {
            String negative = noGame();
            if (negative != null) return negative;
            var p = Minecraft.getInstance().player;
            BlockPos where = q.containsKey("x")
                    ? new BlockPos(Request.whole(q, "x"), Request.whole(q, "y"),
                                   Request.whole(q, "z"))
                    : p.blockPosition();
            return fillWorker.markPlace(which, where);
        });
    }

    private String selection(Map<String, String> q) throws Exception {
        return inGame(fillWorker::selection);
    }

    /**
     * Fills the selection with a block, or empties it with {@code block=air}.
     * It starts and returns, like everything that takes time. Followed with /state and
     * cut with /stop, which is what is needed in the one function able to rebuild
     * someone's house.
     */
    /**
     * Builds a blueprint: cells with their material, "air" to empty. The
     * `build_blueprint` tool uses it, drawing the house in layers and leaving the
     * counting to the code. It comes by POST because a house is hundreds of cells. It
     * answers at once: the job goes on alone, layer by layer, and is watched in /state
     * (fill_job), so the brain is not left waiting.
     */
    private String blueprint(Map<String, String> q) throws Exception {
        final java.util.Map<BlockPos, String> cells = new java.util.LinkedHashMap<>();
        for (String fragment : q.getOrDefault("cells", "").split(";")) {
            String[] c = fragment.trim().split(",");
            if (c.length != 4) continue;
            try {
                cells.put(new BlockPos(Integer.parseInt(c[0].trim()),
                        Integer.parseInt(c[1].trim()), Integer.parseInt(c[2].trim())),
                        c[3].trim());
            } catch (NumberFormatException ignored) { /* skipped */ }
        }
        if (cells.isEmpty()) {
            return "{\"ok\":false,\"error\":\"cells missing (x,y,z,block;...)\"}";
        }
        return inGame(() -> {
            String negative = noGame();
            if (negative != null) return negative;
            escort.stop("I was asked for a fill job");
            follower.stop("I was asked for a fill job");
            fisher.stop("I was asked for a fill job");
            tamer.stop("I was asked for a fill job");
            traveler.stop("I was asked for a fill job");
            stripMiner.stop("I was asked for a fill job");
            staircase.stop("I was asked for a fill job");
            String problem = fillWorker.beginBlueprint(cells);
            if (problem != null) {
                return "{\"ok\":false,\"error\":\"" + Request.escape(problem)
                       + "\"}";
            }
            return String.format("{\"ok\":true,\"startup\":true,\"cells\":%d,"
                    + "\"note\":\"follow with /state, cut with /stop\"}", cells.size());
        });
    }

    private String fill(Map<String, String> q) throws Exception {
        String block = q.getOrDefault("block", "air").trim().toLowerCase();
        // gap=1: only the shell (walls, floor and ceiling), the inside is left as it is,
        // for filling hollow structures.
        String h = q.getOrDefault("gap", "").trim().toLowerCase();
        boolean gap = h.equals("1") || h.equals("true") || h.equals("yes");
        // cells=x,y,z;x,y,z;... : the job is THOSE cells and not the marked box.
        // `build_blueprint` uses it, drawing the house in layers and leaving the counting
        // to the code.
        final java.util.List<net.minecraft.core.BlockPos> cells = new java.util.ArrayList<>();
        for (String fragment : q.getOrDefault("cells", "").split(";")) {
            String[] c = fragment.trim().split(",");
            if (c.length != 3) continue;
            try {
                cells.add(new net.minecraft.core.BlockPos(
                        Integer.parseInt(c[0].trim()), Integer.parseInt(c[1].trim()),
                        Integer.parseInt(c[2].trim())));
            } catch (NumberFormatException ignored) { /* skipped */ }
        }
        return inGame(() -> {
            String negative = noGame();
            if (negative != null) return negative;
            // Like /go: a new job displaces the follower.
            escort.stop("I was asked for a fill job");
            follower.stop("I was asked for a fill job");
            fisher.stop("I was asked for a fill job");
            tamer.stop("I was asked for a fill job");
            traveler.stop("I was asked for a fill job");
            stripMiner.stop("I was asked for a fill job");
            staircase.stop("I was asked for a fill job");
            String problem = cells.isEmpty()
                    ? fillWorker.begin(block, gap)
                    : fillWorker.beginCells(block, cells);
            if (problem != null) {
                return "{\"ok\":false,\"error\":\"" + Request.escape(problem)
                       + "\"}";
            }
            return "{\"ok\":true,\"startup\":true,"
                   + "\"note\":\"follow with /state, cut with /stop\"}";
        });
    }

    /**
     * Putting something in hand.
     *
     * <p>Otherwise the slot was always chosen by the code (the miner takes the best
     * pickaxe, the weapon picker the best weapon, the builder the scaffolding) and nobody
     * could tell it "wield this". Told "block 1", there was no way to obey, good or bad.
     *
     * <p>It accepts the item by its id, which is the natural way ("wield the sword"), or
     * the slot <b>from 1 to 9 as seen on screen</b>. Careful with that: internally
     * Minecraft numbers them 0 to 8, and {@code /inventory} returns them that way. The
     * screen numbering is used here because it is what a person types, and mixing them up
     * is silently picking the wrong item.
     */
    /**
     * Brings something up from the backpack to the hotbar and returns the slot it ended
     * in, or -1 if it does not carry it.
     *
     * <p><b>Whoever picks a tool on their own only looks at the 9 hotbar slots.</b> That
     * stays as it is on purpose: it is what a player does, and rearranging the hotbar
     * without warning would be deciding for the player. The only exception is fighting:
     * the {@link WeaponPicker} does bring the best weapon up from the backpack (the bot
     * was seen defending itself with a pickaxe with a diamond sword stored), and notes it
     * in the logbook. But <b>asked directly</b> is another matter: a pickaxe it crafts or
     * is given lands in the backpack and used to be invisible to it (a diamond_pickaxe in
     * slot 12, neither used on its own nor wieldable).
     *
     * <p>It swaps with an empty slot if there is one; otherwise with the one in hand,
     * which is the one it just decided to let go of. Nothing is lost: what was in the
     * hotbar goes down to the backpack.
     */
    static int takeFromBackpack(LocalPlayer p, String what) {
        var inv = p.getInventory();
        int inBag = -1;
        for (int i = 9; i < inv.getContainerSize(); i++) {
            var stack = inv.getItem(i);
            if (!stack.isEmpty() && BuiltInRegistries.ITEM
                    .getKey(stack.getItem()).getPath().equals(what)) {
                inBag = i;
                break;
            }
        }
        if (inBag < 0) return -1;

        int destination = inv.selected;
        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i).isEmpty()) { destination = i; break; }
        }
        // The real swap, the same as pressing a number with the inventory open. In the
        // player's own menu the backpack slots 9..35 match their index, so no translation
        // is needed. With ANOTHER menu open (a furnace a `place` click opened by
        // accident) the game ignores the click and the hotbar stays as it was: it is
        // closed first, and the swap is checked. Saying "now I carry X" without looking
        // is what confused the bot with the bed.
        if (p.containerMenu != p.inventoryMenu) p.closeContainer();
        WeaponPicker.moveToHotbar(Minecraft.getInstance(), p, inBag, destination);
        return BuiltInRegistries.ITEM.getKey(inv.getItem(destination).getItem())
                .getPath().equals(what) ? destination : -1;
    }

    /**
     * Approaches a DISTANT target in stages: X and Z first, and Y is left for when it is
     * a local problem. It is the plan B the FillWorker introduced for distant areas, and
     * since goals can say "this column, at any height" ({@link Route.Meta#onlyXZ}) it is
     * ONE search with partial routes; the forty lines of base scanning became
     * unnecessary. Returns true if it is already walking.
     */
    static boolean approachTo(LocalPlayer p, ClientWorld world,
            Route.Point here, int ox, int oz, Walker walker) {
        Route.Result r = Route.search(world, here, Route.Meta.onlyXZ(ox, oz),
                new Route.Options(safeFall(p.getHealth()), 12_000, true,
                        true).withDeadline(40)
                        .breaking(Preferences.is("break_to_advance")));
        return r.hasRoute()
                && walker.follow(r.steps(), d -> null) == null;
    }

    /**
     * Mounts a horse: by its name tag, or the nearest tamed one. What takes time
     * (approaching, insisting if untamed) is done by the {@link Rider} tick by tick, and
     * it NOTIFIES when done.
     */
    /**
     * /lead?tie=<what>&count=N | carry=1&x&y&z | tether=1&x&y&z | release=<what|all>.
     * Animals on a lead (see {@link Shepherd}).
     */
    private String lead(Map<String, String> q) throws Exception {
        return inGame(() -> {
            String negative = noGame();
            if (negative != null) return negative;
            follower.stop("I was asked something with the lead");
            fisher.stop("I was asked something with the lead");
            tamer.stop("I was asked something with the lead");
            traveler.stop("I was asked something with the lead");
            explorer.stop("I was asked something with the lead");
            String failure;
            String memo;
            if (q.containsKey("tie")) {
                int quantity = 1;
                try { quantity = Integer.parseInt(q.getOrDefault("count", "1").trim()); }
                catch (NumberFormatException e) { quantity = 1; }
                failure = shepherd.tie(q.get("tie"), quantity);
                memo = "going for it; I notify when I have it tied or if it will not let me";
            } else if ("1".equals(q.get("carry"))) {
                failure = shepherd.carry(Request.whole(q, "x"), Request.whole(q, "y"),
                        Request.whole(q, "z"));
                memo = "I go at their pace and stop to wait for them; I notify on arriving";
            } else if ("1".equals(q.get("tether"))) {
                failure = shepherd.tether(Request.whole(q, "x"), Request.whole(q, "y"),
                        Request.whole(q, "z"));
                memo = "going to the fence to tether them; I notify when done";
            } else if (q.containsKey("release")) {
                failure = shepherd.release(q.get("release"));
                memo = "I release them; the lead falls to the ground next to each one";
            } else {
                return "{\"ok\":false,\"error\":\"tell me what to do: tie, carry, tether or release\"}";
            }
            if (failure != null) {
                return String.format("{\"ok\":false,\"error\":\"%s\"}", Request.escape(failure));
            }
            return String.format("{\"ok\":true,\"note\":\"%s\"}", Request.escape(memo));
        });
    }

    private String mount(Map<String, String> q) throws Exception {
        String name = q.getOrDefault("name", "").trim();
        return inGame(() -> {
            String negative = noGame();
            if (negative != null) return negative;
            // An explicit trip rules, as in /go: the feet are for approaching the horse.
            follower.stop("I was asked to mount");
            fisher.stop("I was asked to mount");
            tamer.stop("I was asked to mount");
            traveler.stop("I was asked to mount");
            explorer.stop("I was asked to mount");
            String failure = rider.mount(name);
            if (failure != null) {
                return String.format("{\"ok\":false,\"error\":\"%s\"}",
                        Request.escape(failure));
            }
            return "{\"ok\":true,\"mounting\":true,\"note\":\"going for it; "
                    + "I notify when I am mounted or if it will not let me\"}";
        });
    }

    private String dismount(Map<String, String> q) throws Exception {
        return inGame(() -> {
            String negative = noGame();
            if (negative != null) return negative;
            String failure = rider.dismount();
            if (failure != null) {
                return String.format("{\"ok\":false,\"error\":\"%s\"}",
                        Request.escape(failure));
            }
            return "{\"ok\":true,\"descending\":true}";
        });
    }

    private String wield(Map<String, String> q) throws Exception {
        String what = q.getOrDefault("what", "").trim().toLowerCase();
        String tile = q.getOrDefault("slot", "").trim();
        return inGame(() -> {
            String negative = noGame();
            if (negative != null) return negative;
            var p = Minecraft.getInstance().player;

            int slot = -1;
            if (!tile.isEmpty()) {
                int n;
                try {
                    n = Integer.parseInt(tile);
                } catch (NumberFormatException e) {
                    return "{\"ok\":false,\"error\":\"the slot is a number "
                           + "from 1 to 9\"}";
                }
                if (n < 1 || n > 9) {
                    return String.format("{\"ok\":false,\"error\":\"slot "
                            + "%d does not exist; they go from 1 to 9\"}", n);
                }
                slot = n - 1;
                if (p.getInventory().getItem(slot).isEmpty()) {
                    return String.format("{\"ok\":false,\"error\":\"slot "
                            + "%d is empty\"}", n);
                }
            } else if (!what.isEmpty()) {
                // Hotbar first: if it already has it at hand, nothing is touched.
                for (int i = 0; i < 9; i++) {
                    var stack = p.getInventory().getItem(i);
                    if (!stack.isEmpty() && BuiltInRegistries.ITEM
                            .getKey(stack.getItem()).getPath().equals(what)) {
                        slot = i;
                        break;
                    }
                }
                if (slot < 0) {
                    slot = takeFromBackpack(p, what);
                }
                if (slot < 0) {
                    boolean saved = false;
                    for (int i = 9; i < p.getInventory().getContainerSize(); i++) {
                        var stack = p.getInventory().getItem(i);
                        if (!stack.isEmpty() && BuiltInRegistries.ITEM
                                .getKey(stack.getItem()).getPath().equals(what)) {
                            saved = true;
                            break;
                        }
                    }
                    return String.format("{\"ok\":false,\"error\":\"%s\"}",
                            Request.escape(saved
                                    ? "I have " + what + " in the backpack but I could not "
                                      + "bring it to the hotbar; try again"
                                    : "I do not carry " + what + " neither in the hotbar nor in the backpack"));
                }
            } else {
                return "{\"ok\":false,\"error\":\"tell me what to put in my hand: an object "
                       + "by its id, or a slot from 1 to 9\"}";
            }

            p.getInventory().selected = slot;
            var heldInHand = p.getInventory().getItem(slot);
            String id = BuiltInRegistries.ITEM.getKey(heldInHand.getItem()).getPath();
            Logbook.note("wield", String.format("I wielded %s (slot %d)",
                    id, slot + 1));
            return String.format("{\"ok\":true,\"in_hand\":\"%s\","
                    + "\"count\":%d,\"slot\":%d}",
                    Request.escape(id), heldInHand.getCount(), slot + 1);
        });
    }

    /** Ticks in a row with a menu open that no job asked for. */
    private int foreignMenuTicks;

    /**
     * Closes a menu nobody asked for. A {@code place} click on a furnace OPENS it instead
     * of resting the block, and with a menu open the game ignores every inventory click
     * ("Ignoring click in mismatching container"): wielding brings nothing up from the
     * backpack and tossing tosses 0 (a bot could not get a bed into its hand).
     * FurnaceHandler and ChestHandler open and close in the same tick, so a second of
     * margin is plenty for them.
     */
    private void closeForeignMenu() {
        var p = Minecraft.getInstance().player;
        if (p == null || p.containerMenu == p.inventoryMenu
                || furnaceHandler.working() || chestHandler.working()) {
            foreignMenuTicks = 0;
            return;
        }
        if (++foreignMenuTicks < 20) return;
        String which = p.containerMenu.getClass().getSimpleName();
        p.closeContainer();
        foreignMenuTicks = 0;
        Logbook.note("menu", "a menu opened on me (" + which
                + ") without asking and I closed it: open, I cannot touch the backpack");
    }

    /**
     * Going to sleep in the nearest bed.
     *
     * <p>It is the real fix for phantoms, not a patch: they spawn because you have not
     * slept for three days, and sleeping resets that count. Killing them changes nothing.
     *
     * <p>ON DEMAND, never on its own initiative: sleeping **skips the night for the whole
     * server**, and it would ruin the time of someone building at night on purpose.
     *
     * <p>If the bed is far it returns its coordinates and the caller decides whether to
     * go: walking and lying down in the same request would take several ticks, and
     * waiting here is not possible without blocking the game thread.
     */
    /**
     * Eating. The body asked for it, literally: it carried 64 steaks and was dying
     * because nobody could tell it to eat them.
     *
     * <p>Food does not heal at once: it fills hunger, and with full hunger health rises
     * by itself. It is the difference between surviving the night and not.
     *
     * <p>Rotten flesh is left for last even though it is food: it poisons, and with
     * anything else in the backpack it makes no sense. Only if there is nothing else.
     */
    /**
     * Whether it poisons. Rotten flesh takes more health than the hunger it gives if
     * eaten out of habit; by hand it will do as a last resort, on its own never.
     */
    static boolean poisons(String id) {
        return id.equals("rotten_flesh") || id.equals("spider_eye")
                || id.equals("poisonous_potato") || id.equals("pufferfish")
                || id.equals("chicken");
    }

    /**
     * The hotbar slot with something to eat, or -1.
     *
     * @param what specific id, or empty to let it choose
     * @param lastResort whether what poisons will do when there is nothing else. Asked by
     *                   hand yes; eating on its own NO: poisoning itself on its own would
     *                   be healing by hurting itself.
     */
    private static int foodSlotOf(LocalPlayer p, String what,
                                      boolean lastResort) {
        int fine = -1, poisonous = -1;
        for (int i = 0; i < 9; i++) {
            var stack = p.getInventory().getItem(i);
            if (stack.isEmpty() || !stack.has(DataComponents.FOOD)) continue;
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
            if (!what.isEmpty()) {
                if (id.equals(what)) return i;
                continue;
            }
            // The blacklist only weighs when IT chooses. Asking for it by name is an
            // order, not an oversight, just as with rotten flesh (the bot was caught
            // snacking on what it was fishing).
            if (FoodBlacklist.banned(id)) continue;
            if (poisons(id)) {
                if (poisonous < 0) poisonous = i;
            } else if (fine < 0) {
                fine = i;
            }
        }
        if (fine >= 0) return fine;
        return lastResort ? poisonous : -1;
    }

    /**
     * The first wholesome food (neither banned nor poisonous) in the backpack, brought up
     * to the hotbar. -1 if there is none.
     */
    private static int moveFoodFromBackpack(LocalPlayer p) {
        var inv = p.getInventory();
        for (int i = 9; i < inv.getContainerSize(); i++) {
            var stack = inv.getItem(i);
            if (stack.isEmpty() || !stack.has(DataComponents.FOOD)) continue;
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
            if (FoodBlacklist.banned(id) || poisons(id)) continue;
            int r = takeFromBackpack(p, id);
            if (r >= 0) {
                Logbook.note("eat", "I brought up " + id + " from the backpack to eat");
                return r;
            }
        }
        return -1;
    }

    private String eat(Map<String, String> q) throws Exception {
        String what = q.getOrDefault("what", "").trim().toLowerCase();
        return inGame(() -> {
            String negative = noGame();
            if (negative != null) return negative;
            Minecraft mc = Minecraft.getInstance();
            var p = mc.player;
            int hunger = p.getFoodData().getFoodLevel();
            if (hunger >= 20 && what.isEmpty()) {
                return "{\"ok\":false,\"error\":\"my hunger is full; eating "
                       + "now would do nothing\"}";
            }

            int slot = foodSlotOf(p, what, true);
            if (slot < 0) {
                return what.isEmpty()
                        ? "{\"ok\":false,\"error\":\"I carry nothing to eat in the hotbar\"}"
                        : String.format("{\"ok\":false,\"error\":\"I do not carry %s "
                                + "in the hotbar\"}", Request.escape(what));
            }

            p.getInventory().selected = slot;
            var food = p.getInventory().getItem(slot);
            String id = BuiltInRegistries.ITEM.getKey(food.getItem()).getPath();
            // Eating lasts ~32 ticks and the client releases the use as soon as the key
            // is not pressed, which here it never is. That is why a window is marked and
            // the tick reasserts it; without this it starts eating and stops.
            hungerWhenEating = hunger;
            eatingUntil = System.currentTimeMillis() + 5000;
            EATING = true;
            mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
            Logbook.note("eat", String.format("I eat %s (hunger %d/20)",
                    id, hunger));
            return String.format("{\"ok\":true,\"eating\":\"%s\","
                    + "\"hunger_before\":%d,"
                    + "\"note\":\"it takes a couple of seconds; look at the hunger "
                    + "afterwards\"}", Request.escape(id), hunger);
        });
    }

    /**
     * Whether it is the part of the day when a bed accepts someone. The window is the
     * game's (~12542 to ~23459 of the 24000-tick cycle); in a thunderstorm it is also
     * possible, and the caller covers that.
     *
     * <p>The data existed and nobody looked at it: the bot could not say whether it was
     * day or night, and its only diagnosis on failing to sleep was "either it is daytime
     * or there is a monster nearby", guessing between two causes that are now told apart.
     */
    private static boolean isNight(net.minecraft.world.level.Level levelValue) {
        long t = levelValue.getDayTime() % 24000;
        return t >= 12542 && t <= 23459;
    }

    /**
     * The nearest bed within {@code r} blocks around (and four high), or null. Sleeping
     * and setting the spawn share it: they look for the same bed and click it the same
     * way; the only difference is the time of day it works at.
     */
    private static BlockPos bedNear(Minecraft mc,
                                      net.minecraft.client.player.LocalPlayer p,
                                      int r) {
        BlockPos bed = null;
        double nearer = Double.MAX_VALUE;
        BlockPos me = p.blockPosition();
        for (BlockPos b : BlockPos.betweenClosed(me.offset(-r, -4, -r),
                                                 me.offset(r, 4, r))) {
            if (!mc.level.getBlockState(b).is(BlockTags.BEDS)) continue;
            double d = Math.sqrt(b.distSqr(me));
            if (d < nearer) { nearer = d; bed = b.immutable(); }
        }
        return bed;
    }

    private String sleep(Map<String, String> q) throws Exception {
        double radius = Math.min(32, Math.max(1,
                Double.parseDouble(q.getOrDefault("radius", "16"))));
        return inGame(() -> {
            String negative = noGame();
            if (negative != null) return negative;
            Minecraft mc = Minecraft.getInstance();
            var p = mc.player;
            if (p.isSleeping()) {
                return "{\"ok\":true,\"sleeping\":true,\"note\":\"I was already "
                       + "asleep\"}";
            }
            // In daytime it does not even try: the server's refusal used to be silent and
            // the only clue was guessing "either it is daytime or there is a monster".
            if (!isNight(p.level()) && !p.level().isThundering()) {
                return "{\"ok\":false,\"error\":\"it is daytime; I can only sleep "
                       + "at night or in a storm\"}";
            }

            int r = (int) radius;
            BlockPos bed = bedNear(mc, p, r);
            if (bed == null) {
                return String.format("{\"ok\":false,\"error\":\"I see no "
                        + "bed within %d blocks; I have none and cannot craft one "
                        + "without wool\"}", r);
            }
            // A bed seen = a bed remembered, even if it is not slept in today.
            if (Places.remember("bed", bed)) {
                Logbook.note("places", String.format(
                        "I remember a bed at %d %d %d",
                        bed.getX(), bed.getY(), bed.getZ()));
            }
            // The CLICK distance is measured from the EYES, which is how the server
            // validates it: measured from the feet, a bed "4.4" away could be 5.2 from
            // the eyes, the click was sent, the server ignored it silently and the bot
            // stood there like a fool. And 4.0 instead of 4.5, the same margin as
            // elsewhere.
            double onClick = p.getEyePosition().distanceTo(Vec3.atCenterOf(bed));
            if (onClick > 4.0) {
                return String.format("{\"ok\":false,\"far\":true,"
                        + "\"bed\":{\"x\":%d,\"y\":%d,\"z\":%d},"
                        + "\"distance\":%.1f,\"error\":\"the bed is %.1f away; "
                        + "I have to get closer first\"}",
                        bed.getX(), bed.getY(), bed.getZ(),
                        onClick, onClick);
            }

            lieDown(mc, p, bed);
            // The SERVER decides whether it lies down, and it may refuse for two reasons
            // that are not failures: daytime, or a monster nearby. Here it only says it
            // was requested.
            return String.format("{\"ok\":true,\"requested\":true,"
                    + "\"bed\":{\"x\":%d,\"y\":%d,\"z\":%d},"
                    + "\"note\":\"the server accepts it; look at 'sleeping' in the "
                    + "state. One only sleeps at night or in a storm, and not with "
                    + "monsters nearby\"}",
                    bed.getX(), bed.getY(), bed.getZ());
        });
    }

    /**
     * The click on the bed. The SERVER decides whether it lies down: in daytime or with a
     * monster nearby it refuses, and the click goes out anyway.
     */
    private static void lieDown(Minecraft mc, LocalPlayer p, BlockPos bed) {
        Vec3 center = Vec3.atCenterOf(bed);
        p.lookAt(EntityAnchorArgument.Anchor.EYES, center);
        mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND,
                new BlockHitResult(center, Direction.UP, bed, false));
        p.swing(InteractionHand.MAIN_HAND);
        Logbook.note("sleep", String.format("I lie down at %d %d %d",
                bed.getX(), bed.getY(), bed.getZ()));
    }

    /**
     * Another bot (my guard or my boss) is going to sleep there and asks me to sleep too:
     * sleepAlone handles it. Body to body (Guards).
     */
    private String sleeps(Map<String, String> q) throws Exception {
        int x = Request.whole(q, "x"), y = Request.whole(q, "y"), z = Request.whole(q, "z");
        return inGame(() -> {
            String negative = noGame();
            if (negative != null) return negative;
            sleepRequestMs = System.currentTimeMillis();
            suggestedBed = new BlockPos(x, y, z);
            Logbook.note("sleep", String.format(
                    "my partner is going to sleep at %d %d %d and asks me to sleep", x, y, z));
            return "{\"ok\":true}";
        });
    }

    // ---- Sleeping ON ITS OWN when phantoms prowl. When phantoms circle a bot, it is
    // better for it to sleep automatically; the guard coordinates with its boss. Body
    // only, no brain: the brain gets ONE notice at dawn so it resumes what it left, or
    // one if there is no bed or it does not manage. Guard and boss tell each other
    // through /asleep: the night only passes if they all sleep.
    private int sleepTicks, bedTrips, bedClicks;
    private BlockPos bedToSleep, suggestedBed;
    private long sleepSinceMs, noSleepUntilMs, sleepRequestMs;
    private boolean sleptAlone, sleptByOrder;
    private static volatile boolean GOING_TO_SLEEP;

    /** On its way to bed on its own: the follower keeps the feet still. */
    static boolean goingToSleep() {
        return GOING_TO_SLEEP;
    }

    private void sleepAlone() {
        try {
            sleepAloneTick();
        } finally {
            GOING_TO_SLEEP = bedToSleep != null;
        }
    }

    private void sleepAloneTick() {
        if (++sleepTicks < 20) return;
        sleepTicks = 0;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || !p.isAlive()) return;
        long now = System.currentTimeMillis();
        if (p.isSleeping()) {
            // Asleep on its own (bedToSleep) or through the `sleep` tool (the brain
            // stopped fishing, placed the bed and lay down): both count for the dawn
            // notice. It used to be only the first, and after the brain's `sleep` nobody
            // resumed anything (once asleep, the fishing was never resumed).
            if (bedToSleep != null) sleptAlone = true;
            else sleptByOrder = true;
            return;
        }
        boolean night = isNight(p.level());
        if ((sleptAlone || sleptByOrder) && !night) {
            // Dawn: the brain resumes what it left, in a single turn.
            boolean alone = sleptAlone;
            sleptAlone = sleptByOrder = false;
            bedToSleep = null;
            Logbook.note("sleep", "dawn; I get up");
            Needs.warn("slept", alone
                    ? "I went to bed ON MY OWN last night because phantoms prowled (count "
                      + "reset) and it is dawn now. What I was doing stopped when "
                      + "I went to bed: resume it"
                    : "it is dawn and I got up (phantom count reset). "
                      + "If you left something half done when going to bed (fishing, fill job, "
                      + "hunt), resume it now");
            return;
        }
        if (!Preferences.is("sleep_alone")) return;
        if (!night) { bedToSleep = null; return; }
        if (now < noSleepUntilMs) return;
        boolean requested = now - sleepRequestMs < 90_000;
        if (bedToSleep == null) {
            if (lookout.fleeingACreeper()) return;
            boolean phantoms = lookout.phantomNear(mc, p) != null;
            if (!phantoms && !requested) return;
            // A bed in sight, or a remembered one; my partner's only if I have no other
            // (if it is taken, that will be seen and reported).
            BlockPos bed = bedNear(mc, p, 32);
            if (bed == null) bed = rememberedBed(p, 96);
            if (bed == null && requested && suggestedBed != null
                    && suggestedBed.distSqr(p.blockPosition()) < 96 * 96) bed = suggestedBed;
            if (bed == null) {
                noSleepUntilMs = now + 120_000;
                if (phantoms) {
                    Needs.warn("phantom", "phantoms prowl around me and I would go "
                            + "to sleep on my own, but I have no bed at hand (nor do I remember "
                            + "one within 96 blocks). Get a bed (3 wool + "
                            + "3 planks) or take me to one");
                }
                return;
            }
            bedToSleep = bed;
            sleepSinceMs = now;
            bedTrips = 0;
            bedClicks = 0;
            String because = phantoms ? "phantoms prowl" : "my partner asks me to";
            Logbook.note("sleep", String.format("%s: I go on my own to the bed at %d %d %d",
                    because, bed.getX(), bed.getY(), bed.getZ()));
            // Hands and feet free: sleeping stops everything else (the escort stays on;
            // the follower waits while goingToSleep).
            String reason = "going to sleep (" + because + ")";
            fillWorker.stop(reason);
            miner.stop(reason);
            hunter.stop(reason);
            archerUnit.stop(reason);
            fisher.stop(reason);
            traveler.stop(reason);
            farmer.stop(reason);
            Guards.warnSleep(bed);
            return;
        }
        // On the way to the bed.
        if (now - sleepSinceMs > 180_000 || bedTrips > 3) {
            Needs.warn("phantom", String.format("I tried to sleep on my own in the "
                    + "bed at %d %d %d because of the phantoms and could not (monsters "
                    + "nearby, bed taken or no path). You decide: another bed, "
                    + "clear it, or carry on", bedToSleep.getX(), bedToSleep.getY(), bedToSleep.getZ()));
            Logbook.note("sleep", "I could not lie down; leaving it for now");
            bedToSleep = null;
            noSleepUntilMs = now + 120_000;
            return;
        }
        if (traveler.traveling() || walker.walking()) return;
        double onClick = p.getEyePosition().distanceTo(Vec3.atCenterOf(bedToSleep));
        if (onClick > 4.0) {
            bedTrips++;
            if (p.blockPosition().distManhattan(bedToSleep) > 40) {
                traveler.begin(new Route.Point(bedToSleep.getX(), bedToSleep.getY(),
                        bedToSleep.getZ()), false);
                return;
            }
            ClientWorld world = new ClientWorld(mc.level);
            Route.Point here = whereAmI(world, p);
            Route.Point bed = new Route.Point(bedToSleep.getX(), bedToSleep.getY(), bedToSleep.getZ());
            Route.Options op = new Route.Options(safeFall(p.getHealth()), 8_000, false, true)
                    .breaking(Preferences.is("break_to_advance"));
            Route.Result r = Route.search(world, here, Route.Meta.near(bed, 2.0), op.withDeadline(40));
            if (!r.hasRoute() || walker.follow(r.steps(), x -> null) != null) {
                Logbook.note("sleep", "I find no path to the bed; retrying");
            }
            return;
        }
        lieDown(mc, p, bedToSleep);
        if (++bedClicks % 10 == 0) {
            Logbook.note("sleep", "I still cannot lie down (monster nearby or bed taken?)");
        }
    }

    /** The nearest remembered bed in this dimension within radius, or null. */
    private static BlockPos rememberedBed(LocalPlayer p, int radius) {
        String dim = Places.currentDimension();
        BlockPos best = null;
        double smaller = (double) radius * radius;
        for (Places.Place l : Places.of("bed")) {
            if (!dim.equals(l.dimension())) continue;
            double d = l.where().distSqr(p.blockPosition());
            if (d < smaller) {
                smaller = d;
                best = l.where();
            }
        }
        return best;
    }

    /**
     * Sets the spawn at a bed without sleeping. It is a game mechanic, not a trick: when
     * a bed is clicked the server records the respawn point there BEFORE checking the
     * time, so in daytime "you can only sleep at night" arrives with the spawn already
     * set.
     *
     * <p>At night it refuses on purpose. The click would be the same, but at that hour it
     * does lie down, and sleeping skips the night for the whole server, which is exactly
     * what it must not do on its own. That is what {@code /sleep} is for, asked for
     * separately and knowingly.
     *
     * <p>That the spawn was set cannot be checked from here: the respawn point lives on
     * the server and the client does not receive it. What is known is that the click went
     * out, and at which bed.
     */
    private String spawn(Map<String, String> q) throws Exception {
        double radius = Math.min(32, Math.max(1,
                Double.parseDouble(q.getOrDefault("radius", "16"))));
        return inGame(() -> {
            String negative = noGame();
            if (negative != null) return negative;
            Minecraft mc = Minecraft.getInstance();
            var p = mc.player;
            if (isNight(p.level()) || p.level().isThundering()) {
                return "{\"ok\":false,\"at_night\":true,\"error\":\"at this hour "
                       + "that click would really put me to bed, and sleeping skips the "
                       + "night for the whole server; the spawn is set by day. "
                       + "If what you want is for me to sleep, ask me\"}";
            }
            int r = (int) radius;
            BlockPos bed = bedNear(mc, p, r);
            if (bed == null) {
                return String.format("{\"ok\":false,\"error\":\"I see no "
                        + "bed within %d blocks\"}", r);
            }
            if (Places.remember("bed", bed)) {
                Logbook.note("places", String.format(
                        "I remember a bed at %d %d %d",
                        bed.getX(), bed.getY(), bed.getZ()));
            }
            // The same measurement as sleeping: the server validates the click from the
            // eyes, not the feet.
            double onClick = p.getEyePosition().distanceTo(Vec3.atCenterOf(bed));
            if (onClick > 4.0) {
                return String.format("{\"ok\":false,\"far\":true,"
                        + "\"bed\":{\"x\":%d,\"y\":%d,\"z\":%d},"
                        + "\"distance\":%.1f,\"error\":\"the bed is %.1f away; "
                        + "I have to get closer first\"}",
                        bed.getX(), bed.getY(), bed.getZ(),
                        onClick, onClick);
            }

            Vec3 center = Vec3.atCenterOf(bed);
            p.lookAt(EntityAnchorArgument.Anchor.EYES, center);
            mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND,
                    new BlockHitResult(center, Direction.UP, bed, false));
            p.swing(InteractionHand.MAIN_HAND);
            Logbook.note("spawn", String.format(
                    "I set my spawn at the bed at %d %d %d",
                    bed.getX(), bed.getY(), bed.getZ()));
            return String.format("{\"ok\":true,\"requested\":true,"
                    + "\"bed\":{\"x\":%d,\"y\":%d,\"z\":%d},"
                    + "\"note\":\"the server answers 'you can only sleep at "
                    + "night' and still sets the spawn: that message is not a "
                    + "failure. If the bed is covered or broken, it does not set it\"}",
                    bed.getX(), bed.getY(), bed.getZ());
        });
    }

    private String inventory(Map<String, String> q) throws Exception {
        return inGame(() -> {
            String negative = noGame();
            if (negative != null) return negative;
            var inv = Minecraft.getInstance().player.getInventory();
            List<String> things = new ArrayList<>();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                var stack = inv.getItem(i);
                if (stack.isEmpty()) continue;
                things.add(String.format(
                        "{\"inv_slot\":%d,\"in_hotbar\":%b,\"what\":\"%s\","
                        + "\"count\":%d,\"usable_for_building\":%b%s%s}",
                        i, i < 9,
                        BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath(),
                        stack.getCount(), Builder.isScaffold(stack),
                        wear(stack),
                        // Slots 36..39 are the body and 40 the off hand: without the tag
                        // they look like the backpack and the brain would believe that
                        // helmet is stored, not worn.
                        i >= 36 && i <= 39 ? ",\"placed\":true"
                                : i == 40 ? ",\"in_offhand\":true" : ""));
            }
            return String.format(
                    "{\"ok\":true,\"scaffold_in_hotbar\":%b,\"things\":[%s]}",
                    Builder.slotWithScaffold(Minecraft.getInstance().player) >= 0,
                    String.join(",", things));
        });
    }

    /**
     * The armor: look at it, put it on or take it off. Without parameters it says what it
     * is wearing; with {@code best=1} it puts on the best of the backpack; with {@code
     * put_on=id} that specific piece; with {@code take_off=id} it removes it. The
     * mechanics live in {@link Armor}.
     */
    private String armor(Map<String, String> q) throws Exception {
        String putOn = q.getOrDefault("put_on", "").trim().toLowerCase();
        String takeOff = q.getOrDefault("take_off", "").trim().toLowerCase();
        boolean best = "1".equals(q.getOrDefault("best", "").trim());
        return inGame(() -> {
            String negative = noGame();
            if (negative != null) return negative;
            Minecraft mc = Minecraft.getInstance();
            var p = mc.player;
            if (best) return Armor.best(mc, p);
            if (!putOn.isEmpty()) return Armor.putOn(mc, p, putOn);
            if (!takeOff.isEmpty()) return Armor.takeOff(mc, p, takeOff);
            return String.format("{\"ok\":true,\"placed\":%s}",
                    Armor.placement(p));
        });
    }

    /**
     * The life left in a tool, as a JSON fragment, or "" if that item does not wear out.
     *
     * <p>Without this nothing long could be planned: asked about its pickaxe, the only
     * answer it had was <i>"I do not know how much it has left"</i>. And a
     * `stone_pickaxe` has 131 uses, so an errand of 300 blocks is bound to end halfway,
     * but there was no way of knowing that BEFORE starting, which is when it helps.
     *
     * <p>The uses left are given, not the "damage": damage goes the other way and invites
     * reading it backwards exactly when it matters.
     */
    private static String wear(net.minecraft.world.item.ItemStack stack) {
        if (!stack.isDamageableItem()) return "";
        return String.format(",\"uses_left\":%d,\"uses_total\":%d",
                stack.getMaxDamage() - stack.getDamageValue(), stack.getMaxDamage());
    }

    /**
     * The whitelist of breakable blocks: view, extend or trim it.
     *
     * <p>Without parameters it returns the list. With {@code allow=id} or {@code
     * forbid=id} it changes it; the id is validated against the registry when allowing,
     * so a typo does not add orphan permissions ("stnoe") that look granted and authorize
     * nothing.
     *
     * <p>It does not go through {@code inGame}: permissions do not read the world, so
     * they can be checked even when the game is busy.
     */
    private String permissions(Map<String, String> q) {
        String give = q.getOrDefault("allow", "").trim().toLowerCase();
        String remove = q.getOrDefault("forbid", "").trim().toLowerCase();
        if (!give.isEmpty()) {
            var id = net.minecraft.resources.ResourceLocation
                    .tryParse("minecraft:" + give);
            if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
                return String.format("{\"ok\":false,\"error\":\"I do not know the "
                        + "block '%s'; ids go in English, like dirt or "
                        + "stone\"}", Request.escape(give));
            }
            BreakPermissions.allow(give);
            Logbook.note("permissions", "I was allowed to break " + give);
        } else if (!remove.isEmpty()) {
            if (!BreakPermissions.forbid(remove)) {
                return String.format("{\"ok\":false,\"error\":\"'%s' was not "
                        + "on the list\"}", Request.escape(remove));
            }
            Logbook.note("permissions", "I was forbidden to break " + remove);
        }
        List<String> ids = BreakPermissions.everyone();
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"can_break\":[");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(Request.escape(ids.get(i))).append('"');
        }
        return sb.append("]}").toString();
    }

    /**
     * Escorts a player, or stops doing so. It is following AND protecting them: the feet
     * come from the {@link Follower} and the eyes from the {@link Escort}.
     */
    private static String readDefaultEscort() {
        try {
            var f = java.nio.file.Path.of("config", "marionette-escort.txt");
            if (!java.nio.file.Files.exists(f)) return null;
            String s = java.nio.file.Files.readString(f).strip();
            return s.isEmpty() ? null : s;
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /**
     * No job under way: what the guard checks to return to its boss. Escorting does not
     * count, since that is exactly what is to be resumed.
     */
    private boolean noJob() {
        return !(fillWorker.working() || farmer.working() || miner.digging() || hunter.hunting()
                || traveler.traveling() || stripMiner.mining() || staircase.descending()
                || tamer.active() || follower.following() || walker.walking()
                || furnaceHandler.working());
    }

    /**
     * The guard returns to its boss on its own. Every two seconds: if it is not
     * escorting, has no job and has been still for ten seconds, it looks for its boss. If
     * it cannot see it, it notifies the brain (with a cooldown), which knows how to ask
     * where the boss is and go.
     */
    private void escortByDefault() {
        if (defaultEscort == null || escortSuspended) return;
        if (++defaultEscortTicks < 40) return;
        defaultEscortTicks = 0;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || !mc.player.isAlive()) return;
        if (escort.escorting() || !noJob()) return;
        if (Activity.idleMs() < 10_000) return;
        String negative = escort.begin(defaultEscort);
        if (negative == null) {
            Logbook.note("escort_status", "free again: back to escorting "
                    + defaultEscort);
            return;
        }
        Needs.warn("escort-default", String.format(
                "I am the guard of %s and I cannot see them to escort (%s); check "
                + "where they are with `players` and take me there with `go_to`",
                defaultEscort, negative));
    }

    private int dressingTicks;

    /**
     * It puts on armor by itself: every ten seconds, if it carries in the backpack a
     * piece better than the worn one (or the slot is empty), it puts it on with the same
     * mechanism as `equip_armor`. Otherwise a guard carried a full iron set in its
     * backpack until its brain had a turn. Armor.best already guards against open menus
     * and only notes when something changes.
     */
    private void dressAlone() {
        if (++dressingTicks < 200) return;
        dressingTicks = 0;
        if (!Preferences.is("dress_alone")) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || !p.isAlive() || eating()) return;
        if (p.containerMenu != p.inventoryMenu) return;
        Armor.best(mc, p);
    }

    private int harvestTicks;

    /**
     * Harvests and resows ON ITS OWN what is ripe in its farms, when free or escorting: a
     * farm nobody harvests feeds nobody. Only its own; others', if asked. Preference
     * harvest_alone.
     */
    private void harvestAlone() {
        if (++harvestTicks < 200) return;
        harvestTicks = 0;
        if (!Preferences.is("harvest_alone")) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || !p.isAlive()) return;
        if (lookout.fleeingACreeper() || !(noJob() || escort.escorting())) return;
        if (!farmer.hasMyRipe(12)) return;
        if (farmer.beginHarvest(12, true) == null) {
            Logbook.note("farm", "there are ripe crops in my field: harvesting them");
        }
    }

    /** Breaks blocks around and picks up what they drop. The `gather` tool uses it. */
    private String gather(Map<String, String> q) throws Exception {
        List<String> ids = List.of(q.getOrDefault("blocks", "").split(","));
        String item = q.getOrDefault("item", "").trim();
        int quantity = Integer.parseInt(q.getOrDefault("count", "16"));
        int radius = Integer.parseInt(q.getOrDefault("radius", "16"));
        return inGame(() -> {
            String negative = noGame();
            if (negative != null) return negative;
            fillWorker.stop("going gathering");
            traveler.stop("going gathering");
            String problem = farmer.beginGather(ids, item, quantity, radius);
            if (problem != null) {
                return "{\"ok\":false,\"error\":\"" + Request.escape(problem) + "\"}";
            }
            return "{\"ok\":true,\"startup\":true,\"note\":\"follow with /state (farm)\"}";
        });
    }

    /** Cuts grass for seeds. The `gather_seeds` tool uses it. */
    private String seeds(Map<String, String> q) throws Exception {
        int howMany = Integer.parseInt(q.getOrDefault("how_many", "16"));
        int radius = Integer.parseInt(q.getOrDefault("radius", "16"));
        return inGame(() -> {
            String negative = noGame();
            if (negative != null) return negative;
            fillWorker.stop("going to cut grass");
            traveler.stop("going to cut grass");
            String problem = farmer.beginSeeds(howMany, radius);
            if (problem != null) {
                return "{\"ok\":false,\"error\":\"" + Request.escape(problem) + "\"}";
            }
            return "{\"ok\":true,\"startup\":true,\"note\":\"follow with /state (farm)\"}";
        });
    }

    /** Fills buckets at an infinite source. The `collect_water` tool uses it. */
    private String water(Map<String, String> q) throws Exception {
        int radius = Integer.parseInt(q.getOrDefault("radius", "32"));
        return inGame(() -> {
            String negative = noGame();
            if (negative != null) return negative;
            fillWorker.stop("going for water");
            traveler.stop("going for water");
            String problem = farmer.beginWater(radius);
            if (problem != null) {
                return "{\"ok\":false,\"error\":\"" + Request.escape(problem) + "\"}";
            }
            return "{\"ok\":true,\"startup\":true,\"note\":\"follow with /state (farm)\"}";
        });
    }

    /**
     * Sows a farm: corner, size and seed. The `farm_crops` tool uses it. With allow=1 and
     * two corners, it notes someone else's farm as allowed.
     */
    private String farm(Map<String, String> q) throws Exception {
        if ("1".equals(q.get("allow"))) {
            BlockPos a = new BlockPos(Request.whole(q, "x1"), Request.whole(q, "y1"), Request.whole(q, "z1"));
            BlockPos b = new BlockPos(Request.whole(q, "x2"), Request.whole(q, "y2"), Request.whole(q, "z2"));
            String seedId = q.getOrDefault("seed", "wheat_seeds").trim().toLowerCase();
            return inGame(() -> {
                String problem = farmer.allow(a, b, seedId);
                if (problem != null) {
                    return "{\"ok\":false,\"error\":\"" + Request.escape(problem) + "\"}";
                }
                return "{\"ok\":true,\"allowed\":true}";
            });
        }
        int x = Request.whole(q, "x"), y = Request.whole(q, "y"), z = Request.whole(q, "z");
        int width = Integer.parseInt(q.getOrDefault("width", "5"));
        int length = Integer.parseInt(q.getOrDefault("length", "5"));
        String seed = q.getOrDefault("seed", "wheat_seeds").trim().toLowerCase();
        return inGame(() -> {
            String negative = noGame();
            if (negative != null) return negative;
            fillWorker.stop("going to sow");
            traveler.stop("going to sow");
            follower.stop("going to sow");
            String problem = farmer.beginSowing(new BlockPos(x, y, z), width, length, seed);
            if (problem != null) {
                return "{\"ok\":false,\"error\":\"" + Request.escape(problem) + "\"}";
            }
            return "{\"ok\":true,\"startup\":true,\"note\":\"follow with /state (farm), cut with /stop\"}";
        });
    }

    /** Harvests what is ripe around (and resows). mine=1: only my farms. */
    private String harvest(Map<String, String> q) throws Exception {
        int radius = Integer.parseInt(q.getOrDefault("radius", "12"));
        boolean ownedByMeList = "1".equals(q.get("mine"));
        return inGame(() -> {
            String negative = noGame();
            if (negative != null) return negative;
            fillWorker.stop("going to harvest");
            traveler.stop("going to harvest");
            String problem = farmer.beginHarvest(radius, ownedByMeList);
            if (problem != null) {
                return "{\"ok\":false,\"error\":\"" + Request.escape(problem) + "\"}";
            }
            return "{\"ok\":true,\"startup\":true,\"note\":\"follow with /state (farm), cut with /stop\"}";
        });
    }

    /** Own wolves at most: more is a pack that does not fit in a tunnel. */
    private static final int WOLVES_MAX = 3;
    /** How far it looks for a wild wolf to tame. */
    private static final double WOLF_NEAR = 16.0;
    private int wolfTicks;
    private long noTamingUntil;

    /**
     * Tames wild wolves ON ITS OWN: if there is one nearby, it carries bones, has no more
     * than {@link #WOLVES_MAX} and is free or escorting, it goes for it. A tamed wolf
     * fights for the bot, and a bot working underground (or the guard protecting it) is
     * glad of the company. Turned off with the tame_wolves preference.
     */
    private void tameWolvesAlone() {
        if (++wolfTicks < 60) return;                 // every 3 s
        wolfTicks = 0;
        if (!Preferences.is("tame_wolves")) return;
        if (System.currentTimeMillis() < noTamingUntil) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || !p.isAlive()) return;
        if (p.getHealth() < 10.0f || lookout.fleeingACreeper()) return;
        if (tamer.active()) return;
        if (!(noJob() || escort.escorting())) return;
        // No bones, no wolf; but if there are bones on the ground (a skeleton that just
        // fell) they are picked up: that is what makes this really happen and not just by
        // chance.
        if (quantityCarried(p, "bone") == 0) {
            net.minecraft.world.entity.item.ItemEntity bone = null;
            for (var e : mc.level.getEntitiesOfClass(
                    net.minecraft.world.entity.item.ItemEntity.class,
                    p.getBoundingBox().inflate(12.0))) {
                if (!BuiltInRegistries.ITEM.getKey(e.getItem().getItem())
                        .getPath().equals("bone")) continue;
                if (bone == null || p.distanceTo(e) < p.distanceTo(bone)) bone = e;
            }
            if (bone != null && !walker.walking()) {
                ClientWorld world = new ClientWorld(mc.level);
                Route.Point here = whereAmI(world, p);
                Route.Point goal = new Route.Point((int) Math.floor(bone.getX()),
                        (int) Math.floor(bone.getY()), (int) Math.floor(bone.getZ()));
                Route.Result r = Route.search(world, here, Route.Meta.near(goal, 0.5),
                        new Route.Options(safeFall(p.getHealth()), 4_000, false, true)
                                .withDeadline(20));
                if (r.hasRoute() && r.steps().size() > 1
                        && walker.follow(r.steps(), x -> null, 0.4) == null) {
                    Logbook.note("wolves", "bones on the ground "
                            + (int) p.distanceTo(bone) + " blocks away: going for them");
                }
                noTamingUntil = System.currentTimeMillis() + 15_000;
                return;
            }
            // A wolf in sight and no bones: the spot is noted and said once, to come back
            // with bones (or send the guard).
            for (var wolf : mc.level.getEntitiesOfClass(
                    net.minecraft.world.entity.animal.Wolf.class,
                    p.getBoundingBox().inflate(WOLF_NEAR))) {
                if (wolf.isTame() || !wolf.isAlive()) continue;
                if (Places.remember("wolf", wolf.blockPosition())) {
                    Logbook.note("wolves", "wild wolf seen without bones: "
                            + "I note the spot " + wolf.blockPosition().toShortString());
                }
                Needs.warn("wolf-no-bones", String.format(
                        "I saw a wild wolf at %s and carry no bones; I noted it "
                        + "as a 'wolf' place. Bones are dropped by "
                        + "skeletons: with bones I come back and tame it",
                        wolf.blockPosition().toShortString()));
                break;
            }
            noTamingUntil = System.currentTimeMillis() + 60_000;
            return;
        }
        var zone = p.getBoundingBox().inflate(WOLF_NEAR);
        int ownedByMeList = 0;
        net.minecraft.world.entity.animal.Wolf wild = null;
        for (var wolf : mc.level.getEntitiesOfClass(
                net.minecraft.world.entity.animal.Wolf.class,
                p.getBoundingBox().inflate(48.0))) {
            if (wolf.isTame()) {
                if (wolf.isOwnedBy(p)) ownedByMeList++;
            } else if (wolf.isAlive() && zone.intersects(wolf.getBoundingBox())
                    && (wild == null
                        || p.distanceTo(wolf) < p.distanceTo(wild))) {
                wild = wolf;
            }
        }
        if (wild == null || ownedByMeList >= WOLVES_MAX) return;
        String negative = tamer.begin(Tamer.Mode.TAME, "wolf", 1);
        if (negative == null) {
            Logbook.note("wolves", String.format(
                    "wild wolf %d blocks away and I carry bones: going to tame it "
                    + "(I have %d)", (int) p.distanceTo(wild), ownedByMeList));
        } else {
            Logbook.note("wolves", "I could not go and tame: " + negative);
        }
        noTamingUntil = System.currentTimeMillis() + 30_000;   // no loops
    }

    /**
     * Another bot (my boss, usually) asks me to MAKE WAY: step away from the segment a-b
     * to r blocks without dropping the escort. Its body sends it, not a brain (see
     * Guards).
     */
    private String stepAsideNow(Map<String, String> q) throws Exception {
        var a = new net.minecraft.world.phys.Vec3(real(q, "x1"), real(q, "y1"), real(q, "z1"));
        var b = new net.minecraft.world.phys.Vec3(real(q, "x2"), real(q, "y2"), real(q, "z2"));
        double r = real(q, "r");
        return inGame(() -> {
            String negative = noGame();
            if (negative != null) return negative;
            follower.stepAside(a, b, r);
            return "{\"ok\":true}";
        });
    }

    private static double real(Map<String, String> q, String key) {
        String v = q.get(key);
        if (v == null) throw new IllegalArgumentException("missing " + key);
        return Double.parseDouble(v);
    }

    private String startEscort(Map<String, String> q) throws Exception {
        String a = q.getOrDefault("to", "").trim();
        boolean leave = "1".equals(q.get("stop_flag"));
        return inGame(() -> {
            if (leave) {
                escort.stop("I was asked to stop escorting");
                follower.stop("I was asked to stop escorting");
                escortSuspended = true;     // until they ask again
                return "{\"ok\":true,\"escorting\":false}";
            }
            escortSuspended = false;
            itemRecovery.noInitiative(false);
            if (a.isEmpty()) {
                return "{\"ok\":false,\"error\":\"tell me who: "
                       + "/escort?to=Name, or stop_flag=1 to stop\"}";
            }
            fillWorker.stop("going to escort " + a);
            miner.stop("going to escort " + a);
            hunter.stop("going to escort " + a);
            archerUnit.stop("going to escort " + a);
            fisher.stop("going to escort " + a);
            traveler.stop("going to escort " + a);
            String negative = escort.begin(a);
            if (negative != null) {
                return String.format("{\"ok\":false,\"error\":\"%s\"}",
                        Request.escape(negative));
            }
            return String.format("{\"ok\":true,\"escorting\":true,"
                    + "\"to\":\"%s\"}", Request.escape(a));
        });
    }

    /**
     * Follows a player, or stops. The continuous work is done by the {@link Follower} in
     * the tick; here it is only turned on and off.
     *
     * <p>Starting to follow STOPS the job and the digging in progress: the feet are not
     * shared, and two tasks fighting over the walker looks like a drunk bot.
     */
    private String follow(Map<String, String> q) throws Exception {
        String a = q.getOrDefault("to", "").trim();
        boolean leave = "1".equals(q.get("stop_flag"));
        return inGame(() -> {
            if (leave) {
                escort.stop("I was asked to stop following");
                follower.stop("I was asked to stop following");
                return "{\"ok\":true,\"following\":false}";
            }
            if (a.isEmpty()) {
                return "{\"ok\":false,\"error\":\"tell me who: "
                       + "/follow?to=Name, or stop_flag=1 to stop\"}";
            }
            fillWorker.stop("going to follow " + a);
            miner.stop("going to follow " + a);
            hunter.stop("going to follow " + a);
            archerUnit.stop("going to follow " + a);
            fisher.stop("going to follow " + a);
            traveler.stop("going to follow " + a);
            String negative = follower.begin(a);
            if (negative != null) {
                return String.format("{\"ok\":false,\"error\":\"%s\"}",
                        Request.escape(negative));
            }
            return String.format("{\"ok\":true,\"following\":true,"
                    + "\"to\":\"%s\"}", Request.escape(a));
        });
    }

    /**
     * Behaviour preferences: view them or change one. It does not go through the game
     * thread (they do not read the world), and keys are validated in {@link Preferences},
     * which only accepts the ones that exist.
     */
    private String preferences(Map<String, String> q) {
        String key = q.getOrDefault("place", "").trim().toLowerCase();
        if (!key.isEmpty()) {
            boolean value = Boolean.parseBoolean(
                    q.getOrDefault("value", "false").trim());
            String negative = Preferences.place(key, value);
            if (negative != null) {
                return String.format("{\"ok\":false,\"error\":\"%s\"}",
                        Request.escape(negative));
            }
            Logbook.note("preferences", key + " is now " + value);
        }
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"preferences\":{");
        boolean firstItem = true;
        for (var e : Preferences.allItems().entrySet()) {
            if (!firstItem) sb.append(',');
            firstItem = false;
            sb.append('\"').append(e.getKey()).append("\":")
              .append(e.getValue());
        }
        return sb.append("}}").toString();
    }

    /**
     * Uses a furnace: {@code mode} = load, take out or look. Loading puts in ALL the
     * stacks it carries of the requested item (and of the fuel, if given); the game's
     * menu routes them by itself. The immediate answer is "on it" (the menu takes a round
     * trip to the server) and the outcome stays in /state.
     */
    private String furnace(Map<String, String> q) throws Exception {
        int x = Integer.parseInt(require(q, "x"));
        int y = Integer.parseInt(require(q, "y"));
        int z = Integer.parseInt(require(q, "z"));
        String mode = q.getOrDefault("mode", "look").trim().toLowerCase();
        String what = q.getOrDefault("what", "").trim().toLowerCase();
        String fuelItem = q.getOrDefault("fuel", "").trim().toLowerCase();
        if (mode.equals("load") && what.isEmpty()) {
            return "{\"ok\":false,\"error\":\"load needs what=id "
                   + "(what you want to smelt)\"}";
        }
        return inGame(() -> {
            String negative = noGame();
            if (negative != null) return negative;
            String failure = furnaceHandler.begin(mode, new BlockPos(x, y, z),
                    what, fuelItem);
            if (failure != null) {
                return String.format("{\"ok\":false,\"error\":\"%s\"}",
                        Request.escape(failure));
            }
            return "{\"ok\":true,\"on_it\":true,\"note\":\"the outcome "
                   + "stays in the state (with_furnace/outcome)\"}";
        });
    }

    /** The useful place type this block id is, or null. */
    private static String placeType(String id) {
        if (id.equals("crafting_table")) return "table";
        if (id.contains("furnace") || id.equals("smoker")) return "furnace";
        if (id.endsWith("_bed")) return "bed";
        if (id.equals("chest") || id.equals("barrel")) return "chest";
        // Portals are not "placed" (they are born from lighting a frame), but they are
        // walked through and looked at, and that does come through here.
        if (id.equals("nether_portal") || id.equals("end_portal")
                || id.equals("end_gateway")) {
            return "portal";
        }
        return null;
    }

    /**
     * The memory of useful places of THIS server: view it, note or forget. The decision
     * to use it (going to the remembered bed vs crafting a new table) belongs to the
     * brain; only the memories live here.
     */
    private String places(Map<String, String> q) throws Exception {
        String remember = q.getOrDefault("remember", "").trim().toLowerCase();
        boolean forget = "1".equals(q.get("forget"));
        return inGame(() -> {
            var p = Minecraft.getInstance().player;
            if (!remember.isEmpty() || forget) {
                int x = Integer.parseInt(require(q, "x"));
                int y = Integer.parseInt(require(q, "y"));
                int z = Integer.parseInt(require(q, "z"));
                if (forget) {
                    if (!Places.forget(new BlockPos(x, y, z))) {
                        return "{\"ok\":false,\"error\":\"that spot was not "
                               + "in my memory\"}";
                    }
                    Logbook.note("places", String.format(
                            "I forgot the place %d %d %d", x, y, z));
                } else {
                    if (!Places.TYPES.contains(remember)) {
                        return String.format("{\"ok\":false,\"error\":\"I do not "
                                + "know the type '%s'; the ones there are: %s\"}",
                                Request.escape(remember),
                                String.join(", ", Places.TYPES));
                    }
                    String label = q.getOrDefault("label", "").trim();
                    // A point is a place someone named; without a name there is no way to
                    // ask for it later, and it would be junk in the memory.
                    if (Places.requiresName(remember) && label.isEmpty()) {
                        return "{\"ok\":false,\"error\":\"a point needs "
                               + "a name (label): without it I cannot know which "
                               + "spot you ask for later\"}";
                    }
                    // A portal is noted by its BOTTOM tile, the one with a floor: the top
                    // row is portal too and there is no route to it. That is how the bot
                    // ended up on the frame, "stuck on the edge".
                    BlockPos whereToNote = new BlockPos(x, y, z);
                    if (remember.equals("portal")) {
                        whereToNote = Traveler.adjustPortal(
                                Minecraft.getInstance(), whereToNote);
                    }
                    if (Places.remember(remember, whereToNote, label)) {
                        Logbook.note("places", String.format(
                                "I remember %s at %d %d %d (I was told%s)",
                                remember, whereToNote.getX(),
                                whereToNote.getY(), whereToNote.getZ(),
                                label.isEmpty() ? ""
                                        : ": '" + label + "'"));
                    }
                }
            }
            String type = q.getOrDefault("type", "").trim().toLowerCase();
            String name = q.getOrDefault("name", "").trim();
            var list = Places.of(type);
            // Searching by name is what turns "go to the factory" into coordinates. Empty
            // is not an error: it means I do not have it noted.
            if (!name.isEmpty()) list.retainAll(Places.called(name));
            String here = Places.currentDimension();
            if (p != null) {
                // The ones in THIS dimension first and by proximity; the ones from
                // another dimension last and without distance, because across dimensions
                // distance means nothing: in the Nether the same coordinates are another
                // place (at one eighth of the scale).
                list.sort((a, b) -> {
                    boolean gives = a.dimension().equals(here);
                    boolean db = b.dimension().equals(here);
                    if (gives != db) return gives ? -1 : 1;
                    return Double.compare(
                            a.where().distToCenterSqr(p.position()),
                            b.where().distToCenterSqr(p.position()));
                });
            }
            StringBuilder sb = new StringBuilder(
                    "{\"ok\":true,\"dimension\":\"" + here
                    + "\",\"places\":[");
            boolean firstItem = true;
            for (var l : list) {
                if (!firstItem) sb.append(',');
                firstItem = false;
                BlockPos b = l.where();
                boolean same = l.dimension().equals(here);
                sb.append(String.format(
                        "{\"type\":\"%s\",\"x\":%d,\"y\":%d,\"z\":%d,"
                        + "\"dimension\":\"%s\"%s%s}",
                        l.type(), b.getX(), b.getY(), b.getZ(),
                        Request.escape(l.dimension()),
                        same && p != null
                                ? String.format(",\"distance\":%.0f",
                                        Math.sqrt(b.distToCenterSqr(p.position())))
                                : ",\"other_dimension\":true",
                        l.label().isEmpty() ? ""
                                : ",\"label\":\""
                                  + Request.escape(l.label()) + "\""));
            }
            return sb.append("]}").toString();
        });
    }

    /**
     * Uses a chest: {@code mode} = put, take or look, with {@code what} to put or take.
     * It moves ALL the stacks of that item; the outcome stays in the state
     * (with_chest/outcome).
     */
    private String chest(Map<String, String> q) throws Exception {
        int x = Integer.parseInt(require(q, "x"));
        int y = Integer.parseInt(require(q, "y"));
        int z = Integer.parseInt(require(q, "z"));
        String mode = q.getOrDefault("mode", "look").trim().toLowerCase();
        String what = q.getOrDefault("what", "").trim().toLowerCase();
        if ((mode.equals("stop_flag") || mode.equals("take")) && what.isEmpty()) {
            return "{\"ok\":false,\"error\":\"put/take need "
                   + "what=id of the item\"}";
        }
        int quantity = 0;                      // 0 = everything, as always
        try {
            quantity = Integer.parseInt(q.getOrDefault("count", "0").trim());
        } catch (NumberFormatException ignored) { /* everything */ }
        int n = quantity;
        return inGame(() -> {
            String negative = noGame();
            if (negative != null) return negative;
            String failure = chestHandler.begin(mode, new BlockPos(x, y, z), what, n);
            if (failure != null) {
                return String.format("{\"ok\":false,\"error\":\"%s\"}",
                        Request.escape(failure));
            }
            return "{\"ok\":true,\"on_it\":true,\"note\":\"the outcome "
                   + "stays in the state (with_chest/outcome)\"}";
        });
    }

    /**
     * Hunting: chase and kill {@code quantity} mobs of a {@code type}. Players only with
     * the hunt_players preference: the filter lives in the {@link Hunter}, in the code.
     */
    private String hunt(Map<String, String> q) throws Exception {
        String type = require(q, "type").trim().toLowerCase();
        int quantity = Integer.parseInt(q.getOrDefault("count", "1"));
        boolean search = "1".equals(q.get("search"));
        String toward = q.getOrDefault("toward", "");
        return inGame(() -> {
            String negative = noGame();
            if (negative != null) return negative;
            // One task per pair of feet: hunting displaces following and the job.
            escort.stop("I was asked to hunt");
            follower.stop("I was asked to hunt");
            fillWorker.stop("I was asked to hunt");
            fisher.stop("I was asked to hunt");
            tamer.stop("I was asked to hunt");
            traveler.stop("I was asked to hunt");
            archerUnit.stop("I was asked to hunt");
            String failure = hunter.begin(type, quantity, search, toward);
            if (failure != null) {
                return String.format("{\"ok\":false,\"error\":\"%s\"}",
                        Request.escape(failure));
            }
            return "{\"ok\":true,\"hunting\":true,\"note\":\"follow the "
                   + "progress in the state (hunt)\"}";
        });
    }

    /**
     * Shearing: like hunting, but with shears and only sheep with wool. The same {@link
     * Hunter} does it in its other mode; {@code quantity} 0 = every one it sees.
     */
    private String shear(Map<String, String> q) throws Exception {
        int quantity = Integer.parseInt(q.getOrDefault("count", "0"));
        boolean search = "1".equals(q.get("search"));
        String toward = q.getOrDefault("toward", "");
        return inGame(() -> {
            String negative = noGame();
            if (negative != null) return negative;
            escort.stop("I was asked to shear");
            follower.stop("I was asked to shear");
            fillWorker.stop("I was asked to shear");
            fisher.stop("I was asked to shear");
            tamer.stop("I was asked to shear");
            traveler.stop("I was asked to shear");
            archerUnit.stop("I was asked to shear");
            String failure = hunter.shear(quantity, search, toward);
            if (failure != null) {
                return String.format("{\"ok\":false,\"error\":\"%s\"}",
                        Request.escape(failure));
            }
            return "{\"ok\":true,\"shearing\":true,\"note\":\"follow the "
                   + "progress in the state (shear_job)\"}";
        });
    }

    /**
     * Taming: wolves with bones, cats with fish, parrots with seeds. The continuous work
     * is done by the {@link Tamer}.
     */
    private String tame(Map<String, String> q) throws Exception {
        String type = require(q, "type").trim().toLowerCase();
        int quantity = Integer.parseInt(q.getOrDefault("count", "1"));
        return withAnimals(Tamer.Mode.TAME, type, quantity, "tame");
    }

    /** Breeding: their food to two adults and wait for the baby. */
    private String breed(Map<String, String> q) throws Exception {
        String type = require(q, "type").trim().toLowerCase();
        int pairs = Integer.parseInt(q.getOrDefault("pairs", "1"));
        // What to feed them with (id), if given; empty = the Tamer chooses.
        String food = q.getOrDefault("food", "").trim().toLowerCase();
        if (food.startsWith("minecraft:")) food = food.substring(10);
        return withAnimals(Tamer.Mode.BREED, type, pairs, "breed", food);
    }

    /**
     * Sits or stands up its own pets (wolves, cats, parrots): touching them with an empty
     * hand. Without a type, every one it sees.
     */
    private String pets(Map<String, String> q) throws Exception {
        String action = require(q, "action").trim().toLowerCase();
        String type = q.getOrDefault("type", "").trim().toLowerCase();
        int quantity = Integer.parseInt(q.getOrDefault("count",
                String.valueOf(Tamer.MAX)));
        Tamer.Mode mode = switch (action) {
            case "sit" -> Tamer.Mode.SIT;
            case "stand" -> Tamer.Mode.STAND;
            default -> null;
        };
        if (mode == null) {
            return "{\"ok\":false,\"error\":\"action has to be sit or "
                   + "stand\"}";
        }
        return withAnimals(mode, type, quantity, action + " my pets");
    }

    private String withAnimals(Tamer.Mode mode, String type, int quantity,
                               String verb) throws Exception {
        return withAnimals(mode, type, quantity, verb, "");
    }

    private String withAnimals(Tamer.Mode mode, String type, int quantity,
                               String verb, String food) throws Exception {
        return inGame(() -> {
            String negative = noGame();
            if (negative != null) return negative;
            // One task per pair of feet, here too.
            escort.stop("I was asked to " + verb);
            follower.stop("I was asked to " + verb);
            fillWorker.stop("I was asked to " + verb);
            fisher.stop("I was asked to " + verb);
            traveler.stop("I was asked to " + verb);
            archerUnit.stop("I was asked to " + verb);
            hunter.stop("I was asked to " + verb);
            String failure = tamer.begin(mode, type, quantity, food);
            if (failure != null) {
                return String.format("{\"ok\":false,\"error\":\"%s\"}",
                        Request.escape(failure));
            }
            return "{\"ok\":true,\"active\":true,\"note\":\"follow the "
                   + "progress in the state (breeding)\"}";
        });
    }

    /**
     * Killing with arrows: take down {@code quantity} mobs of a {@code type} with the
     * bow. The rule that separates it from {@link #hunt}: the bow is for killing, not for
     * hunting. The continuous work is done by the {@link Archer}.
     */
    private String kill(Map<String, String> q) throws Exception {
        String type = require(q, "type").trim().toLowerCase();
        int quantity = Integer.parseInt(q.getOrDefault("count", "1"));
        return inGame(() -> {
            String negative = noGame();
            if (negative != null) return negative;
            // One task per pair of feet, here too.
            escort.stop("I was asked to kill with arrows");
            follower.stop("I was asked to kill with arrows");
            fillWorker.stop("I was asked to kill with arrows");
            fisher.stop("I was asked to kill with arrows");
            tamer.stop("I was asked to kill with arrows");
            hunter.stop("I was asked to kill with arrows");
            traveler.stop("I was asked to kill with arrows");
            // Without a bow or arrows it does NOT refuse: it goes with the sword, through
            // the Hunter, and that is it; it should not say it has no bow or arrows, it
            // should just go. The same with what the bow cannot handle (breeze,
            // enderman).
            String withoutBow = Bow.ready(Minecraft.getInstance().player);
            boolean withSword = withoutBow != null
                    || type.contains("breeze") || type.contains("enderman");
            String failure = withSword ? hunter.killWithSword(type, quantity)
                                   : archerUnit.begin(type, quantity);
            if (failure != null) {
                return String.format("{\"ok\":false,\"error\":\"%s\"}",
                        Request.escape(failure));
            }
            if (withSword) {
                return "{\"ok\":true,\"killing\":true,\"with_sword\":true,"
                       + "\"note\":\"no bow or arrows: going by sword; follow the "
                       + "progress in the state (hunt)\"}";
            }
            return "{\"ok\":true,\"killing\":true,\"note\":\"follow the "
                   + "progress in the state (archery)\"}";
        });
    }

    /**
     * Standing orders: list them, note one or delete one. The mod does not interpret
     * them: they are text for the brain, stored by category. It does not go through the
     * game thread: pure file.
     */
    private String orders(Map<String, String> q) {
        String note = q.getOrDefault("annotate", "").trim().toLowerCase();
        String delete = q.getOrDefault("delete", "").trim().toLowerCase();
        if (!note.isEmpty()) {
            String negative = StandingOrders.note(note,
                    q.getOrDefault("text", "").trim());
            if (negative != null) {
                return String.format("{\"ok\":false,\"error\":\"%s\"}",
                        Request.escape(negative));
            }
            Logbook.note("orders", String.format("new order of %s: %s",
                    note, q.getOrDefault("text", "")));
        } else if (!delete.isEmpty()) {
            int number = Integer.parseInt(q.getOrDefault("number", "0"));
            String text = StandingOrders.delete(delete, number);
            if (text == null) {
                return String.format("{\"ok\":false,\"error\":\"there is no "
                        + "order %d in %s\"}", number, Request.escape(delete));
            }
            Logbook.note("orders", String.format(
                    "deleted order of %s: %s", delete, text));
        }
        String category = q.getOrDefault("category", "").trim().toLowerCase();
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"orders\":[");
        boolean firstItem = true;
        java.util.Map<String, Integer> counter = new java.util.HashMap<>();
        for (var o : StandingOrders.of(category)) {
            int n = counter.merge(o.category(), 1, Integer::sum);
            if (!firstItem) sb.append(',');
            firstItem = false;
            sb.append(String.format(
                    "{\"category\":\"%s\",\"number\":%d,\"text\":\"%s\"}",
                    o.category(), n, Request.escape(o.text())));
        }
        return sb.append("]}").toString();
    }

    /**
     * Fishes in the nearest water. The continuous work is done by the {@link Fisher} in
     * the tick; here it is only switched on. Fishing takes feet and hands: it displaces
     * following, the job, digging and hunting.
     */
    private String fish(Map<String, String> q) throws Exception {
        return inGame(() -> {
            String negative = noGame();
            if (negative != null) return negative;
            escort.stop("I was asked to fish");
            follower.stop("I was asked to fish");
            fillWorker.stop("I was asked to fish");
            miner.stop("I was asked to fish");
            hunter.stop("I was asked to fish");
            archerUnit.stop("I was asked to fish");
            traveler.stop("I was asked to fish");
            String failure = fisher.begin();
            if (failure != null) {
                return String.format("{\"ok\":false,\"error\":\"%s\"}",
                        Request.escape(failure));
            }
            return "{\"ok\":true,\"fishing\":true,\"note\":\"follow the "
                   + "progress in the state (fishing_job)\"}";
        });
    }

    /**
     * Leaves the server keeping the client ALIVE at the title screen, ready for a {@code
     * connect}. It is the exact sequence of the "Disconnect" button of the pause menu.
     * Outside the world there is no voice nor ears, so coming back is not asked for in
     * the chat: whoever runs the bot uses {@code launcher/connect_bot.sh}.
     *
     * <p>No lock here on purpose: the brain has no tool that reaches this. Only the bridge
     * calls it, when the server passes on a {@code /marionette bot <bot> logoff}, and the
     * server is what knows for sure who ran that command. A lock here used to check a name
     * that the brain wrote, which a player could talk it into faking.
     */
    private String disconnect(Map<String, String> q) throws Exception {
        return inGame(() -> {
            String negative = noGame();
            if (negative != null) return "{\"ok\":true,\"note\":\"I was already out\"}";
            Minecraft mc = Minecraft.getInstance();
            // Everything stopped before leaving: a worker digging during the goodbye is a
            // click against a world that no longer exists.
            walker.stop("logging off");
            miner.stop("logging off");
            fillWorker.stop("logging off");
            escort.stop("logging off");
            follower.stop("logging off");
            furnaceHandler.stop("logging off");
            chestHandler.stop("logging off");
            hunter.stop("logging off");
            archerUnit.stop("logging off");
            fisher.stop("logging off");
            tamer.stop("logging off");
            traveler.stop("logging off");
            Logbook.note("connection", "I logged off the server");
            mc.level.disconnect();
            mc.disconnect();
            mc.setScreen(new net.minecraft.client.gui.screens.TitleScreen());
            return "{\"ok\":true,\"disconnected\":true}";
        });
    }

    private String stop(Map<String, String> q) throws Exception {
        return inGame(() -> {
            Logbook.note("stop", "I was asked to stop");
            walker.stop("I was asked to stop");
            miner.stop("I was asked to stop");
            fillWorker.stop("I was asked to stop");
            farmer.stop("I was asked to stop");
            stripMiner.stop("I was asked to stop");
            staircase.stop("I was asked to stop");
            escort.stop("I was asked to stop");
            follower.stop("I was asked to stop");
            furnaceHandler.stop("I was asked to stop");
            chestHandler.stop("I was asked to stop");
            hunter.stop("I was asked to stop");
            archerUnit.stop("I was asked to stop");
            fisher.stop("I was asked to stop");
            tamer.stop("I was asked to stop");
            rider.stop("I was asked to stop");
            shepherd.stop("I was asked to stop");
            traveler.stop("I was asked to stop");
            itemRecovery.stop("I was asked to stop");
            return "{\"ok\":true,\"stopped\":true}";
        });
    }

    // --- plumbing -------------------------------------------------------------

    private static String require(Map<String, String> q, String key) {
        String v = q.get(key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("missing " + key);
        }
        return v;
    }

    private interface RouteHandler {
        String respond(Map<String, String> query) throws Exception;
    }

    /**
     * The handlers that only look. Asking for the state is not having something to do,
     * and counting it as activity would leave the night routine and the idle notice
     * asleep forever: the bridge asks all the time.
     */
    private static final List<String> LOOK_ONLY = List.of(
            "/state", "/logbook", "/needs", "/inventory", "/light",
            "/places", "/permissions", "/preferences", "/orders", "/diary",
            "/people", "/selection", "/look", "/version");

    private void attend(HttpExchange x, RouteHandler m) throws IOException {
        if (!LOOK_ONLY.contains(x.getRequestURI().getPath())) {
            Activity.markPlace();
        }
        try {
            // A POST carries the parameters in its body: a blueprint is hundreds of cells
            // and that does not fit in a URL. The same format as the query.
            String raw = x.getRequestURI().getRawQuery();
            if ("POST".equalsIgnoreCase(x.getRequestMethod())) {
                String body = new String(x.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8);
                raw = (raw == null || raw.isEmpty()) ? body : raw + "&" + body;
            }
            respond(x, 200, m.respond(Request.query(raw)));
        } catch (IllegalArgumentException e) {
            respond(x, 400, "{\"ok\":false,\"error\":\""
                              + Request.escape(e.getMessage()) + "\"}");
        } catch (Exception e) {
            LOG.error("[marionette-bot] error handling {}", x.getRequestURI(), e);
            respond(x, 500, "{\"ok\":false,\"error\":\""
                              + Request.escape(String.valueOf(e)) + "\"}");
        }
    }

    private static void respond(HttpExchange x, int code, String json)
            throws IOException {
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "application/json");
        x.sendResponseHeaders(code, payload.length);
        try (OutputStream out = x.getResponseBody()) { out.write(payload); }
    }
}
