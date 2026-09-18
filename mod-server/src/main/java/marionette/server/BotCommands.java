package marionette.server;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.rcon.RconConsoleSource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;
import org.slf4j.Logger;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import marionette.server.BotAccess.Action;

/**
 * {@code /marionette bot <bot> ...}: taking a bot out of the game and choosing who it
 * hears, decided by the server, which knows for sure who runs a command. The rules live
 * in {@link BotAccess}; this is the command, the permission nodes and the messages.
 *
 * <ul>
 *   <li>{@code info} (or nothing): owner, admins, who it hears, whether its bridge
 *       answers. Anyone may look.
 *   <li>{@code shutdown | restart | logoff}: the owner or an admin.
 *   <li>{@code hear on | off | add <player> | remove <player>}: the owner or an admin.
 *       Shaped like vanilla {@code /whitelist}, which every server admin already knows:
 *       {@code on} = only its list, {@code off} = everyone.
 *   <li>{@code hear list}: the list and whether it is on. Anyone may look.
 *   <li>{@code admins add <player> | remove <player>}: the owner only.
 *   <li>{@code pref}: every behaviour toggle with its value. Anyone may look.
 *       {@code pref <key>} explains one; {@code pref <key> on|off} switches it, owner or
 *       admin. The keys are offered by tab completion and come from {@link
 *       marionette.common.Settings}, the same table the body reads.
 *   <li>{@code food}: what it will not eat on its own. {@code food ban <item>} and
 *       {@code food allow <item>}, owner or admin.
 *   <li>{@code break}: what it may break by itself to make its way. {@code break allow
 *       <block>} and {@code break forbid <block>}, owner or admin. What it is TOLD to dig
 *       never needed permission.
 * </ul>
 *
 * <p>The three settings families used to be changed by asking the bot in the chat. They
 * moved here for the same reason shutdown did: the brain's tools said "only on the owner's
 * order", and that was a sentence in a prompt with nothing enforcing it. What the bot
 * writes about the world it discovers — places, chests, its diary, its trash list — is
 * still its own.
 *
 * <p>Each action also has a permission node: {@code marionette.bot.shutdown},
 * {@code .restart}, {@code .logoff}, {@code .hear}, {@code .admins}, {@code .pref},
 * {@code .food} and {@code .break}. Nobody has them by
 * default, not even operators, because a bot belongs to its owner and not to the server. A
 * permissions mod such as LuckPerms can grant them, and then they work on every bot. The
 * server console (and RCON) always may: whoever holds it controls everything already.
 * Command blocks and datapack functions may not.
 */
final class BotCommands {

    private static final Logger LOG = LogUtils.getLogger();

    private static final Map<Action, PermissionNode<Boolean>> NODES = new EnumMap<>(Action.class);

    static {
        for (Action a : Action.values()) {
            NODES.put(a, new PermissionNode<>("marionette", "bot." + a.id(),
                    PermissionTypes.BOOLEAN, (player, uuid, context) -> false));
        }
    }

    private final BotAccess access;

    BotCommands(BotAccess access) {
        this.access = access;
    }

