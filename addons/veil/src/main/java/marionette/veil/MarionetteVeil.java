package marionette.veil;

import com.mojang.logging.LogUtils;
import marionette.bot.Bot;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

/**
 * The add-on's mod class. It does nothing but say, once, whether it is on duty: the
 * work is in the mixins, which apply when Veil's classes load, and only cut when the
 * client is a bot with no screen.
 */
@Mod(value = MarionetteVeil.ID, dist = Dist.CLIENT)
public class MarionetteVeil {

    public static final String ID = "marionette_veil";
    private static final Logger LOG = LogUtils.getLogger();

    public MarionetteVeil() {
        if (Bot.headless()) {
            LOG.info("[marionette-veil] headless bot: Veil keeps its hands off the GPU");
        } else {
            LOG.info("[marionette-veil] a screen is present: Veil runs as usual");
        }
    }
}
