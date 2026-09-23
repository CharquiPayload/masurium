package masurium.veil.mixin;

import masurium.bot.Bot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The first Veil wall, moved here from the core. Veil's {@code emptySamplers} is set in
 * its {@code init()}, which needs a GL context and never runs on a headless client, so
 * the first blit threw an NPE in {@code unbindSamplers}. Unbinding the samplers of a GPU
 * that does not exist means nothing, so the whole method is cancelled.
 *
 * <p>It used to cut for any bot; now only for one with no screen, which is the case it
 * describes: a bot with a window has a GPU, Veil initializes, and its samplers are its
 * own to unbind.
 */
@Mixin(targets = "foundry.veil.api.client.render.VeilRenderSystem", remap = false)
public abstract class VeilRenderSystemMixin {

    @Inject(method = "unbindSamplers", at = @At("HEAD"), cancellable = true)
    private static void masurium$noGpuNoSamplers(int first, int count, CallbackInfo ci) {
        if (Bot.headless()) {
            ci.cancel();
        }
    }
}