    @SubscribeEvent
    public void onPermissionNodes(PermissionGatherEvent.Nodes event) {
        event.addNodes(NODES.values().toArray(new PermissionNode<?>[0]));
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        SuggestionProvider<CommandSourceStack> bots =
                (c, b) -> SharedSuggestionProvider.suggest(access.names(), b);
        SuggestionProvider<CommandSourceStack> online =
                (c, b) -> SharedSuggestionProvider.suggest(c.getSource().getOnlinePlayerNames(), b);
        SuggestionProvider<CommandSourceStack> admins =
                (c, b) -> SharedSuggestionProvider.suggest(access.admins(bot(c)), b);
        SuggestionProvider<CommandSourceStack> heard =
                (c, b) -> SharedSuggestionProvider.suggest(access.hearList(bot(c)), b);
        // The settings come from common/, shared with the body: the list offered here is
        // the same one the bot knows, so it cannot suggest a key that does nothing.
        SuggestionProvider<CommandSourceStack> keys =
                (c, b) -> SharedSuggestionProvider.suggest(marionette.common.Settings.keys(), b);
        // Undoing suggests what there is to undo. Banning suggests nothing: any item id
        // is fair game and the body is the one that knows them all.
        SuggestionProvider<CommandSourceStack> banned =
                (c, b) -> SharedSuggestionProvider.suggest(access.foodList(bot(c), true), b);
        SuggestionProvider<CommandSourceStack> allowed =
                (c, b) -> SharedSuggestionProvider.suggest(access.breakList(bot(c), true), b);
        SuggestionProvider<CommandSourceStack> none = (c, b) -> b.buildFuture();

        event.getDispatcher().register(Commands.literal("marionette")
                .then(Commands.literal("bot")
                        .then(Commands.argument("bot", StringArgumentType.word())
                                .suggests(bots)
                                .executes(this::info)
                                .then(Commands.literal("info").executes(this::info))
                                .then(Commands.literal("shutdown")
                                        .executes(c -> order(c, Action.SHUTDOWN)))
                                .then(Commands.literal("restart")
                                        .executes(c -> order(c, Action.RESTART)))
                                .then(Commands.literal("logoff")
                                        .executes(c -> order(c, Action.LOGOFF)))
                                .then(Commands.literal("hear")
                                        .then(Commands.literal("on")
                                                .executes(c -> hearMode(c, true)))
                                        .then(Commands.literal("off")
                                                .executes(c -> hearMode(c, false)))
                                        .then(Commands.literal("list")
                                                .executes(this::hearList))
                                        .then(Commands.literal("add").then(player(online)
                                                .executes(c -> hear(c, true))))
                                        .then(Commands.literal("remove").then(player(heard)
                                                .executes(c -> hear(c, false)))))
                                .then(Commands.literal("admins")
                                        .then(Commands.literal("add").then(player(online)
                                                .executes(c -> admin(c, true))))
                                        .then(Commands.literal("remove").then(player(admins)
                                                .executes(c -> admin(c, false)))))
                                .then(Commands.literal("pref")
                                        .executes(this::prefList)
                                        .then(Commands.argument("key", StringArgumentType.word())
                                                .suggests(keys)
                                                .executes(this::prefOne)
                                                .then(Commands.literal("on")
                                                        .executes(c -> pref(c, true)))
                                                .then(Commands.literal("off")
                                                        .executes(c -> pref(c, false)))))
                                .then(Commands.literal("food")
                                        .executes(this::foodList)
                                        .then(Commands.literal("ban").then(id(none)
                                                .executes(c -> food(c, true))))
                                        .then(Commands.literal("allow").then(id(banned)
                                                .executes(c -> food(c, false)))))
                                .then(Commands.literal("break")
                                        .executes(this::breakList)
                                        .then(Commands.literal("allow").then(id(none)
                                                .executes(c -> breaking(c, true))))
                                        .then(Commands.literal("forbid").then(id(allowed)
                                                .executes(c -> breaking(c, false))))))));
        LOG.info("[marionette] /marionette bot command registered");
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> player(
            SuggestionProvider<CommandSourceStack> suggestions) {
        return Commands.argument("player", StringArgumentType.word()).suggests(suggestions);
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> id(
            SuggestionProvider<CommandSourceStack> suggestions) {
        return Commands.argument("id", StringArgumentType.word()).suggests(suggestions);
    }

    private static String bot(CommandContext<CommandSourceStack> c) {
        return StringArgumentType.getString(c, "bot");
    }

    // ------------------------------------------------------------ permission

    /**
     * Whether whoever runs the command may do that to that bot. If not, it says why.
     *
     * <p>Who runs it is {@code source.source}: the one who typed. Not the entity, because
     * {@code /execute as <owner> run ...} changes the entity and would let any operator
     * pass for the owner.
     */
    private boolean allowed(CommandContext<CommandSourceStack> c, String bot, Action action) {
        CommandSourceStack s = c.getSource();
        if (!access.knows(bot)) {
            fail(s, "There is no bot called " + bot + " on this server. Known: "
                    + (access.names().isEmpty() ? "none" : String.join(", ", access.names())));
            return false;
        }
        // The console and RCON run at level 4. Datapack functions also come from the
        // server itself, but at level 2, so they do not pass.
        if ((s.source instanceof MinecraftServer || s.source instanceof RconConsoleSource)
                && s.hasPermission(4)) {
            return true;
        }
        if (!(s.source instanceof ServerPlayer p)) {
            fail(s, "Only a player or the server console can do that");
            return false;
        }
        String name = p.getGameProfile().getName();
        if (access.may(bot, name, action)) return true;
        try {
            if (PermissionAPI.getPermission(p, NODES.get(action))) return true;
        } catch (RuntimeException e) {
            LOG.error("[marionette] could not check node {}", NODES.get(action).getNodeName(), e);
        }
        String what = switch (action) {
            case ADMINS -> "manage the admins of " + access.display(bot)
                    + " (only its owner can)";
            case HEAR -> "change who " + access.display(bot) + " hears";
            case PREF -> "change the settings of " + access.display(bot);
            case FOOD -> "change what " + access.display(bot) + " eats";
            case BREAK -> "change what " + access.display(bot) + " may break";
            default -> action.id() + " " + access.display(bot);
        };
        fail(s, "You do not have permission to " + what);
        return false;
    }

    private static String runner(CommandSourceStack s) {
        return s.source instanceof ServerPlayer p ? p.getGameProfile().getName() : "the console";
    }

    private static void fail(CommandSourceStack s, String text) {
        s.sendFailure(Component.literal(text));
    }

    private static void done(CommandSourceStack s, String text) {
        // true: operators and the server log see it too, so there is a trace of who did it.
        s.sendSuccess(() -> Component.literal(text).withStyle(ChatFormatting.GREEN), true);
    }

    // --------------------------------------------------------------- actions

    private int info(CommandContext<CommandSourceStack> c) {
        String bot = bot(c);
        CommandSourceStack s = c.getSource();
        if (!access.knows(bot)) {
            fail(s, "There is no bot called " + bot + " on this server. Known: "
                    + (access.names().isEmpty() ? "none" : String.join(", ", access.names())));
            return 0;
        }
        String name = access.display(bot);
        boolean inGame = s.getServer().getPlayerList().getPlayerByName(name) != null;
        boolean bridge = access.alive(bot, System.currentTimeMillis());
        List<String> admins = access.admins(bot);
        String owner = access.owner(bot);
        MutableComponent m = Component.literal(name).withStyle(ChatFormatting.AQUA)
                .append(Component.literal((inGame ? "  in the game" : "  not in the game")
                        + (bridge ? ", bridge answering" : ", bridge not answering"))
                        .withStyle(inGame && bridge ? ChatFormatting.GREEN : ChatFormatting.GRAY))
                .append(Component.literal("\n  owner: " + (owner.isEmpty() ? "none" : owner))
                        .withStyle(ChatFormatting.WHITE))
                .append(Component.literal("\n  admins: "
                        + (admins.isEmpty() ? "none" : String.join(", ", admins)))
                        .withStyle(ChatFormatting.WHITE))
                .append(Component.literal("\n  hears: " + hearing(bot))
                        .withStyle(ChatFormatting.WHITE));
        s.sendSuccess(() -> m, false);
        return 1;
    }

    /** "everyone" or "only its list", with the names. */
    private String hearing(String bot) {
        List<String> heard = access.hearList(bot);
        String names = heard.isEmpty() ? "nobody" : String.join(", ", heard);
        return access.onlyList(bot)
                ? "only its list (list on): " + names
                  + ", plus its owner, its admins and other bots"
                : "everyone (list off)"
                  + (heard.isEmpty() ? "" : "; list kept for later: " + names);
    }

    private int hearList(CommandContext<CommandSourceStack> c) {
        String bot = bot(c);
        CommandSourceStack s = c.getSource();
        if (!access.knows(bot)) {
            fail(s, "There is no bot called " + bot + " on this server. Known: "
                    + (access.names().isEmpty() ? "none" : String.join(", ", access.names())));
            return 0;
        }
        String line = access.display(bot) + " hears " + hearing(bot);
        s.sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.AQUA), false);
        return access.hearList(bot).size();
    }

