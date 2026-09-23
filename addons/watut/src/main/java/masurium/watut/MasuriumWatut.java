package masurium.watut;

import com.mojang.logging.LogUtils;
import masurium.bot.Bot;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

import java.lang.reflect.Method;

/**
 * WATUT marks a player AFK after minutes without a key or a mouse button, and says so
 * above their head and in how their body stands. A bot has neither: it walks, digs and
 * fights through code, so WATUT had it "away" while it worked. Here, on a bot's client
 * and nowhere else, whatever the bot does counts as what a key press counts as for a
 * person: WATUT's own onAction(), the call its key and mouse hooks make. A bot that
 * stands still for long enough goes AFK like anyone. And while its brain thinks an
 * answer it is shown typing (see the mixin).
 *
 * <p>WATUT is reached by reflection, so the add-on needs no copy of it to be built. Its
 * mods.toml pins the WATUT it is for; without WATUT in the pack it does nothing.
 */
@Mod(value = MasuriumWatut.ID, dist = Dist.CLIENT)
public class MasuriumWatut {

    public static final String ID = "masurium_watut";
    private static final Logger LOG = LogUtils.getLogger();

    private final Nudge nudge = new Nudge();
    private Object manager;
    private Method onAction;
    private boolean broken;
    private double lastX, lastY, lastZ;

    public MasuriumWatut() {
        if (!Bot.isBot() || !ModList.get().isLoaded("watut")) {
            LOG.info("[masurium-watut] {}: nothing to do", Bot.isBot() ? "no WATUT in the pack" : "not a bot");
            return;
        }
        NeoForge.EVENT_BUS.addListener(this::tick);
        LOG.info("[masurium-watut] a bot that moves or acts is not away, and it types while it thinks");
    }

    private void tick(ClientTickEvent.Post event) {
        var player = Minecraft.getInstance().player;
        if (player == null || broken) return;
        double moved = player.distanceToSqr(lastX, lastY, lastZ);
        lastX = player.getX();
        lastY = player.getY();
        lastZ = player.getZ();
        if (nudge.due(moved, player.swinging || player.isUsingItem(), player.tickCount)) {
            act();
        }
    }

    /** WATUT's onAction(), found once; if it is not there, said once and left alone. */
    private void act() {
        try {
            if (onAction == null) {
                manager = Class.forName("com.corosus.watut.WatutMod")
                        .getMethod("getPlayerStatusManagerClient").invoke(null);
                onAction = manager.getClass().getMethod("onAction");
            }
            onAction.invoke(manager);
        } catch (ReflectiveOperationException | RuntimeException e) {
            broken = true;
            LOG.warn("[masurium-watut] WATUT is not the one this add-on knows ({}): it may show the bot "
                    + "away while it works", e.toString());
        }
    }
}
