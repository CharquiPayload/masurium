package marionette.bot.mixin;

import marionette.bot.Bot;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A headless bot never draws the world: `renderLevel` is cut off entirely — for a bot,
 * and only for one.
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
        // Only for a bot. A mixin is applied when its class loads, long before anyone
        // asks what this client is for, so the question is asked here instead: without
        // the guard, anyone who installs the jar to play gets a black screen.
        if (Bot.isBot()) {
            ci.cancel();
        }
    }
}