    private int order(CommandContext<CommandSourceStack> c, Action action) {
        String bot = bot(c);
        CommandSourceStack s = c.getSource();
        if (!allowed(c, bot, action)) return 0;
        String name = access.display(bot);
        if (!access.alive(bot, System.currentTimeMillis())) {
            fail(s, "The bridge of " + name + " is not answering, so nothing was sent. "
                    + "If the bot is stuck, it has to be stopped from the machine it runs on.");
            return 0;
        }
        if (action == Action.LOGOFF
                && s.getServer().getPlayerList().getPlayerByName(name) == null) {
            fail(s, name + " is not in the game");
            return 0;
        }
        access.order(bot, action, runner(s), System.currentTimeMillis());
        LOG.info("[marionette] {} for {} ordered by {}", action.id(), name, runner(s));
        done(s, switch (action) {
            case SHUTDOWN -> name + " will shut down";
            case RESTART -> name + " will restart; back in about a minute";
            case LOGOFF -> name + " will log off; its client stays running";
            default -> "sent";
        });
        return 1;
    }

    private int hearMode(CommandContext<CommandSourceStack> c, boolean onlyList) {
        String bot = bot(c);
        if (!allowed(c, bot, Action.HEAR)) return 0;
        String bad = access.hearOnlyList(bot, onlyList);
        if (bad != null) {
            fail(c.getSource(), bad);
            return 0;
        }
        done(c.getSource(), onlyList
                ? access.display(bot) + " now hears only its owner, its admins, other bots "
                  + "and its list (" + String.join(", ", access.hearList(bot)) + ")"
                : access.display(bot) + " now hears everyone");
        return 1;
    }

