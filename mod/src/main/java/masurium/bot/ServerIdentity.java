package masurium.bot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Which server the bot is connected to, according to the launcher.
 *
 * <p>The mod cannot know it alone: several servers may share an IP and port (running one
 * at a time), so the launcher writes the identity into {@code
 * config/masurium-server.txt} at startup. EVERY per-world state file hangs from this
 * key (permissions, preferences, places and orders), so that each server keeps its own
 * settings.
 */
final class ServerIdentity {

    private ServerIdentity() {}

    private static String key;

    static synchronized String key() {
        if (key != null) return key;
        key = "unknown";
        try {
            Path mark = Path.of("config", "masurium-server.txt");
            if (Files.exists(mark)) {
                String s = Files.readString(mark).trim()
                        .replaceAll("[^A-Za-z0-9._-]", "_");
                if (!s.isEmpty()) key = s;
            }
        } catch (IOException ignored) { }
        return key;
    }

    /**
     * The per-server path of the file with a given name, migrating the old global file if
     * it exists: when permissions and preferences were a single file for every world,
     * what was written there belonged to THIS one (the only one in use), and losing it
     * when splitting per server would punish whoever had already configured it. The old
     * file is renamed to .migrated, not deleted.
     */
    static Path file(String name) {
        Path byServer = Path.of("config",
                "masurium-" + name + "-" + key() + ".txt");
        Path global = Path.of("config", "masurium-" + name + ".txt");
        try {
            if (!Files.exists(byServer) && Files.exists(global)) {
                Files.copy(global, byServer);
                Files.move(global, Path.of("config",
                        "masurium-" + name + ".txt.migrated"));
            }
        } catch (IOException ignored) { }
        return byServer;
    }
}
