package masurium.bot;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.sounds.SoundSource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * A bot that was told where to go does not stop at the menu.
 *
 * <p>The main menu is furniture for a person: a panorama, music, buttons to click. A bot
 * has nobody to look at it and no hands to click it, so with
 * {@code -Dmasurium.server=host:port} it walks straight past — the title screen opens
 * and is immediately replaced by the connection.
 *
 * <p>The same moment is used to turn the client down to what a bot actually needs. It
 * draws nothing (see {@link TitleNotice} for who draws what) and hears nothing, but
 * without this it would still be mixing audio it cannot hear and rendering frames as
 * fast as the machine allows. On a machine running several bots that is real CPU spent
 * on nothing.
 *
 * <p>None of this touches a client that is not a bot: a person's volume and frame rate
 * are theirs.
 */
public final class AutoJoin {

    /** Once per game. The title screen comes back after a disconnect, and a bot that
     *  reconnected by itself in a loop would hammer a server that is down. */
    private static boolean joined;
    private static boolean quieted;

    private AutoJoin() {
    }

    @SubscribeEvent
    public static void onTitleScreen(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof TitleScreen screen) || !Bot.isBot()) {
            return;
        }
        quieten();

        String address = Bot.server();
        if (address == null || joined) {
            return;
        }
        joined = true;
        Minecraft mc = Minecraft.getInstance();
        ConnectScreen.startConnecting(screen, mc, ServerAddress.parseString(address),
                new ServerData("masurium", address, ServerData.Type.OTHER), false, null);
    }

    /** Silence and the smallest view a bot can work with, applied once. */
    private static void quieten() {
        if (quieted) {
            return;
        }
        quieted = true;
        Minecraft mc = Minecraft.getInstance();
        try {
            for (SoundSource source : SoundSource.values()) {
                mc.options.getSoundSourceOptionInstance(source).set(0.0);
            }
            // Not zero: the client still has to tick, and the tick loop is paced by
            // the frame loop. Low enough to stop burning a core on frames nobody sees.
            mc.options.framerateLimit().set(30);
            // A bot must not stop working because a window lost the mouse. This is a
            // person's setting and it is on by default; for a bot it is a bug waiting
            // for the first time anyone clicks somewhere else.
            mc.options.pauseOnLostFocus = false;
            // Render distance is deliberately NOT touched. It looks like the obvious
            // next saving, but it decides which chunks the client has at all, and a bot
            // acts on the world through this client: cutting it would quietly shorten
            // how far it can work, which is not a graphics setting any more.
            mc.options.save();
        } catch (RuntimeException e) {
            // Turning the volume down is not worth failing a launch over.
        }
    }
}
