package masurium.watut;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Says in the log, once, that a mixin ran. The mixins are optional on purpose (a WATUT
 * that changed must not stop the game), and Mixin skips an optional one without a word:
 * this line is how to know it took.
 */
public final class Seen {

    private static final Logger LOG = LogUtils.getLogger();
    private static final Set<String> SAID = ConcurrentHashMap.newKeySet();

    private Seen() {
    }

    public static void once(String what) {
        if (SAID.add(what)) LOG.info("[masurium-watut] {}", what);
    }
}
