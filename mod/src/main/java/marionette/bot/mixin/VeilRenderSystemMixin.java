package marionette.bot.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Veil (bundled inside some mods) assumes there is a GPU: `emptySamplers` is set in its
 * `init()`, which needs a GL context and never runs in a headless client, so the first
 * blit throws an NPE in `unbindSamplers`. Unbinding the samplers of a GPU that does not
 * exist means nothing, so the whole method is cancelled.
 *
 * <p>The target is a string and uses {@code remap = false} so Veil is not needed on the
 * compile classpath; with {@code defaultRequire: 0} in the json, in a pack without Veil
 * the mixin simply does not apply.
 */
@Mixin(targets = "foundry.veil.api.client.render.VeilRenderSystem", remap = false)
public abstract class VeilRenderSystemMixin {

    @Inject(method = "unbindSamplers", at = @At("HEAD"), cancellable = true)
    private static void marionette$noGpuNoSamplers(int first, int count, CallbackInfo ci) {
        ci.cancel();
    }
}
