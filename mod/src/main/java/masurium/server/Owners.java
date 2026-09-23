package masurium.server;

import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The owners of the bots as seen by players, and the state notice on the action bar.
 *
 * <p>Who owns a bot is not set here: it comes from the bot's own config, reported by its
 * bridge (see {@link BotAccess}). An operator used to assign owners by hand with a
 * command, and that list could say one thing while the bot believed another.
 *
 * <p>Everything hangs from a single root command, {@code /masurium}, named after the
 * project:
 * <ul>
 *   <li>{@code /masurium owners}: every bot with its owner;
 *   <li>{@code /masurium status}: what each bot is doing, with hp and position;
 *   <li>{@code /masurium hud on|off}: turn on or off, for oneself, the state icon of
 *       one's bots above the hotbar;
 *   <li>{@code /masurium bot <bot> ...}: see {@link BotCommands}.
 * </ul>
 *
 * <p>Who wants the hotbar notice is saved in {@code masurium_hud.properties}, next to
 * the mod config: it is a setting made once, not on every start.
 */
public final class Owners {

    private static final Logger LOG = LogUtils.getLogger();
    /**
     * Who wants the notice on the hotbar. Saved: it is a setting made once, not on every
     * start.
     */
    private static final Path HUD_FILE = Path.of("masurium_hud.properties");
    /**
     * How often, in ticks, the hotbar is refreshed. Every tick would mean sending twenty
     * packets per second to each owner to show the same thing.
     */
    private static final int EVERY = 20;

    /** Where the owners come from. */
    private final BotAccess access;
    /** Who wants the notice on the hotbar. Saved in {@link #HUD_FILE}. */
    private final Set<String> withNotice = ConcurrentHashMap.newKeySet();
    private final Tab tab;
    private int ticks;

    Owners(Tab tab, BotAccess access) {
        this.tab = tab;
        this.access = access;
        loadHud();
    }

    /** bot -> owner, sorted so the list always comes out the same. */
    private Map<String, String> owners() {
        return new TreeMap<>(access.owners());
    }

    // ------------------------------------------------------------ persistence

    private void loadHud() {
        if (!Files.exists(HUD_FILE)) return;
        Properties p = new Properties();
        try (var in = Files.newInputStream(HUD_FILE)) {
            p.load(in);
        } catch (IOException e) {
            LOG.error("[masurium] could not read {}", HUD_FILE, e);
            return;
        }
        for (String who : p.stringPropertyNames()) {
            if ("on".equalsIgnoreCase(p.getProperty(who, "").trim())) {
                withNotice.add(who);
            }
        }
        LOG.info("[masurium] {} player(s) with the action bar notice on",
                 withNotice.size());
    }

    private void saveHud() {
        Properties p = new Properties();
        withNotice.forEach(who -> p.setProperty(who, "on"));
        try (var out = Files.newOutputStream(HUD_FILE)) {
            p.store(out, "Who wants the icon of their bots above the action bar. "
                    + "Written by /masurium hud.");
        } catch (IOException e) {
            LOG.error("[masurium] could not write {}", HUD_FILE, e);
        }
    }

    /** The owner of a bot, or null. */
    public String of(String bot) {
        String who = access.owner(bot);
        return who.isEmpty() ? null : who;
    }

    /** A player's bots, in order. */
    private List<String> botsOf(String player) {
        List<String> ownedByMeList = new ArrayList<>();
        owners().forEach((bot, who) -> {
            if (who.equalsIgnoreCase(player)) ownedByMeList.add(bot);
        });
        return ownedByMeList;
    }

    /** The bots with an owner, sorted: the list the scoreboard shows. */
    Set<String> bots() {
        return new java.util.TreeSet<>(owners().keySet());
    }

