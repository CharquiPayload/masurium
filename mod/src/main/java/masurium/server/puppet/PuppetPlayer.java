package masurium.server.puppet;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A player nobody plays, living on the server: EXPERIMENT (server-side bots).
 *
 * <p>A real player's body ticks twice over: the level ticks the player
 * ({@link ServerPlayer#tick}), and its connection ticks its body ({@code doTick}: the
 * physics, the food, the attacks) when its client's packets are handled. A puppet's
 * connection is never ticked, since nobody sends it anything, so the body is ticked
 * here, as Carpet's fake players do. Its physics are then a player's own, run by the
 * server: gravity, collisions, the step up, water, falls. What moves it is what moves a
 * player, keys: {@link #pilot} presses them (forward, jump, sprint) before each tick.
 */
final class PuppetPlayer extends ServerPlayer {

    /** Presses the keys for the tick about to run; null = none pressed. */
    Runnable pilot;

    PuppetPlayer(MinecraftServer server, ServerLevel level, GameProfile profile) {
        super(server, level, profile, ClientInformation.createDefault());
    }

    @Override
    public void tick() {
        long started = System.nanoTime();
        // Every half second, what a moving client would have made the server do: its
        // position taken as good, and the chunks around it loaded where it now is.
        if (getServer() != null && getServer().getTickCount() % 10 == 0) {
            connection.resetPosition();
            serverLevel().getChunkSource().move(this);
        }
        if (pilot != null) pilot.run();
        super.tick();
        doTick();
        Puppets.ticked(System.nanoTime() - started);
    }

    /**
     * A real player's falls are judged from what its client reports; a server-side
     * player's leave that empty, so without this it would never take fall damage.
     */
    @Override
    protected void checkFallDamage(double y, boolean onGround, BlockState state, BlockPos pos) {
        doCheckFallDamage(0.0, y, 0.0, onGround);
    }

    @Override
    public void die(DamageSource cause) {
        super.die(cause);
        Puppets.died(this);
    }
}
