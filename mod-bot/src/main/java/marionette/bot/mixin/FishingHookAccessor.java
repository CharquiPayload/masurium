package marionette.bot.mixin;

import net.minecraft.world.entity.projectile.FishingHook;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The fish bite travels from the server to the client (DATA_BITING, a synced data field),
 * but the field it lands in is private and has no getter. This accessor opens it for
 * {@code Fisher}: read what the server already said instead of guessing it from the
 * bobber's speed.
 */
@Mixin(FishingHook.class)
public interface FishingHookAccessor {

    @Accessor("biting")
    boolean marionetteBiting();
}
