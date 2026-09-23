package masurium.bot;

import masurium.common.Logbook;
import masurium.common.Request;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Using furnaces: loading them, emptying them and looking inside.
 *
 * <p>It is done <b>the way a player would</b>: right-click the furnace, wait for the
 * server to open the menu, move the stacks with the usual inventory clicks, and close. No
 * server-side magic; the ghost block lesson applies here too: what is inside the furnace
 * is read from the OPEN MENU, which is data synced by the server, not from what we
 * believe we put in.
 *
 * <p>Opening a menu takes a round trip to the server, so this is a state machine in the
 * tick, like the other behaviours: the HTTP request starts the job and the outcome is
 * asked for later.
 *
 * <p>Stacks are moved with {@code QUICK_MOVE} (shift-click): in the furnace menu the game
 * itself routes what can be smelted to the input, fuel to its slot and cooked items to
 * the inventory. Less code and none of our own slot arithmetic, the kind that already bit
 * once with the 0-8/1-9 hotbar.
 */
final class FurnaceHandler {

    /** Click reach, from the eyes: the lesson of the bed. */
    private static final double REACH = 4.0;
    /** Ticks to wait for the server to open the menu before giving up. */
    private static final int MENU_WAIT = 60;

    private enum Job { NONE, LOAD, TAKE_OUT, LOOK }

    private Job job = Job.NONE;
    private BlockPos furnace;
    private String what;           // LOAD: id to smelt. TAKE: which slot.
    private String fuelItem;   // fuel id (LOAD only)
    private int ticks;
    private String outcome = "I have not used any furnace yet";
    private boolean working;
    /**
     * Until when the last furnace loaded is COOKING (10 s per item) and where: for the
     * "cooking" state in the TAB. Cleared when taking out with the input empty.
     */
    private long cookingUntil;
    private BlockPos cookingAt;

    /** Starts a job. @return null if it is under way, or the reason. */
    synchronized String begin(String mode, BlockPos where,
                                String what, String fuelItem) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return "I am in no world";
        if (working) return "I am already at a furnace; wait until I am done";

        var state = mc.level.getBlockState(where);
        String block = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
        if (!block.contains("furnace") && !block.contains("smoker")) {
            // If this spot was in the places memory, it no longer counts: when arriving
            // and the block is gone, it is deleted from memory.
            String extra = Places.forget(where)
                    ? " (I had it in my memory; I deleted it)" : "";
            return String.format("at %d %d %d there is %s, not a furnace%s",
                    where.getX(), where.getY(), where.getZ(), block, extra);
        }
        if (Places.remember("furnace", where)) {
            Logbook.note("places", String.format("I remember a furnace at %s",
                    where.getX() + " " + where.getY() + " " + where.getZ()));
        }
        double d = p.getEyePosition().distanceTo(Vec3.atCenterOf(where));
        if (d > REACH) {
            return String.format("the furnace is %.1f away and I reach %.1f; "
                    + "I have to get closer", d, REACH);
        }

        this.furnace = where;
        this.what = what;
        this.fuelItem = fuelItem;
        this.job = switch (mode) {
            case "load" -> Job.LOAD;
            case "take_out" -> Job.TAKE_OUT;
            default -> Job.LOOK;
        };
        this.ticks = 0;
        this.outcome = null;
        this.working = true;

