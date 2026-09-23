package masurium.watut.mixin;

import masurium.bot.Bot;
import masurium.bot.Thinking;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While its brain thinks an answer, a bot is shown as a player typing one: WATUT asks
 * every tick whether the local player is typing, from what is in their chat box, and a
 * bot has no chat box. The answer is "yes" while the bridge says a turn is on, and
 * WATUT's own, from the empty box, afterwards: the bubble goes as it came.
 */
@Mixin(targets = "com.corosus.watut.PlayerStatusManagerClient", remap = false)
public abstract class TypingWhileThinkingMixin {

    @Inject(method = "checkIfTyping", at = @At("HEAD"), cancellable = true)
    private void masurium$typingWhileThinking(String input, Player player, CallbackInfoReturnable<Boolean> cir) {
        if (Bot.isBot() && Thinking.now()) {
            cir.setReturnValue(true);
        }
    }
}
