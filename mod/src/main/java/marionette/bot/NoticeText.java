package marionette.bot;

import java.util.ArrayList;
import java.util.List;

/**
 * What the title screen has to say about how this instance was configured, and when —
 * with no Minecraft in it.
 *
 * <p>Split from {@link TitleNotice} for a reason found the hard way: the moment that
 * class touched a screen type, loading it in a test dragged in the whole client and the
 * tests went red on a {@code NoClassDefFoundError}. The wording and the rules are a
 * decision and deserve tests; putting pixels on a screen is not and cannot have them.
 *
 * <p>There is more than one thing that can be wrong at once — no server address and a
 * defaulted port, say — so this answers with a list, and each entry carries its own id
 * because each is silenced on its own.
 */
public final class NoticeText {

    /**
     * One thing worth telling whoever set this instance up.
     *
     * @param id         what "do not show again" remembers; never reuse or reword
     * @param text       what is wrong, or what is merely true
     * @param hint       what to do about it — a complaint without a fix is noise
     * @param severity   how it reads on screen
     * @param dismissible whether it may ever be silenced
     */
    record Notice(String id, String text, String hint, Severity severity,
                  boolean dismissible) {
    }

    enum Severity { ERROR, WARNING, INFO, SUCCESS }

    static final String URL =
            "https://github.com/CharquiPayload/marionette/blob/main/docs/setup.md";

    static final String MANUAL = "[ Manual ]";
    static final String HIDE = "[ Do not show again ]";

    private NoticeText() {
    }

    /**
     * Everything this instance should be told, topmost first.
     *
     * @param isBot      whether a usable bot name was given
     * @param blankName  the name flag is present and empty
     * @param hasServer  an address to join on start was given
     * @param name       the bot's name, for the line that confirms it
     * @param server     the address it will join, or {@code null}
     */
    static List<Notice> noticesFor(boolean isBot, boolean blankName,
                                   boolean hasServer, String name, String server) {
        List<Notice> out = new ArrayList<>();

        if (blankName) {
            // Fatal and not dismissible: someone meant to make a bot and this client
            // is not one. Silencing it is how you end up with the mute bot the whole
            // notice exists to prevent.
            out.add(new Notice("blank-name",
                    "-Dmarionette.name is empty, so this instance is NOT a bot",
                    "Give it a name: -Dmarionette.name=Alice",
                    Severity.ERROR, false));
            return out;
        }

        if (!isBot) {
            out.add(new Notice("not-a-bot",
                    "Marionette is installed but this instance is not a bot",
                    "That is on purpose: play normally. To make one, read the manual",
                    Severity.INFO, true));
            return out;
        }

        // From here on it IS a bot. The good news goes first and is not conditional:
        // someone who set this up wants to see that it worked, and "nothing appeared"
        // is indistinguishable from "the mod did not load".
        out.add(new Notice("ready",
                "Marionette: this instance is the bot " + name,
                hasServer ? "Joining " + server + " now"
                          : "Loaded and waiting — it will not join a server by itself",
                Severity.SUCCESS, true));

        if (!hasServer) {
            out.add(new Notice("no-server",
                    "-Dmarionette.server is missing, so this bot will not join on its own",
                    "Add -Dmarionette.server=192.168.1.10:25565, or pick a server below",
                    Severity.ERROR, true));
        }
        // The defaulted port is NOT mentioned here on purpose. With one bot the default
        // is correct, and warning someone about a correct setup teaches them to ignore
        // this box. It goes to the log, where the person with two bots will look.
        return out;
    }

    /** Whether a point is inside a row of text drawn at the given edges. */
    static boolean inside(double mx, double my, int left, int right, int top, int height) {
        return left >= 0 && mx >= left && mx <= right && my >= top && my <= top + height;
    }

    /** The ids still worth drawing, given what has already been silenced. */
    static List<Notice> notSilenced(List<Notice> notices, List<String> hidden) {
        List<Notice> out = new ArrayList<>();
        for (Notice n : notices) {
            if (!n.dismissible() || !hidden.contains(n.id())) {
                out.add(n);
            }
        }
        return out;
    }
}