        // The click that opens it. The menu will arrive in a future tick.
        Vec3 center = Vec3.atCenterOf(where);
        p.lookAt(EntityAnchorArgument.Anchor.EYES, center);
        mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND,
                new BlockHitResult(center, Direction.NORTH, where, false));
        p.swing(InteractionHand.MAIN_HAND);
        return null;
    }

    synchronized void tick() {
        if (!working) return;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) { finish("I left the world"); return; }

        if (!(p.containerMenu instanceof AbstractFurnaceMenu m)) {
            if (++ticks > MENU_WAIT) {
                finish("the furnace did not open (is someone else using it?)");
            }
            return;
        }

        // Menu open: everything is done in this tick and it is closed.
        switch (job) {
            case LOAD -> {
                // Before loading, CLEAR: with the output taken by what was cooked before
                // (pork chops), the furnace smelts nothing new; and an input holding
                // something else does not let the requested item in either. Loading 49
                // iron on top of some pork chops left the furnace "stuck". What is taken
                // out goes to the backpack and is counted.
                StringBuilder cleared = new StringBuilder();
                ItemStack exitPoint = m.slots.get(2).getItem().copy();
                if (!exitPoint.isEmpty()) {
                    mc.gameMode.handleInventoryMouseClick(
                            m.containerId, 2, 0, ClickType.QUICK_MOVE, p);
                    cleared.append(exitPoint.getCount()).append(" ")
                             .append(id(exitPoint)).append(" from the output");
                }
                ItemStack entry = m.slots.get(0).getItem().copy();
                if (!entry.isEmpty() && what != null && !id(entry).equals(what)) {
                    mc.gameMode.handleInventoryMouseClick(
                            m.containerId, 0, 0, ClickType.QUICK_MOVE, p);
                    if (cleared.length() > 0) cleared.append(" and ");
                    cleared.append(entry.getCount()).append(" ")
                             .append(id(entry)).append(" from the input");
                }
                if (cleared.length() > 0) {
                    Logbook.note("furnace", "I took out " + cleared
                            + " before loading");
                }
                int movedOnes = move(mc, p, m, what);
                int fuel = fuelItem == null || fuelItem.isBlank()
                        ? 0 : move(mc, p, m, fuelItem);
                String inside = contents(m);
                if (movedOnes == 0) {
                    finish(String.format("I carry no %s to put in; %s",
                            what, inside));
                } else {
                    Logbook.note("furnace", String.format(
                            "I loaded the furnace at %s: %s",
                            shortPos(), inside));
                    int items = m.slots.get(0).getItem().getCount();
                    cookingUntil = System.currentTimeMillis() + 10_000L * Math.max(items, 1);
                    cookingAt = furnace;
                    finish(String.format("loaded (%d stacks of %s, %d of "
                            + "fuel)%s; %s", movedOnes, what, fuel,
                            cleared.length() > 0
                                    ? ", after taking out " + cleared : "",
                            inside));
                }
            }
            case TAKE_OUT -> {
                // Slots of the furnace menu, fixed by the game: 0 input, 1 fuel, 2
                // output. By default only the cooked items come out; "input" recovers the
                // raw items STILL smelting, which is how a load is undone (for example,
                // to get back a salmon already put in).
                String which = what == null ? "" : what;
                int[] slots = switch (which) {
                    case "entry", "raw" -> new int[] {0};
                    case "fuel" -> new int[] {1};
                    case "everything" -> new int[] {2, 0, 1};
                    default -> new int[] {2};
                };
                String caption = switch (which) {
                    case "entry", "raw" -> "the input";
                    case "fuel" -> "the fuel";
                    case "everything" -> "the furnace";
                    default -> "the output";
                };
                StringBuilder takenOut = new StringBuilder();
                int stacks = 0;
                for (int r : slots) {
                    ItemStack outside = m.slots.get(r).getItem().copy();
                    if (outside.isEmpty()) continue;
                    mc.gameMode.handleInventoryMouseClick(
                            m.containerId, r, 0, ClickType.QUICK_MOVE, p);
                    if (stacks > 0) takenOut.append(", ");
                    takenOut.append(outside.getCount()).append(" ")
                            .append(id(outside));
                    stacks++;
                }
                if (stacks == 0) {
                    finish(String.format("there was nothing to take out of %s; %s",
                            caption, contents(m)));
                } else {
                    Logbook.note("furnace", String.format(
                            "I took %s out of the furnace at %s", takenOut, shortPos()));
                    if (m.slots.get(0).getItem().isEmpty()) cookingUntil = 0;
                    finish(String.format("I took out %s; %s", takenOut,
                            contents(m)));
                }
            }
            case LOOK -> finish(contents(m));
            default -> finish("I had no job");
        }
        p.closeContainer();
    }

    /**
     * Puts into the furnace every stack of the player's with that id, by QUICK_MOVE: the
     * menu itself decides whether they go to the input or to the fuel.
     *
     * @return how many stacks moved (fully or partially)
     */
    private static int move(Minecraft mc, LocalPlayer p,
                             AbstractFurnaceMenu m, String soughtId) {
        int movedOnes = 0;
        // Slots 0-2 belong to the furnace; 3 and up, to the player.
        for (int i = 3; i < m.slots.size(); i++) {
            ItemStack stack = m.slots.get(i).getItem();
            if (!stack.isEmpty() && id(stack).equals(soughtId)) {
                mc.gameMode.handleInventoryMouseClick(
                        m.containerId, i, 0, ClickType.QUICK_MOVE, p);
                movedOnes++;
            }
        }
        return movedOnes;
    }

    /** What is inside, read from the menu: server data, not belief. */
    private static String contents(AbstractFurnaceMenu m) {
        return String.format("inside: input=%s, fuel=%s, output=%s",
                stack(m.slots.get(0).getItem()),
                stack(m.slots.get(1).getItem()),
                stack(m.slots.get(2).getItem()));
    }

    private static String stack(ItemStack s) {
        return s.isEmpty() ? "empty" : s.getCount() + " " + id(s);
    }

    private static String id(ItemStack s) {
        return BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
    }

    private String shortPos() {
        return furnace.getX() + " " + furnace.getY() + " " + furnace.getZ();
    }

    private void finish(String how) {
        working = false;
        job = Job.NONE;
        outcome = how;
    }

    synchronized void stop(String because) {
        if (working) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) mc.player.closeContainer();
        }
        finish(because);
    }

    synchronized String state() {
        long missingCount = Math.max(0, (cookingUntil - System.currentTimeMillis()) / 1000);
        String cooking = String.format(",\"cooking\":%b,\"missing_s\":%d,\"at\":\"%s\"",
                missingCount > 0, missingCount,
                cookingAt == null ? "" : cookingAt.toShortString());
        if (!working) {
            return String.format("{\"with_furnace\":false,\"outcome\":\"%s\"%s}",
                    Request.escape(outcome), cooking);
        }
        return String.format("{\"with_furnace\":true,\"job\":\"%s\"%s}",
                job.name().toLowerCase(), cooking);
    }

    synchronized boolean working() {
        return working;
    }
}
