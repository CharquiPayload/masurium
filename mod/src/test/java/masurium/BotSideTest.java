package masurium;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The one rule that keeps a single jar safe on a dedicated server.
 *
 * <p>Server and bot used to be two projects that could not see each other, so the
 * separation held for free. One jar removes that: now nothing stops a distracted
 * {@code import} in {@code masurium.server} from reaching into the bot's half, and a
 * client class touched on a dedicated server does not fail politely — it kills the
 * startup. Not on this machine, either: on the server of whoever downloaded the mod.
 *
 * <p>So the build checks it. On a dedicated server only {@code masurium.server} and
 * {@code masurium.common} are ever loaded ({@code masurium.bot} is
 * {@code @Mod(dist = Dist.CLIENT)} and never constructed), and neither of those two may
 * name a client class or a bot class.
 *
 * <p>It reads the COMPILED classes, not the source. A class file lists every type it
 * touches in its constant pool, so this catches what a grep over imports would miss: a
 * fully qualified name written inline, a lambda, a nested class, a return type never
 * imported.
 */
class BotSideTest {

    /** Where Gradle leaves the compiled classes of the main source set. */
    private static final Path CLASSES = Path.of("build", "classes", "java", "main");

    /** What a class loaded on a dedicated server must never name. */
    private static final List<String> FORBIDDEN = List.of(
            "net/minecraft/client/",
            "com/mojang/blaze3d/",
            "masurium/bot/");

    @Test
    @DisplayName("nothing the server loads names a client class or the bot's half")
    void theServerSideNamesNoClientClass() throws IOException {
        // A guard that quietly passes when it cannot find anything to check is worse
        // than no guard: it reads as green forever.
        assertTrue(Files.isDirectory(CLASSES),
                "there are no compiled classes at " + CLASSES.toAbsolutePath()
                + "; this test has to run after compileJava or it guards nothing");

        List<String> sins = new ArrayList<>();
        for (String half : List.of("server", "common")) {
            Path dir = CLASSES.resolve("masurium").resolve(half);
            assertTrue(Files.isDirectory(dir), "missing " + dir);
            try (Stream<Path> files = Files.walk(dir)) {
                for (Path f : files.filter(p -> p.toString().endsWith(".class")).toList()) {
                    String pool = new String(Files.readAllBytes(f),
                            java.nio.charset.StandardCharsets.ISO_8859_1);
                    for (String forbidden : FORBIDDEN) {
                        if (pool.contains(forbidden)) {
                            sins.add(CLASSES.relativize(f) + " names " + forbidden);
                        }
                    }
                }
            }
        }
        if (!sins.isEmpty()) {
            fail("On a dedicated server only masurium.server and masurium.common "
                 + "are loaded, and one of them reaches somewhere it must not. That is "
                 + "a crash on someone else's server, at startup or the first time the "
                 + "code path runs:\n  " + String.join("\n  ", sins));
        }
    }

    @Test
    @DisplayName("the bot's half is there, so the check is not passing over an empty jar")
    void theBotHalfIsActuallyInTheJar() throws IOException {
        Path bot = CLASSES.resolve("masurium").resolve("bot");
        assertTrue(Files.isDirectory(bot), "missing " + bot);
        try (Stream<Path> files = Files.walk(bot)) {
            long n = files.filter(p -> p.toString().endsWith(".class")).count();
            // If the bot ever stops being compiled into the same jar, the test above
            // becomes trivially true and stops meaning anything.
            assertTrue(n > 20, "only " + n + " compiled bot classes: is the bot still "
                    + "part of this jar?");
        }
    }
}
