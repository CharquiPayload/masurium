package masurium.server;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.scores.PlayerTeam;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An icon next to the bot's name in the TAB list, according to what it is doing.
 *
 * <p>A loading icon for thinking, a green one for idle and a red one for errors or
 * combat. Who KNOWS what the bot is doing is the bridge (whether the brain is in a turn,
 * whether it failed) and the body (whether it flees, works or died); the server only
 * draws what it is told through {@code /tab?player=Alice&state=thinking}.
 *
 * <p>ONLY the TAB list name is touched, through NeoForge's {@link
 * PlayerEvent.TabListNameFormat} event: the chat name and the one above the head stay
 * intact, and those are what the bots' bridges read (a team prefix would change all
 * three, which is why colored teams go without a prefix). The team color is kept: the
 * name is formatted as the game would and the icon goes in front.
 *
 * <p>The symbols are from Unicode plane 0, which the client draws with unifont; an emoji
 * would show up as a white square.
 */
public final class Tab {

    public record Icon(String symbol, ChatFormatting color) {}

    /** Sorted so the error message always lists the states the same way. */
    static final Map<String, Icon> ICONS = new TreeMap<>(Map.ofEntries(
            Map.entry("idle", new Icon("●", ChatFormatting.GREEN)),        // ●
            Map.entry("working", new Icon("▶", ChatFormatting.AQUA)),    // ▶ (a job without its own name)
            Map.entry("thinking", new Icon("◐", ChatFormatting.YELLOW)),    // ◐
            Map.entry("combat", new Icon("⚔", ChatFormatting.RED)),        // ⚔
            // Fleeing is not fighting: it is not standing its ground, it is putting
            // distance in between.
            Map.entry("fleeing", new Icon("»", ChatFormatting.GOLD)),        // »
            Map.entry("error", new Icon("✖", ChatFormatting.RED)),          // ✖
            // PHYSICALLY stuck (it wants to move and does not, or gave up with "I got
            // stuck"): the bridge detects it and shows it in red.
            Map.entry("stuck", new Icon("⚠", ChatFormatting.RED)),       // ⚠
            Map.entry("dead", new Icon("☠", ChatFormatting.DARK_RED)),    // ☠
            // Talking to ANOTHER AI through the internal channel (a bot with its guard).
            Map.entry("internal", new Icon("⇄", ChatFormatting.LIGHT_PURPLE)),  // ⇄
            // Jobs with their own name, for the scoreboard, which shows text. All in
            // aqua, like working.
            Map.entry("escorting", new Icon("◈", ChatFormatting.AQUA)),
            Map.entry("following", new Icon("◇", ChatFormatting.AQUA)),
            Map.entry("mining", new Icon("⛏", ChatFormatting.AQUA)),
            Map.entry("building", new Icon("▦", ChatFormatting.AQUA)),
            Map.entry("farming", new Icon("❀", ChatFormatting.GREEN)),
            Map.entry("chopping", new Icon("⚒", ChatFormatting.AQUA)),
            Map.entry("crafting", new Icon("⚙", ChatFormatting.AQUA)),
            Map.entry("cooking", new Icon("♨", ChatFormatting.DARK_AQUA)),
            // Gathering is by hand; the shears belong to shearing.
            Map.entry("gathering", new Icon("✋", ChatFormatting.AQUA)),
            Map.entry("shearing", new Icon("✂", ChatFormatting.AQUA)),
            Map.entry("traveling", new Icon("➜", ChatFormatting.AQUA)),
            Map.entry("hunting", new Icon("⚑", ChatFormatting.AQUA)),
            Map.entry("fishing", new Icon("⚓", ChatFormatting.AQUA)),
            Map.entry("exploring", new Icon("✧", ChatFormatting.AQUA)),
            Map.entry("taming", new Icon("♥", ChatFormatting.LIGHT_PURPLE)),
            Map.entry("smelting", new Icon("♨", ChatFormatting.AQUA)),
            Map.entry("sleeping", new Icon("☾", ChatFormatting.DARK_AQUA))));

    /** Player -> state. Written by the HTTP thread and read by the game one. */
    private final Map<String, String> states = new ConcurrentHashMap<>();

    /** Stores the state. Returns null if it was accepted, or the reason. */
    String place(String player, String state) {
        if (state.isEmpty() || state.equals("none")) {
            states.remove(player);
            return null;
        }
        if (!ICONS.containsKey(state)) {
            return "unknown state: " + state + " (valid: "
                    + String.join(", ", ICONS.keySet()) + " or none)";
        }
        states.put(player, state);
        return null;
    }

    String of(String player) {
        return states.get(player);
    }

    /** Every known state (a copy), for the sidebar. */
    Map<String, String> everyone() {
        return new TreeMap<>(states);
    }

    /**
     * What the TAB list shows for a player with a state; null = as usual. Kept apart from
     * the event so it can be reasoned about.
     */
    static Component nameWithIcon(String state, MutableComponent teamName) {
        Icon i = ICONS.get(state);
        if (i == null) return null;
        return Component.literal(i.symbol() + " ").withStyle(i.color())
                .append(teamName);
    }

    @SubscribeEvent
    public void onFormatTab(PlayerEvent.TabListNameFormat event) {
        var p = event.getEntity();
        String state = states.get(p.getGameProfile().getName());
        if (state == null) return;
        Component with = nameWithIcon(state,
                PlayerTeam.formatNameForTeam(p.getTeam(), p.getName()));
        if (with != null) event.setDisplayName(with);
    }
}
