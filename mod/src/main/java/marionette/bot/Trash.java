package marionette.bot;

import marionette.common.Logbook;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the bot tosses ON ITS OWN when the backpack fills up.
 *
 * <p>A full backpack (36 of 36, with hundreds of cobblestone) left the bot circling some
 * redstone dust that did not fit. Hence a list of items it tosses automatically when the
 * inventory is full.
 *
 * <p>Two nuances that are not decorative. It only tosses <b>with the backpack full</b>,
 * never for the sake of it: surplus stone is surplus only when it is in the way. And of
 * what serves to build (scaffolding: cobblestone, dirt...) it <b>keeps one stack</b>:
 * that is what it plugs gaps and crosses ravines with, and running out of it to make room
 * would trade one problem for another.
 *
 * <p>Per server, like banned food: what is trash in a mine is material on a building
 * site.
 */
final class Trash {

    private Trash() {}

    /** What the list is born with: what fills backpacks in any mine. */
    private static final List<String> FACTORY = List.of(
            "cobblestone", "cobbled_deepslate", "tuff", "granite", "diorite",
            "andesite", "dirt", "gravel");

    private static Path file() {
        return ServerIdentity.file("trash");
    }

    private static Set<String> list;

    private static synchronized Set<String> load() {
        if (list != null) return list;
        list = new LinkedHashSet<>();
        try {
            if (Files.exists(file())) {
                for (String line : Files.readAllLines(file())) {
                    line = line.strip().toLowerCase();
                    if (!line.isEmpty() && !line.startsWith("#")) list.add(line);
                }
                return list;
            }
        } catch (IOException ignored) {
            // Unreadable list: start with the factory one and rewrite it.
        }
        list.addAll(FACTORY);
        save();
        return list;
    }

    private static void save() {
        try {
            Path f = file();
            Files.createDirectories(f.getParent());
            List<String> lines = new ArrayList<>();
            lines.add("# Trash: what I toss ON MY OWN when my backpack fills up "
                    + "(of what serves as scaffolding I keep one stack).");
            lines.addAll(list);
            Files.write(f, lines);
        } catch (IOException e) {
            Logbook.note("trash", "could not save the trash list: " + e.getMessage());
        }
    }

    static synchronized boolean isTrash(String id) {
        return id != null && load().contains(id.strip().toLowerCase());
    }

    /** @return true if it was not already there */
    static synchronized boolean add(String id) {
        boolean fresh = load().add(id.strip().toLowerCase());
        if (fresh) save();
        return fresh;
    }

    /** @return true if it was there */
    static synchronized boolean remove(String id) {
        boolean was = load().remove(id.strip().toLowerCase());
        if (was) save();
        return was;
    }

    static synchronized List<String> list() {
        return new ArrayList<>(load());
    }

    /**
     * Tosses the surplus trash to make room. Called ONLY with the backpack full. Returns
     * how many stacks it tossed.
     *
     * <p>The slot in hand is not touched (it could be halfway through placing a block),
     * nothing is tossed while eating, and nothing is done with a chest or furnace open:
     * the clicks would go to that menu.
     */
    static synchronized int tossSurplus(LocalPlayer p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || MarionetteBot.eating()) return 0;
        if (p.containerMenu != p.inventoryMenu) return 0;
        var inv = p.getInventory();
        // By id: which slots hold it, to decide which one stays.
        Map<String, List<Integer>> byId = new HashMap<>();
        for (int i = 0; i < 36; i++) {
            if (i == inv.selected) continue;
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
            if (!isTrash(id)) continue;
            byId.computeIfAbsent(id, k -> new ArrayList<>()).add(i);
        }
        int tosses = 0;
        List<String> what = new ArrayList<>();
        for (var entry : byId.entrySet()) {
            List<Integer> slots = entry.getValue();
            ItemStack sample = inv.getItem(slots.get(0));
            boolean scaffold = Builder.isScaffold(sample);
            // Of what serves to build, the biggest stack is kept.
            int save = -1;
            if (scaffold) {
                for (int r : slots) {
                    if (save < 0 || inv.getItem(r).getCount() > inv.getItem(save).getCount()) save = r;
                }
                // If it is in hand it was already skipped above; if there is no other
                // stack, nothing of that id is tossed.
            }
            for (int r : slots) {
                if (r == save) continue;
                int inMenuFlag = r < 9 ? 36 + r : r;
                mc.gameMode.handleInventoryMouseClick(p.inventoryMenu.containerId,
                        inMenuFlag, 1, ClickType.THROW, p);
                tosses++;
            }
            if (slots.size() > (save >= 0 ? 1 : 0)) what.add(entry.getKey());
        }
        if (tosses > 0) {
            Logbook.note("trash", String.format("backpack full: I tossed %d stacks of %s",
                    tosses, String.join(", ", what)));
        }
        return tosses;
    }
}