    private int hear(CommandContext<CommandSourceStack> c, boolean add) {
        String bot = bot(c);
        String player = StringArgumentType.getString(c, "player");
        if (!allowed(c, bot, Action.HEAR)) return 0;
        String bad = add ? access.addHear(bot, player) : access.removeHear(bot, player);
        if (bad != null) {
            fail(c.getSource(), bad);
            return 0;
        }
        String name = access.display(bot);
        done(c.getSource(), (add ? player + " added to the list of " + name
                                 : player + " removed from the list of " + name)
                + (access.onlyList(bot) ? ""
                   : ". The list applies once you run /marionette bot " + name + " hear on"));
        return 1;
    }

    // -------------------------------------------------------------- settings

    /**
     * Whether the bot is there to take the change now. Unlike shutdown, a setting is NOT
     * refused when the bridge is quiet: it is written down here and sent again the next
     * time a bridge reports, so what was decided is not lost because the bot happened to
     * be off. The message says which of the two happened.
     */
    private String whenItApplies(String bot) {
        return access.alive(bot, System.currentTimeMillis()) ? ""
                : " (its bridge is not answering; it will be applied when it comes back)";
    }

    private int prefList(CommandContext<CommandSourceStack> c) {
        String bot = bot(c);
        CommandSourceStack s = c.getSource();
        if (!known(s, bot)) return 0;
        Map<String, Boolean> set = access.prefs(bot);
        MutableComponent m = Component.literal("settings of " + access.display(bot))
                .withStyle(ChatFormatting.AQUA);
        for (String key : marionette.common.Settings.keys()) {
            boolean value = set.containsKey(key)
                    ? set.get(key) : marionette.common.Settings.byDefault(key);
            boolean changed = set.containsKey(key);
            m.append(Component.literal("\n  " + key + ": " + (value ? "on" : "off")
                    + (changed ? " (set here)" : ""))
                    .withStyle(changed ? ChatFormatting.WHITE : ChatFormatting.GRAY));
        }
        s.sendSuccess(() -> m, false);
        return set.size();
    }

