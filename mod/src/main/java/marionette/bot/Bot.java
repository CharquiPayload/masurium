package marionette.bot;

/**
 * Whether this client is a bot at all.
 *
 * <p>The mod used to assume the answer was always yes, because during development it
 * only ever ran inside a headless client that existed to be a bot. That assumption is
 * baked into things that are not harmless: the world is never drawn, food is eaten
 * automatically, death is followed by an automatic respawn, and an HTTP port is opened
 * to take orders. Put that jar in the client someone plays on and the mod plays for
 * them, in the dark.
 *
 * <p>So the jar has to be safe to install anywhere. A bot is a client that was
 * <b>told</b> to be one, through a JVM property its launcher sets:
 *
 * <pre>
 *   -Dmarionette.name=Alice
 * </pre>
 *
 * <p>Any ordinary launcher can set it — it is the "Java arguments" box of a per-instance
 * launcher such as Prism, and one instance is one bot. Without it this half of the mod
 * does not register a single listener, does not open a port, and does not draw the world
 * any differently: the jar is present and the mod is not there. A player can have it
 * installed and play on the very server its bots are on, and never notice.
 *
 * <p>It is read once. A JVM property cannot change while the game runs, and a value that
 * cannot change should not be read as if it could.
 */
public final class Bot {

    /** The property that decides it all. No name, no bot. */
    public static final String NAME_PROPERTY = "marionette.name";

    /** Where to go on start, so a bot never waits at the menu for a click. */
    public static final String SERVER_PROPERTY = "marionette.server";

    /** Which port this bot's hands listen on. Read by the mod itself, checked here. */
    public static final String PORT_PROPERTY = "marionette.bot.port";

    /**
     * No screen and no graphics card: the client is running under a launcher that
     * stubs out the graphics library, such as HeadlessMC.
     *
     * <p>It is told, not guessed. Sniffing for a launcher's own properties works until
     * the launcher renames one, and the failure it causes then is a connection that
     * dies in the middle of a mod handshake with a message about something else.
     */
    public static final String HEADLESS_PROPERTY = "marionette.headless";

    /**
     * What a bot may be called: Minecraft's own username rules.
     *
     * <p>Enforced here for a reason that has nothing to do with Minecraft. This name
     * becomes a directory, part of several file names, and the word the bridge looks
     * for in the chat. It is not just text.
     *
     * <p>{@code ../../etc} arrives from the command line intact — the JVM has no
     * opinion about it — and would be used as a path. {@code Bot Alice} arrives intact
     * too when the launcher quoted it, and would be a directory with a space in it and
     * a chat token matching half of what people say. Both are refused here, loudly,
     * rather than found later as a file somewhere it should not be.
     *
     * <p>Declared BEFORE the fields that use it: a static field initialised further
     * down the file would still be null while NAME was being worked out.
     */
    private static final java.util.regex.Pattern ALLOWED =
            java.util.regex.Pattern.compile("[A-Za-z0-9_]{1,16}");

    private static final String RAW = System.getProperty(NAME_PROPERTY);
    private static final String SERVER = nameFrom(System.getProperty(SERVER_PROPERTY));
    private static final String TRIMMED = nameFrom(RAW);
    private static final String NAME = usable(TRIMMED) ? TRIMMED : null;

    private Bot() {
    }

    /**
     * The whole decision, with no JVM in the middle so it can be tested.
     *
     * <p>Blank is not a name. A launcher that writes the flag and leaves the value empty
     * is a launcher that has not been configured yet, and reading that as "yes, and the
     * bot is called nothing" would be the worst of both answers.
     *
     * @return the bot's name, or {@code null} if this client is not a bot
     */
    static String nameFrom(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * The flag is there and says nothing.
     *
     * <p>This is not the same silence as a player's, and must not be answered the same
     * way. Someone wrote the property, so someone believes they are starting a bot; if
     * this were as quiet as an ordinary client, they would be left with a client that
     * joins, sits there and never obeys, with nothing anywhere saying why.
     */
    public static boolean misconfigured() {
        return RAW != null && NAME == null;
    }

    /**
     * Whether this is a name a bot can actually carry.
     *
     * <p>Visible for tests: everything dangerous about a name is decided here, and the
     * whole list of nasty ones has to be throwable at it without a JVM in the middle.
     */
    static boolean usable(String name) {
        return name != null && ALLOWED.matcher(name).matches();
    }

    /** The flag is present and empty, as opposed to present and wrong. */
    public static boolean blank() {
        return RAW != null && TRIMMED == null;
    }

    /** The flag is present and holds something that is not a usable name. */
    public static boolean badName() {
        return RAW != null && TRIMMED != null && NAME == null;
    }

    /** What was given and refused, so the message can quote it back. */
    public static String rejected() {
        return TRIMMED;
    }

    /** Whether this client has no graphics at all. See {@link #HEADLESS_PROPERTY}. */
    public static boolean headless() {
        return nameFrom(System.getProperty(HEADLESS_PROPERTY)) != null;
    }

    /** Whether this client was told to be a bot. */
    public static boolean isBot() {
        return NAME != null;
    }

    /** The name it was told to use, or {@code null} if it is not a bot. */
    public static String name() {
        return NAME;
    }

    /**
     * The server to join on start, or {@code null} to stop at the menu.
     *
     * <p>Blank counts as absent for the same reason a blank name does: a flag written
     * and left empty is a launcher someone has not finished configuring, and joining
     * "" would fail with a message about the address instead of about the flag.
     */
    public static String server() {
        return SERVER;
    }

    /**
     * Whether the bot port was chosen rather than defaulted.
     *
     * <p>Worth saying out loud, because the failure it leads to does not look like a
     * configuration problem: two bots on one machine both taking the default both try
     * to open 8478, and the second one comes up with no hands and no obvious reason.
     */
    public static boolean portSpecified() {
        return nameFrom(System.getProperty(PORT_PROPERTY)) != null;
    }
}
