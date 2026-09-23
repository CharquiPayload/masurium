package masurium.watut.mixin;

import com.corosus.watut.PlayerStatus;
import masurium.bot.Bot;
import masurium.bot.Thinking;
import masurium.watut.Seen;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While its brain thinks an answer, a bot is shown as a player typing one. WATUT draws
 * the typing bubble over a player whose chat is OPEN and who has text in it, and a bot
 * has neither: no screen, no chat box. So, while the bridge says a turn is on, its chat
 * counts as open and as typed in. Afterwards WATUT's own answers come back, from no
 * screen and an empty box, and the bubble goes as it came.
 *
 * <p>Saying only "typing" was not enough: WATUT kept the chat closed, and a closed chat
 * shows no bubble at all.
 */
@Mixin(targets = "com.corosus.watut.PlayerStatusManagerClient", remap = false)
public abstract class TypingWhileThinkingMixin {

    @Inject(method = "checkIfTyping", at = @At("HEAD"), cancellable = true)
    private void masurium$typingWhileThinking(String input, Player player, CallbackInfoReturnable<Boolean> cir) {
        if (Bot.isBot() && Thinking.now()) {
            Seen.once("thinking: WATUT asked whether it is typing, and was told yes");
            cir.setReturnValue(true);
        }
    }

    @ModifyVariable(method = "sendGuiStatus(Lcom/corosus/watut/PlayerStatus$PlayerGuiState;Z)V",
            at = @At("HEAD"), argsOnly = true)
    private PlayerStatus.PlayerGuiState masurium$chatOpenWhileThinking(PlayerStatus.PlayerGuiState state) {
        if (Bot.isBot() && Thinking.now() && state == PlayerStatus.PlayerGuiState.NONE) {
            Seen.once("thinking: its chat counts as open");
            return PlayerStatus.PlayerGuiState.CHAT_SCREEN;
        }
        return state;
    }
}
