package marionette.bot.mixin;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A headless bot never draws the world: `renderLevel` is cut off entirely.
 *
 * <p>This is the cut that generalizes. Patching Veil's broken methods one at a time is a
 * bottomless pit (the second wall, `VeilFirstPersonRenderer.bind()`, only showed up on
 * joining a world). Cutting higher up kills the whole family of render crashes at once.
 * <b>If another render crash appears, the answer is to move the cut up, not to add
 * another one-off patch.</b>
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(method = "renderLevel", at = @At("HEAD"), cancellable = true)
    private void marionette$dontRenderWorld(DeltaTracker deltaTracker, CallbackInfo ci) {
        ci.cancel();
    }
}
