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

    private static final String RAW = System.getProperty(NAME_PROPERTY);
    private static final String SERVER = nameFrom(System.getProperty(SERVER_PROPERTY));
    private static final String NAME = nameFrom(RAW);

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
}
