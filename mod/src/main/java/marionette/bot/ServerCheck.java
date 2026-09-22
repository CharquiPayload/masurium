package marionette.bot;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * A bot on a server without Marionette leaves it, and closes its game.
 *
 * <p>Everything a bot does past walking in needs the server's half of the mod: its
 * bridge reads the chat and the players through the server's API, and the commands that
 * shut it down or restart it are the server's. On a server without it the bot would
 * stand there, logged in, a body nobody can reach, until someone noticed. The launcher
 * asks the server's API before it starts a game; this is the same question asked from
 * inside, for a bot started some other way.
 *
 * <p>The sign is the {@code /marionette} command: the server half registers it for
 * everyone, and a server sends each client its command tree when it joins. No such
 * command a few seconds after joining means no Marionette there.
 */
public final class ServerCheck {

    /** What the server half registers, open to every player. */
    public static final String COMMAND = "marionette";
    /** Ticks in the world before deciding: the tree comes with the login, and a busy
     *  server may send it late. Ten seconds. */
    static final int PATIENCE = 200;

    enum Verdict { WAIT, FINE, LEAVE }

    /** The whole decision, apart from Minecraft so it can be tested. */
    static Verdict judge(boolean hasCommand, int ticksInWorld) {
        if (hasCommand) {
            return Verdict.FINE;
        }
        return ticksInWorld < PATIENCE ? Verdict.WAIT : Verdict.LEAVE;
    }

    /** The connection already judged, so each one is judged once. */
    private static ClientPacketListener judged;
    private static int ticks;

    private ServerCheck() {
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientPacketListener connection = mc.getConnection();
        if (connection == null || mc.player == null || mc.level == null) {
            ticks = 0;
            return;
        }
        if (connection == judged) {
            return;
        }
        ticks++;
        boolean has = connection.getCommands().getRoot().getChild(COMMAND) != null;
        switch (judge(has, ticks)) {
            case FINE -> {
                judged = connection;
                ticks = 0;
            }
            case WAIT -> {
            }
            case LEAVE -> {
                judged = connection;
                String where = mc.getCurrentServer() != null ? mc.getCurrentServer().ip : "this server";
                // The line the launcher quotes when a start does not join.
                // The logger is fetched here and not held in a static field: the
                // decision above is tested without Minecraft's classes around.
                LogUtils.getLogger().error("[marionette-bot] {} has no Marionette on its side (no /{} command): "
                        + "a bot cannot work there. Leaving, and closing the game.", where, COMMAND);
                mc.level.disconnect();
                mc.disconnect();
                mc.stop();
            }
        }
    }
}