    // ---------------------------------------------------------------- commands

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("masurium")
                .then(Commands.literal("owners")
                        .executes(this::seeAll))
                .then(Commands.literal("status")
                        .executes(this::state))
                .then(Commands.literal("hud")
                        .then(Commands.literal("on").executes(c -> notice(c, true)))
                        .then(Commands.literal("off").executes(c -> notice(c, false)))));
        LOG.info("[masurium] /masurium command registered");
    }

    /**
     * What each bot is doing, with how much hp and where. It comes from the server itself
     * (hp, position, world) and from the icon the bridge already sends to the TAB, so
     * there is no need to leave the game to know.
     */
    private int state(com.mojang.brigadier.context.CommandContext<CommandSourceStack> c) {
        var list = c.getSource().getServer().getPlayerList().getPlayers();
        MutableComponent m = Component.literal("Bots:").withStyle(ChatFormatting.AQUA);
        int quantity = 0;
        Map<String, String> owners = owners();
        for (String bot : owners.keySet()) {
            ServerPlayer p = list.stream()
                    .filter(j -> j.getGameProfile().getName().equalsIgnoreCase(bot))
                    .findFirst().orElse(null);
            quantity++;
            if (p == null) {
                m.append(Component.literal("\n  " + bot + " ").withStyle(ChatFormatting.WHITE))
                 .append(Component.literal("offline").withStyle(ChatFormatting.DARK_GRAY));
                continue;
            }
            String state = tab.of(bot);
            Tab.Icon i = state == null ? null : Tab.ICONS.get(state);
            m.append(Component.literal("\n  ").withStyle(ChatFormatting.WHITE));
            if (i != null) {
                m.append(Component.literal(i.symbol() + " ").withStyle(i.color()));
            }
            m.append(Component.literal(bot).withStyle(ChatFormatting.WHITE))
             .append(Component.literal("  " + (state == null ? "no state" : state))
                     .withStyle(i == null ? ChatFormatting.GRAY : i.color()))
             .append(Component.literal(String.format("  %.0f/%.0f hp",
                     p.getHealth(), p.getMaxHealth()))
                     .withStyle(p.getHealth() <= 6 ? ChatFormatting.RED
                                                   : ChatFormatting.GREEN))
             .append(Component.literal(String.format("  %d %d %d",
                     p.blockPosition().getX(), p.blockPosition().getY(),
                     p.blockPosition().getZ())).withStyle(ChatFormatting.GRAY));
            String owner = owners.get(bot);
            if (owner != null) {
                m.append(Component.literal("  (of " + owner + ")")
                        .withStyle(ChatFormatting.DARK_GRAY));
            }
        }
        if (quantity == 0) {
            c.getSource().sendSuccess(() -> Component.literal(
                    "No bot has reported an owner yet, so I do not know which ones "
                    + "to watch. The owner is set in each bot's config.")
                    .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        c.getSource().sendSuccess(() -> m, false);
        return quantity;
    }

    private int seeAll(com.mojang.brigadier.context.CommandContext<CommandSourceStack> c) {
        Map<String, String> owners = owners();
        if (owners.isEmpty()) {
            c.getSource().sendSuccess(() -> Component.literal(
                    "No bot has reported an owner yet. The owner is set in each bot's "
                    + "config and arrives when its bridge connects.")
                    .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        MutableComponent m = Component.literal("Owners:").withStyle(ChatFormatting.AQUA);
        owners.forEach((bot, who) -> m.append(Component.literal(
                "\n  " + bot + " -> " + who).withStyle(ChatFormatting.WHITE)));
        c.getSource().sendSuccess(() -> m, false);
        return owners.size();
    }

    private int notice(com.mojang.brigadier.context.CommandContext<CommandSourceStack> c,
                      boolean turnOn) {
        ServerPlayer p;
        try {
            p = c.getSource().getPlayerOrException();
        } catch (Exception e) {
            c.getSource().sendFailure(Component.literal(
                    "this only works when said by a player"));
            return 0;
        }
        String name = p.getGameProfile().getName();
        if (turnOn) {
            withNotice.add(name);
            saveHud();
            List<String> ownedByMeList = botsOf(name);
            c.getSource().sendSuccess(() -> Component.literal(ownedByMeList.isEmpty()
                    ? "Notice on, but you have no bot assigned"
                    : "Notice on for " + String.join(", ", ownedByMeList))
                    .withStyle(ChatFormatting.GREEN), false);
        } else {
            withNotice.remove(name);
            saveHud();
            p.displayClientMessage(Component.empty(), true);   // clear the hotbar
            c.getSource().sendSuccess(() -> Component.literal("Notice off")
                    .withStyle(ChatFormatting.GRAY), false);
        }
        return 1;
    }

    // ---------------------------------------------------- the hotbar notice

    @SubscribeEvent
    public void onTick(ServerTickEvent.Post event) {
        if (++ticks < EVERY) return;
        ticks = 0;
        if (withNotice.isEmpty() || access.owners().isEmpty()) return;
        for (ServerPlayer p : event.getServer().getPlayerList().getPlayers()) {
            String name = p.getGameProfile().getName();
            if (!withNotice.contains(name)) continue;
            Component line = hotbarOf(name);
            if (line != null) p.displayClientMessage(line, true);
        }
    }

    /**
     * The hotbar line: the icon of each of the player's bots with its name, or null if
     * they have no bots or none has a state.
     */
    private Component hotbarOf(String player) {
        MutableComponent line = null;
        for (String bot : botsOf(player)) {
            String state = tab.of(bot);
            Tab.Icon i = state == null ? null : Tab.ICONS.get(state);
            if (i == null) continue;
            if (line == null) {
                line = Component.empty();
            } else {
                line.append(Component.literal("  ").withStyle(ChatFormatting.DARK_GRAY));
            }
            line.append(Component.literal(i.symbol()).withStyle(i.color()))
                 .append(Component.literal(" " + bot).withStyle(ChatFormatting.WHITE));
        }
        return line;
    }
}
