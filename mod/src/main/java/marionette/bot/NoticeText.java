package marionette.bot;

/**
 * What the title-screen notice says, and when — with no Minecraft in it.
 *
 * <p>Split from {@link TitleNotice} for a reason found the hard way: the moment that
 * class touched a screen type, loading it in a test dragged in the whole client and the
 * tests went red on a {@code NoClassDefFoundError}. The wording and the rules are a
 * decision and deserve tests; putting pixels on a screen is not and cannot have them.
 */
public final class NoticeText {

    static final String MISCONFIGURED =
            "Marionette: -Dmarionette.name is empty, so this instance is NOT a bot";
    static final String HINT_MISCONFIGURED =
            "Put a name in the instance's Java arguments, e.g. -Dmarionette.name=Alice";

    static final String NOT_A_BOT =
            "Marionette is installed but this instance is not a bot, so it does nothing";
    static final String HINT_NOT_A_BOT =
            "That is on purpose: play normally. To make a bot, read the manual";

    static final String MANUAL = "[ Open the manual ]";
    static final String HIDE = "[ Do not show again ]";

    static final String URL =
            "https://github.com/CharquiPayload/marionette/blob/main/docs/setup.md";

    private NoticeText() {
    }

    /** The two lines to show, or {@code null} to show nothing. */
    static String[] linesFor(boolean isBot, boolean misconfigured) {
        if (isBot) {
            return null;
        }
        return misconfigured
                ? new String[] {MISCONFIGURED, HINT_MISCONFIGURED}
                : new String[] {NOT_A_BOT, HINT_NOT_A_BOT};
    }

    /**
     * Whether this notice may ever be silenced.
     *
     * <p>Only the calm one. An error that can be hidden is an error that will be hidden,
     * and then someone is back to a bot that does nothing for no visible reason.
     */
    static boolean dismissible(boolean misconfigured) {
        return !misconfigured;
    }

    /** Whether a point is inside a row of text drawn at the given edges. */
    static boolean inside(double mx, double my, int left, int right, int top, int height) {
        return left >= 0 && mx >= left && mx <= right && my >= top && my <= top + height;
    }
}