    private int prefOne(CommandContext<CommandSourceStack> c) {
        String bot = bot(c);
        CommandSourceStack s = c.getSource();
        if (!known(s, bot)) return 0;
        String key = StringArgumentType.getString(c, "key").toLowerCase();
        String does = marionette.common.Settings.describe(key);
        if (does == null) {
            fail(s, "There is no setting called " + key + ". There are: "
                    + String.join(", ", marionette.common.Settings.keys()));
            return 0;
        }
        Map<String, Boolean> set = access.prefs(bot);
        boolean value = set.containsKey(key)
                ? set.get(key) : marionette.common.Settings.byDefault(key);
        String line = key + ": " + (value ? "on" : "off")
                + (set.containsKey(key) ? " (set here)" : " (its default)")
                + "\n  " + does;
        s.sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.AQUA), false);
        return 1;
    }

    private int pref(CommandContext<CommandSourceStack> c, boolean value) {
        String bot = bot(c);
        if (!allowed(c, bot, Action.PREF)) return 0;
        String key = StringArgumentType.getString(c, "key").toLowerCase();
        CommandSourceStack s = c.getSource();
        String bad = access.pref(bot, key, value, runner(s), System.currentTimeMillis());
        if (bad != null) {
            fail(s, bad + ". There are: "
                    + String.join(", ", marionette.common.Settings.keys()));
            return 0;
        }
        LOG.info("[marionette] {} of {} set to {} by {}", key, access.display(bot),
                value, runner(s));
        done(s, access.display(bot) + ": " + key + " is now " + (value ? "on" : "off")
                + whenItApplies(bot));
        return 1;
    }

    private int foodList(CommandContext<CommandSourceStack> c) {
        String bot = bot(c);
        CommandSourceStack s = c.getSource();
        if (!known(s, bot)) return 0;
        List<String> ban = access.foodList(bot, true);
        List<String> back = access.foodList(bot, false);
        String line = access.display(bot) + " does not eat on its own: "
                + (ban.isEmpty() ? "nothing banned from here" : String.join(", ", ban))
                + (back.isEmpty() ? "" : "\n  allowed again from here: "
                   + String.join(", ", back))
                + "\n  it also has its own factory list (the golden apples); ask the bot "
                + "for the whole one";
        s.sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.AQUA), false);
        return ban.size();
    }

    private int food(CommandContext<CommandSourceStack> c, boolean ban) {
        String bot = bot(c);
        if (!allowed(c, bot, Action.FOOD)) return 0;
        String what = StringArgumentType.getString(c, "id").toLowerCase();
        CommandSourceStack s = c.getSource();
        String bad = access.food(bot, what, ban, runner(s), System.currentTimeMillis());
        if (bad != null) {
            fail(s, bad);
            return 0;
        }
        LOG.info("[marionette] food {} {} for {} by {}", ban ? "ban" : "allow", what,
                access.display(bot), runner(s));
        done(s, access.display(bot) + (ban
                ? " will not eat " + what + " on its own any more (handed to it by name, "
                  + "it still will)"
                : " may eat " + what + " on its own again") + whenItApplies(bot));
        return 1;
    }

    private int breakList(CommandContext<CommandSourceStack> c) {
        String bot = bot(c);
        CommandSourceStack s = c.getSource();
        if (!known(s, bot)) return 0;
        List<String> yes = access.breakList(bot, true);
        List<String> no = access.breakList(bot, false);
        String line = access.display(bot) + " may break on its own, to make its way: "
                + (yes.isEmpty() ? "nothing allowed from here" : String.join(", ", yes))
                + (no.isEmpty() ? "" : "\n  forbidden from here: " + String.join(", ", no))
                + "\n  it also has its own seed list (stone, cobblestone, grass_block, "
                + "dirt). What it is TOLD to dig never needed permission.";
        s.sendSuccess(() -> Component.literal(line).withStyle(ChatFormatting.AQUA), false);
        return yes.size();
    }

    private int breaking(CommandContext<CommandSourceStack> c, boolean allow) {
        String bot = bot(c);
        if (!allowed(c, bot, Action.BREAK)) return 0;
        String what = StringArgumentType.getString(c, "id").toLowerCase();
        CommandSourceStack s = c.getSource();
        String bad = access.breaking(bot, what, allow, runner(s), System.currentTimeMillis());
        if (bad != null) {
            fail(s, bad);
            return 0;
        }
        LOG.info("[marionette] break {} {} for {} by {}", allow ? "allow" : "forbid",
                what, access.display(bot), runner(s));
        done(s, access.display(bot) + (allow
                ? " may now break " + what + " on its own"
                : " may no longer break " + what + " on its own") + whenItApplies(bot));
        return 1;
    }

    /** The "there is no such bot" check the read-only commands share. */
    private boolean known(CommandSourceStack s, String bot) {
        if (access.knows(bot)) return true;
        fail(s, "There is no bot called " + bot + " on this server. Known: "
                + (access.names().isEmpty() ? "none" : String.join(", ", access.names())));
        return false;
    }

    private int admin(CommandContext<CommandSourceStack> c, boolean add) {
        String bot = bot(c);
        String player = StringArgumentType.getString(c, "player");
        if (!allowed(c, bot, Action.ADMINS)) return 0;
        String bad = add ? access.addAdmin(bot, player) : access.removeAdmin(bot, player);
        if (bad != null) {
            fail(c.getSource(), bad);
            return 0;
        }
        String name = access.display(bot);
        done(c.getSource(), add ? player + " is now an admin of " + name
                                : player + " is no longer an admin of " + name);
        return 1;
    }
}
