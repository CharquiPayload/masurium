package marionette.bot.mixin;

import marionette.bot.Bot;
import marionette.bot.BotScreen;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
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
        if (!Bot.isBot()) {
            return;
        }
        // No graphics card at all: there is nothing to draw on and no one to draw for.
        // This goes FIRST and admits no exception. The connect screen is a screen, so
        // the rule below would let a headless client into the render path in the middle
        // of joining a server — where it dies inside a mod handshake, reporting a
        // version mismatch that is not what happened.
        if (Bot.headless()) {
            ci.cancel();
            return;
        }
        // Someone pressed R and is looking at the bot. Their keypress, their frames.
        if (BotScreen.drawing()) {
            return;
        }
        // A screen is open, so a person is looking at this window. Bots have windows
        // now, and a person will press E on one. The inventory draws the player model,
        // and the entity renderer reads a camera that ONLY renderLevel sets — cancelled
        // here, that camera is null and opening the inventory crashes the game with a
        // stack trace that names none of this. So while a screen is up, the world is
        // drawn. A working bot never opens one, and pays nothing.
        if (Minecraft.getInstance().screen != null) {
            return;
        }
        ci.cancel();
    }
}
