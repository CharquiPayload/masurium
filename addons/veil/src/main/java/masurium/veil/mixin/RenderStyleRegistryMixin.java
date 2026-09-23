package masurium.veil.mixin;

import masurium.bot.Bot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The wall in Veil 4.3.2. Once its renderer exists, Veil initializes the render styles
 * of its particle engine (Quasar): each one uploads a vertex array, and under a stubbed
 * graphics library the GL answers that it has -1 vertex attributes, so the first upload
 * throws before the mod handshake ("Invalid vertex attribute index. Must be between 0
 * and -1"). A bot with no screen never draws a particle, so the styles are never needed:
 * both their initialization and their release are cut.
 *
 * <p>Why here and not higher. The first attempt cut Veil's whole client initialization,
 * and the very next thing to die was a vanilla shader: Veil has some hundred mixins in
 * the game (shaders, render targets, textures) and every one of them assumes the
 * renderer that initialization creates. Veil's own {@code VeilRenderSystem.init()}
 * survives the stub; this step after it does not. So Veil is left whole and this one
 * subsystem sleeps. If another Veil wall appears, look for the next GPU-only subsystem
 * and cut it the same way — not Veil itself.
 *
 * <p>Only for a bot with no screen. A player, or a bot with a window, has a GPU and gets
 * Veil whole. The target is a string with {@code remap = false} so Veil is not needed on
 * the compile classpath; with {@code defaultRequire: 0} in the json, in a pack without
 * Veil the mixin simply does not apply.
 */
@Mixin(targets = "foundry.veil.api.quasar.registry.RenderStyleRegistry", remap = false)
public abstract class RenderStyleRegistryMixin {

    @Inject(method = "initRenderStyles", at = @At("HEAD"), cancellable = true)
    private static void masurium$noGpuNoStyles(CallbackInfo ci) {
        if (Bot.headless()) {
            ci.cancel();
        }
    }

    @Inject(method = "freeRenderStyles", at = @At("HEAD"), cancellable = true)
    private static void masurium$nothingToFree(CallbackInfo ci) {
        if (Bot.headless()) {
            ci.cancel();
        }
    }
}
