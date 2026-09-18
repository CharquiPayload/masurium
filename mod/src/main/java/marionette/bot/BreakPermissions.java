package marionette.bot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/**
 * Which blocks the bot may BREAK when IT decides to clear its way. A whitelist, nothing
 * more.
 *
 * <p>It is the third leg of the safety around breaking things: the marked area says WHERE
 * things may be touched, the build opt-in says WHEN, and this list says WHAT. The spatial
 * limit alone does not protect against digging what must not be dug INSIDE the box.
 *
 * <p>The AI manages the list itself through the chat ("you may break dirt"), but the LOCK
 * is here, in the mod. It only rules where the bot breaks WITHOUT being asked: the Walker
 * when going through a plug and {@link ClientWorld#breakable} when planning the route.
 * What it is TOLD to dig, gather, mine or clear is broken without looking at the list:
 * the order already is the permission ({@link Miner#begin} does not check it).
 *
 * <p>It lives in a plain file in the gamedir config, one id per line, so it survives
 * restarts and can be read and edited by hand.
 */
final class BreakPermissions {

    private BreakPermissions() {}

    /**
     * Per server, like every toggle. Resolved late because it migrates the old global
     * file if there is one.
     */
    private static Path file() {
        return ServerIdentity.file("permissions");
    }

    /** The seed if the file does not exist. */
    private static final List<String> SEED = List.of(
            "stone", "cobblestone", "grass_block", "dirt");

    /** Sorted so the list always reads the same. */
    private static TreeSet<String> list;

    private static synchronized TreeSet<String> load() {
        if (list != null) return list;
        list = new TreeSet<>();
        try {
            if (Files.exists(file())) {
                for (String line : Files.readAllLines(file())) {
                    line = line.trim();
                    if (!line.isEmpty() && !line.startsWith("#")) {
                        list.add(line);
                    }
                }
            } else {
                list.addAll(SEED);
                save();
            }
        } catch (IOException e) {
            // Without a readable file the seed stays in memory: better a factory
            // permission than a bot that cannot break anything or, worse, one that can
            // break everything.
            list.addAll(SEED);
        }
        return list;
    }

    private static void save() {
        try {
            Files.createDirectories(file().getParent());
            List<String> lines = new ArrayList<>();
            lines.add("# Blocks the bot is allowed to break.");
            lines.add("# Managed by the AI through the chat; the lock is in Miner.");
            lines.addAll(list);
            Files.write(file(), lines);
        } catch (IOException e) {
            // Noted and carried on: the permission in memory already changed, and
            // refusing to work because a file could not be written would be worse.
            marionette.common.Logbook.note(
                    "permissions", "could not save the list: " + e.getMessage());
        }
    }

    /**
     * My BOSS's list, if I am a guard: guards get the same block whitelist as the lead
     * bot. It is read from the boss's gamedir and reloaded when it changes; empty if I am
     * not a guard or it cannot be read.
     */
    private static long bossMtime = -1;
    private static java.util.Set<String> ofTheBoss = java.util.Set.of();

    private static synchronized java.util.Set<String> ofTheBoss() {
        String boss = MarionetteBot.boss();
        if (boss == null) return java.util.Set.of();
        Path f = Path.of("..", "..", boss.toLowerCase(), "gamedir").resolve(file());
        try {
            if (!Files.exists(f)) return ofTheBoss;
            long m = Files.getLastModifiedTime(f).toMillis();
            if (m != bossMtime) {
                TreeSet<String> fresh = new TreeSet<>();
                for (String line : Files.readAllLines(f)) {
                    String l = line.strip();
                    if (!l.isEmpty() && !l.startsWith("#")) fresh.add(l);
                }
                ofTheBoss = fresh;
                bossMtime = m;
            }
        } catch (java.io.IOException e) {
            // The last reading stays: a read failure does not take permissions away.
        }
        return ofTheBoss;
    }

    static synchronized boolean mayIBreak(String id) {
        return load().contains(id) || ofTheBoss().contains(id);
    }

    // --- the free zone ------------------------------------------------------
    // While the FillWorker works an area it was told to empty, fill or clear for a
    // blueprint, EVERYTHING inside that box may be broken, whether it is on the list or
    // not: an order to break an area already allows breaking every block inside that area
    // (and only those). This turns the note above around on purpose. Outside the box, and
    // with no job running, the list rules. The FillWorker sets and removes it
    // (start/stop).

    private static net.minecraft.core.BlockPos freeA, freeB;

    static synchronized void freeZone(net.minecraft.core.BlockPos a,
                                        net.minecraft.core.BlockPos b) {
        freeA = a;
        freeB = b;
    }

    static synchronized void noFreeZone() {
        freeA = null;
        freeB = null;
    }

    static synchronized boolean inFreeZone(net.minecraft.core.BlockPos p) {
        if (freeA == null || freeB == null || p == null) return false;
        return p.getX() >= Math.min(freeA.getX(), freeB.getX())
                && p.getX() <= Math.max(freeA.getX(), freeB.getX())
                && p.getY() >= Math.min(freeA.getY(), freeB.getY())
                && p.getY() <= Math.max(freeA.getY(), freeB.getY())
                && p.getZ() >= Math.min(freeA.getZ(), freeB.getZ())
                && p.getZ() <= Math.max(freeA.getZ(), freeB.getZ());
    }

    /** The list, or the free zone if {@code where} falls inside it. */
    static synchronized boolean mayIBreak(String id, net.minecraft.core.BlockPos where) {
        return mayIBreak(id) || inFreeZone(where);
    }

    /** Mine and my boss's, together and sorted. */
    static synchronized List<String> everyone() {
        TreeSet<String> t = new TreeSet<>(own());
        t.addAll(ofTheBoss());
        return new java.util.ArrayList<>(t);
    }

    static synchronized void allow(String id) {
        load().add(id);
        save();
    }

    /** @return true if it was there and was removed */
    static synchronized boolean forbid(String id) {
        boolean was = load().remove(id);
        if (was) save();
        return was;
    }

    static synchronized List<String> own() {
        return new ArrayList<>(load());
    }
}
