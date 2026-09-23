package masurium.veil;

import com.mojang.logging.LogUtils;
import masurium.bot.Bot;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

/**
 * The add-on's mod class. It does nothing but say, once, whether it is on duty: the
 * work is in the mixins, which apply when Veil's classes load, and only cut when the
 * client is a bot with no screen.
 */
@Mod(value = MasuriumVeil.ID, dist = Dist.CLIENT)
public class MasuriumVeil {

    public static final String ID = "masurium_veil";
    private static final Logger LOG = LogUtils.getLogger();

    public MasuriumVeil() {
        if (Bot.headless()) {
            LOG.info("[masurium-veil] headless bot: Veil keeps its hands off the GPU");
        } else {
            LOG.info("[masurium-veil] a screen is present: Veil runs as usual");
        }
    }
}
