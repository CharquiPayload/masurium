package marionette.bot;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import marionette.common.Logbook;

/**
 * My guards (the bots whose {@code escort} file names me) and how to ask them to MAKE WAY
 * when they are in the way, body to body and without the brain.
 *
 * <p>A guard standing on the cell where a block goes, or on the fishing line (the hook
 * kept catching on it), blocked its boss again and again. When a task detects that, the
 * request goes over HTTP to the guard's port (the same one its brain uses), to {@code
 * /step_aside}: a segment a-b (or a point, if a = b) and a radius; its {@link Follower}
 * steps aside and the escort goes on as if nothing happened.
 *
 * <p>Who my guards are is read from disk (sibling folders of mine, with {@code escort}
 * and {@code port}), like {@link BreakPermissions#ofTheBoss()} does the other way round;
 * it is reread every half minute.
 */
final class Guards {

    private static final long REREAD_NS = 30_000_000_000L;
    /** Between two requests to the same guard: its own stepping aside lasts 10 s. */
    private static final long BETWEEN_REQUESTS_NS = 2_000_000_000L;
    private static final long BETWEEN_NOTICES_NS = 10_000_000_000L;

    private static Map<String, Integer> ports = Map.of();
    private static long wasRead;
    private static final Map<String, Long> lastRequest = new HashMap<>();
    private static final Map<String, Long> lastNotice = new HashMap<>();
    private static final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(1)).build();

    private Guards() {}

    /** Name (lowercase) → port of each of my guards. */
    static synchronized Map<String, Integer> ownedByMeSet() {
        long now = System.nanoTime();
        if (wasRead != 0 && now - wasRead < REREAD_NS) return ports;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return ports;
        wasRead = now;
        String me = mc.player.getGameProfile().getName();
        Map<String, Integer> freshList = new HashMap<>();
        try (var dirs = Files.list(Path.of("..", ".."))) {
            for (Path d : (Iterable<Path>) dirs::iterator) {
                try {
                    Path escortFile = d.resolve("escort"), portFile = d.resolve("port");
                    if (!Files.isRegularFile(escortFile) || !Files.isRegularFile(portFile)) continue;
                    if (!Files.readString(escortFile).strip().equalsIgnoreCase(me)) continue;
                    freshList.put(d.getFileName().toString().toLowerCase(Locale.ROOT),
                            Integer.parseInt(Files.readString(portFile).strip()));
                } catch (Exception e) {
                    // That folder is not a well-formed bot: skipped.
                }
            }
        } catch (Exception e) {
            // No bots folder (another kind of deployment): no guards.
        }
        ports = freshList;
        return ports;
    }

    /** The port of another bot by its name (sibling folder), or null. */
    static Integer portOf(String name) {
        try {
            return Integer.parseInt(Files.readString(
                    Path.of("..", "..", name.toLowerCase(Locale.ROOT), "port")).strip());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Tells my guards (and my boss, if I am a guard) that I am going to sleep there, so
     * they sleep too: the night only passes if everyone sleeps. Body to body, through
     * /asleep.
     */
    static void warnSleep(BlockPos bed) {
        Map<String, Integer> a = new HashMap<>(ownedByMeSet());
        String boss = MarionetteBot.boss();
        if (boss != null) {
            Integer pj = portOf(boss);
            if (pj != null) a.put(boss.toLowerCase(Locale.ROOT), pj);
        }
        for (var e : a.entrySet()) {
            HttpRequest req = HttpRequest.newBuilder(URI.create(String.format(Locale.ROOT,
                    "http://127.0.0.1:%d/duerme?x=%d&y=%d&z=%d", e.getValue(),
                    bed.getX(), bed.getY(), bed.getZ())))
                    .timeout(Duration.ofSeconds(2)).GET().build();
            http.sendAsync(req, HttpResponse.BodyHandlers.discarding());
            Logbook.note("sleep", "I warn " + e.getKey()
                    + " that I am going to sleep, sleep too");
        }
    }

    static boolean isGuard(Entity e) {
        return e instanceof Player p
                && ownedByMeSet().containsKey(p.getGameProfile().getName().toLowerCase(Locale.ROOT));
    }

    /** The guard whose body stands in that box (the cell of a block), or null. */
    static Player guardAt(Minecraft mc, AABB box) {
        if (mc.level == null || ownedByMeSet().isEmpty()) return null;
        for (Player p : mc.level.players()) {
            if (p == mc.player || !isGuard(p)) continue;
            if (p.getBoundingBox().intersects(box)) return p;
        }
        return null;
    }

    /** The guard within r of the segment a-b (sampled every half block). */
    static Player guardBetween(Minecraft mc, Vec3 a, Vec3 b, double r) {
        if (mc.level == null || ownedByMeSet().isEmpty()) return null;
        int steps = Math.max(1, (int) Math.ceil(a.distanceTo(b) / 0.5));
        for (Player p : mc.level.players()) {
            if (p == mc.player || !isGuard(p)) continue;
            AABB box = p.getBoundingBox().inflate(r);
            for (int i = 0; i <= steps; i++) {
                if (box.contains(a.lerp(b, (double) i / steps))) return p;
            }
        }
        return null;
    }

    /**
     * Asks the guard to step away from the segment a-b (a point if b is null) to r
     * blocks. Asynchronous and rate-limited: one request every 2 s per guard, one logbook
     * line every 10 s.
     */
    static void moveAside(Player guard, Vec3 a, Vec3 b, double r, String because) {
        String name = guard.getGameProfile().getName();
        Integer port = ownedByMeSet().get(name.toLowerCase(Locale.ROOT));
        if (port == null) return;
        long now = System.nanoTime();
        synchronized (lastRequest) {
            Long before = lastRequest.get(name);
            if (before != null && now - before < BETWEEN_REQUESTS_NS) return;
            lastRequest.put(name, now);
        }
        if (b == null) b = a;
        String q = String.format(Locale.ROOT,
                "x1=%.2f&y1=%.2f&z1=%.2f&x2=%.2f&y2=%.2f&z2=%.2f&r=%.2f",
                a.x, a.y, a.z, b.x, b.y, b.z, r);
        HttpRequest req = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + "/apartate?" + q))
                .timeout(Duration.ofSeconds(2)).GET().build();
        http.sendAsync(req, HttpResponse.BodyHandlers.discarding());
        synchronized (lastNotice) {
            Long before = lastNotice.get(name);
            if (before != null && now - before < BETWEEN_NOTICES_NS) return;
            lastNotice.put(name, now);
        }
        Logbook.note("guard", String.format("I ask %s to make way: %s", name, because));
    }
}
