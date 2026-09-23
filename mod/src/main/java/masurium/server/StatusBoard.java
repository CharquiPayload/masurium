package masurium.server;

import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.numbers.BlankFormat;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.criteria.ObjectiveCriteria;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

/**
 * The sidebar with the state of EVERY bot at once.
 *
 * <p>The TAB list already shows the icon next to each name; this is the same, but always
 * in view and for everyone online, with the same icon and color as the TAB. It is turned
 * on with {@code /masurium scoreboard on} (an operator) and saved in {@code
 * masurium_scoreboard.properties}, next to the owners.
 *
 * <p>One line per bot IN THE GAME: a bot that is not connected has nothing to show. With
 * no bot connected at all the board is taken away entirely, rather than leaving an empty
 * "Bots" title on everyone's screen, and it comes back on its own when one joins.
 *
 * <p>Each bot is a fixed scoreboard holder ({@code masurium_<bot>}) whose text changes,
 * so a line does not flicker when the state changes. Numbers are hidden. It refreshes
 * every second, and only if something changed — except for the first pass of each
 * session, which always runs: the objective is saved in the world, and skipping that pass
 * left the previous session's lines on screen for bots that were no longer there.
 */
public final class StatusBoard {

    private static final Logger LOG = LogUtils.getLogger();
    private static final Path FILE = Path.of("masurium_scoreboard.properties");
    private static final String TARGET = "masurium";
    private static final int EVERY = 20;

    private final Tab tab;
    private final Owners owners;
    private volatile boolean enabled;
    private int ticks;
    private String lastOne = "";
    /** Whether this session has drawn once. Its first pass must never be skipped. */
    private boolean drawn;
    private final Set<String> headlines = new HashSet<>();

    StatusBoard(Tab tab, Owners owners) {
        this.tab = tab;
        this.owners = owners;
        load();
    }

    // ------------------------------------------------------------ persistence

    private void load() {
        if (!Files.exists(FILE)) return;
        Properties p = new Properties();
        try (var in = Files.newInputStream(FILE)) {
            p.load(in);
        } catch (IOException e) {
            LOG.error("[masurium] could not read {}", FILE, e);
            return;
        }
        enabled = "on".equalsIgnoreCase(p.getProperty("sidebar", "off").trim());
        LOG.info("[masurium] sidebar: {}", enabled ? "on" : "off");
    }

    private void save() {
        Properties p = new Properties();
        p.setProperty("sidebar", enabled ? "on" : "off");
        try (var out = Files.newOutputStream(FILE)) {
            p.store(out, "Sidebar status board with the state of every bot. "
                    + "Written by /masurium scoreboard.");
        } catch (IOException e) {
            LOG.error("[masurium] could not write {}", FILE, e);
        }
    }

    // ---------------------------------------------------------------- command

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        // It hangs from the same /masurium: the dispatcher merges the trees.
        event.getDispatcher().register(Commands.literal("masurium")
                .then(Commands.literal("scoreboard")
                        .requires(s -> s.hasPermission(2))
                        .then(Commands.literal("on").executes(c -> place(c, true)))
                        .then(Commands.literal("off").executes(c -> place(c, false)))));
    }

    private int place(com.mojang.brigadier.context.CommandContext<CommandSourceStack> c,
                      boolean yes) {
        enabled = yes;
        save();
        if (!yes) remove(c.getSource().getServer());
        else lastOne = "";   // redraw right away
        c.getSource().sendSuccess(() -> Component.literal(
                yes ? "Bot status board on" : "Bot status board off")
                .withStyle(yes ? ChatFormatting.GREEN : ChatFormatting.GRAY), true);
        return 1;
    }

    // ------------------------------------------------------------------- draw

    @SubscribeEvent
    public void onTick(ServerTickEvent.Post event) {
        if (++ticks < EVERY) return;
        ticks = 0;
        if (!enabled) return;
        MinecraftServer server = event.getServer();
        // The ONLINE bots with an owner or with a state sent by their bridge: a
        // disconnected bot has nothing to show on the board.
        Set<String> candidates = new TreeSet<>(owners.bots());
        Map<String, String> states = tab.everyone();
        candidates.addAll(states.keySet());
        Set<String> bots = new TreeSet<>();
        for (String bot : candidates) {
            boolean inside = server.getPlayerList().getPlayers().stream()
                    .anyMatch(j -> j.getGameProfile().getName().equalsIgnoreCase(bot));
            if (inside) bots.add(bot);
        }
        StringBuilder footprint = new StringBuilder();
        for (String bot : bots) {
            footprint.append(bot).append('=').append(states.get(bot)).append(';');
        }
        String now = footprint.toString();
        // The FIRST pass of a session always runs, even when nothing seems to have
        // changed. The objective and its lines are saved in the world, so after a
        // restart with no bot online the footprint was "" and so was lastOne: this
        // return fired, the saved lines were never cleaned, and the sidebar kept
        // showing the previous session's "⇄ Alice  idle" for a bot that was not there.
        if (drawn && now.equals(lastOne)) return;
        lastOne = now;
        drawn = true;

        // No bot in the game: take the board away instead of leaving an empty "Bots"
        // title on everyone's screen. It comes back by itself when one connects.
        if (bots.isEmpty()) {
            remove(server);
            return;
        }

        ServerScoreboard sb = server.getScoreboard();
        Objective obj = sb.getObjective(TARGET);
        // The objective and its lines are SAVED in the world: after a restart the
        // previous session's lines were still there ("○ Alice  offline") and this board
        // did not know them. On the first draw of the session the saved ones are thrown
        // away and it starts clean.
        if (obj != null && headlines.isEmpty()) {
            sb.removeObjective(obj);
            obj = null;
        }
        if (obj == null) {
            obj = sb.addObjective(TARGET, ObjectiveCriteria.DUMMY,
                    Component.literal("Bots").withStyle(ChatFormatting.AQUA),
                    ObjectiveCriteria.RenderType.INTEGER, false, BlankFormat.INSTANCE);
        }
        sb.setDisplayObjective(DisplaySlot.SIDEBAR, obj);

        Set<String> alive = new HashSet<>();
        int points = bots.size();
        for (String bot : bots) {
            String headline = "masurium_" + bot;
            alive.add(headline);
            String state = states.get(bot);
            var access = sb.getOrCreatePlayerScore(ScoreHolder.forNameOnly(headline), obj);
            access.set(points--);
            access.display(line(bot, state, true));
            access.numberFormatOverride(BlankFormat.INSTANCE);
        }
        for (String old : new HashSet<>(headlines)) {
            if (!alive.contains(old)) {
                sb.resetSinglePlayerScore(ScoreHolder.forNameOnly(old), obj);
            }
        }
        headlines.clear();
        headlines.addAll(alive);
    }

    /** "⇄ Alice  internal", with the icon and color of the TAB; disconnected in gray. */
    static Component line(String bot, String state, boolean inside) {
        if (!inside) {
            return Component.literal("○ " + bot + "  offline").withStyle(ChatFormatting.DARK_GRAY);
        }
        Tab.Icon i = state == null ? null : Tab.ICONS.get(state);
        MutableComponent m = Component.literal("");
        if (i != null) m.append(Component.literal(i.symbol() + " ").withStyle(i.color()));
        m.append(Component.literal(bot).withStyle(ChatFormatting.WHITE));
        m.append(Component.literal("  " + (state == null ? "no state" : state))
                .withStyle(i == null ? ChatFormatting.GRAY : i.color()));
        return m;
    }

    private void remove(MinecraftServer server) {
        ServerScoreboard sb = server.getScoreboard();
        Objective obj = sb.getObjective(TARGET);
        if (obj != null) {
            sb.setDisplayObjective(DisplaySlot.SIDEBAR, null);
            sb.removeObjective(obj);
        }
        headlines.clear();
        lastOne = "";
    }
}
