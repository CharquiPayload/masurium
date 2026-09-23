package masurium.server;

import masurium.common.Request;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Crafting, on the server side.
 *
 * <p><b>Why here and not in the bot mod.</b> The rule of this project is "ask the server,
 * act with the bot", and crafting looks like an action. But it is not: it is a
 * <i>transaction of rules</i> (does the recipe exist?, does the player have the
 * materials?, is there a table nearby?) and the server knows the rules. The bot could
 * only <i>simulate</i> the clicks of a window, and that is where almost all the failures
 * used to live: opening the table took four phases, none in the same tick; placing,
 * collecting and closing together returned the grid before the crafting resolved; and the
 * recipe book meant only the recipes the player already knew worked.
 *
 * <p>Here the window goes away and the rules stay. And as a bonus, the server knows
 * <b>every</b> loaded recipe, including the ones from mods.
 */
final class Workshop {

    /**
     * Reach to the table for a 3x3 recipe, in blocks. Stricter than the game's real reach
     * (4.5) on purpose.
     */
    private static final double NEAR_TABLE = 2.0;

    private final MinecraftServer server;

    Workshop(MinecraftServer server) {
        this.server = server;
    }

    /** What an item needs. It never makes things up: it comes from the game itself. */
    String recipe(Map<String, String> q) {
        String object = requested(q);
        List<Item> whoAll = candidates(object);
        if (whoAll.isEmpty()) return dontKnow(object);
        if (whoAll.size() > 1) return ambiguous(object, whoAll);
        Item sought = whoAll.get(0);
        List<RecipeHolder<CraftingRecipe>> allItems = recipesOf(sought);
        if (allItems.isEmpty()) return noTable(sought);

        // EVERY way of making it. A stick comes from planks and from bamboo: showing only
        // one gives wrong information to whoever carries the other.
        List<String> shapes = new ArrayList<>();
        for (RecipeHolder<CraftingRecipe> r : allItems) {
            List<String> ingredients = new ArrayList<>();
            for (Ingredient ing : r.value().getIngredients()) {
                if (ing.isEmpty()) continue;
                List<String> options = new ArrayList<>();
                for (ItemStack stack : ing.getItems()) {
                    options.add(shortId(stack.getItem()));
                }
                ingredients.add("[\"" + String.join("\",\"", options) + "\"]");
            }
            ItemStack exits = r.value().getResultItem(server.registryAccess());
            shapes.add(String.format(
                    "{\"yields\":%d,\"needs_table\":%b,\"ingredients\":[%s]}",
                    exits.getCount(), !r.value().canCraftInDimensions(2, 2),
                    String.join(",", ingredients)));
        }
        ItemStack exits = allItems.get(0).value()
                .getResultItem(server.registryAccess());
        return String.format(
                "{\"ok\":true,\"object\":\"%s\",\"shapes\":[%s]}",
                shortId(exits.getItem()), String.join(",", shapes));
    }

    /** Crafts for real: checks, consumes and hands over. */
    String craft(Map<String, String> q) {
        String object = requested(q);
        int times = Math.max(1, Math.min(64,
                Integer.parseInt(q.getOrDefault("times", "1"))));

        ServerPlayer p = server.getPlayerList()
                .getPlayerByName(q.getOrDefault("player", ""));
        if (p == null) {
            return "{\"ok\":false,\"error\":\"that player is not connected\"}";
        }
        List<Item> whoAll = candidates(object);
        if (whoAll.isEmpty()) return dontKnow(object);
        if (whoAll.size() > 1) return ambiguous(object, whoAll);
        RecipeHolder<CraftingRecipe> r = recipeItCanMake(p, whoAll.get(0));
        if (r == null) return noTable(whoAll.get(0));

        boolean needsTable = !r.value().canCraftInDimensions(2, 2);
        if (needsTable && !hasTableNear(p)) {
            return String.format(
                    "{\"ok\":false,\"error\":\"that recipe needs a crafting table "
                    + "and there is none within %.0f blocks\"}", NEAR_TABLE);
        }

        List<Ingredient> required = noGaps(r);

        int doneOnes = 0;
        int toGround = 0;
        for (int i = 0; i < times; i++) {
            if (!hasEverything(p, required)) break;
            for (Ingredient ing : required) spendOne(p, ing);
            ItemStack exits = r.value().getResultItem(server.registryAccess()).copy();
            // If it does not fit it drops to the ground, and THAT HAS TO BE SAID.
            // Answering "done" while the crafted item lies on the ground is exactly the
            // kind of lie this project exists to kill.
            if (!p.getInventory().add(exits)) {
                p.drop(exits, false);
                toGround += exits.getCount();
            }
            doneOnes++;
        }

        if (doneOnes == 0) {
            return String.format(
                    "{\"ok\":false,\"error\":\"I do not have enough materials for "
                    + "%s\",\"needs_table\":%b}", Request.escape(object),
                    needsTable);
        }
        ItemStack exits = r.value().getResultItem(server.registryAccess());
        String notice = toGround > 0
                ? String.format(",\"notice\":\"they did not fit: %d units were left on "
                                + "the ground, they have to be picked up\"", toGround)
                : "";
        return String.format(
                "{\"ok\":true,\"object\":\"%s\",\"done_count\":%d,\"requested_count\":%d,"
                + "\"units\":%d,\"to_ground\":%d,\"uses_table\":%b%s}",
                shortId(exits.getItem()),
                doneOnes, times, doneOnes * exits.getCount(), toGround, needsTable,
                notice);
    }

    // --- plumbing -------------------------------------------------------------

    private static String requested(Map<String, String> q) {
        String o = q.getOrDefault("object", "").trim().toLowerCase();
        return o.startsWith("minecraft:") ? o.substring(10) : o;
    }

    private static String dontKnow(String object) {
        // Loud and with a hint: the model tends to say the name in its own language, and
        // accepting that silently would leave the bot waiting for something that never
        // comes.
        return String.format(
                "{\"ok\":false,\"error\":\"there is no object '%s'; "
                + "ids go in English (stone_pickaxe, oak_planks) and modded ones "
                + "work too, with or without prefix (cogwheel or "
                + "create:cogwheel)\"}", Request.escape(object));
    }

    /**
     * The items answering to that name, mods included. Pinning the `minecraft:` prefix
     * made the bot "not see" anything from Create: neither `cogwheel` nor
     * `create:cogwheel` reached the registry.
     */
    private static List<Item> candidates(String object) {
        List<Item> list = new ArrayList<>();
        if (object.isEmpty()) return list;
        if (object.contains(":")) {
            ResourceLocation id = ResourceLocation.tryParse(object);
            if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
                list.add(BuiltInRegistries.ITEM.get(id));
            }
            return list;
        }
        ResourceLocation vanilla = ResourceLocation.tryParse("minecraft:" + object);
        if (vanilla != null && BuiltInRegistries.ITEM.containsKey(vanilla)) {
            list.add(BuiltInRegistries.ITEM.get(vanilla));
            return list;
        }
        // Not vanilla: walk through the mods. The name almost always has a single owner;
        // if there are several, they are listed and whoever asks chooses.
        for (ResourceLocation id : BuiltInRegistries.ITEM.keySet()) {
            if (id.getPath().equals(object)) {
                list.add(BuiltInRegistries.ITEM.get(id));
            }
        }
        return list;
    }

    /**
     * The id the way a person says it: bare if vanilla, with its mod in front otherwise,
     * since "cogwheel" from create and from another mod are not the same.
     */
    private static String shortId(Item item) {
        ResourceLocation k = BuiltInRegistries.ITEM.getKey(item);
        return k.getNamespace().equals("minecraft") ? k.getPath() : k.toString();
    }

    private static String ambiguous(String object, List<Item> whoAll) {
        List<String> ids = new ArrayList<>();
        for (Item i : whoAll) ids.add(shortId(i));
        return String.format(
                "{\"ok\":false,\"error\":\"there are several '%s': %s. Ask me with "
                + "the full id\"}", Request.escape(object),
                Request.escape(String.join(", ", ids)));
    }

    /**
     * The item exists but no crafting table recipe gives it. Saying "no recipe" here
     * would be a lie: many Create things come from its machines (press, mixer, assembly),
     * and that has to be told, even though making them is still not the bot's job.
     */
    private String noTable(Item item) {
        List<String> types = new ArrayList<>();
        for (RecipeHolder<?> r : server.getRecipeManager().getRecipes()) {
            try {
                ItemStack exits = r.value().getResultItem(server.registryAccess());
                if (exits != null && !exits.isEmpty() && exits.getItem() == item) {
                    String t = String.valueOf(BuiltInRegistries.RECIPE_TYPE
                            .getKey(r.value().getType()));
                    if (!types.contains(t)) types.add(t);
                }
            } catch (Exception any) {
                // An exotic recipe that blows up when asked cannot take down the whole
                // query.
            }
        }
        types.remove("minecraft:crafting");
        if (types.isEmpty()) {
            return String.format(
                    "{\"ok\":false,\"error\":\"'%s' has no recipe: it is not "
                    + "crafted, it is obtained\"}", shortId(item));
        }
        return String.format(
                "{\"ok\":false,\"error\":\"'%s' is not made at the table: it comes "
                + "from %s. That is done by the mod's machines, not me\"}",
                shortId(item), Request.escape(String.join(", ", types)));
    }

    /**
     * Every crafting table recipe that gives that item. A stick comes from planks AND
     * from bamboo: keeping the first one found is tossing a coin.
     */
    private List<RecipeHolder<CraftingRecipe>> recipesOf(Item sought) {
        List<RecipeHolder<CraftingRecipe>> foundOnes = new ArrayList<>();
        for (RecipeHolder<CraftingRecipe> r : server.getRecipeManager()
                .getAllRecipesFor(RecipeType.CRAFTING)) {
            ItemStack exits = r.value().getResultItem(server.registryAccess());
            if (!exits.isEmpty() && exits.getItem() == sought) foundOnes.add(r);
        }
        return foundOnes;
    }

    /** The recipe it can really make with what it carries. */
    private RecipeHolder<CraftingRecipe> recipeItCanMake(ServerPlayer p,
                                                             Item sought) {
        List<RecipeHolder<CraftingRecipe>> allItems = recipesOf(sought);
        for (RecipeHolder<CraftingRecipe> r : allItems) {
            if (hasEverything(p, noGaps(r))) return r;
        }
        return allItems.isEmpty() ? null : allItems.get(0);
    }

    private static List<Ingredient> noGaps(RecipeHolder<CraftingRecipe> r) {
        List<Ingredient> list = new ArrayList<>();
        for (Ingredient ing : r.value().getIngredients()) {
            if (!ing.isEmpty()) list.add(ing);
        }
        return list;
    }

    private boolean hasTableNear(ServerPlayer p) {
        int r = (int) Math.ceil(NEAR_TABLE);
        BlockPos me = p.blockPosition();
        for (BlockPos b : BlockPos.betweenClosed(me.offset(-r, -r, -r),
                                                 me.offset(r, r, r))) {
            if (Math.sqrt(b.distToCenterSqr(p.position())) > NEAR_TABLE + 0.5) {
                continue;   // the box is a cube; the requested reach is a sphere
            }
            if (p.level().getBlockState(b).is(Blocks.CRAFTING_TABLE)) return true;
        }
        return false;
    }

    private static boolean hasEverything(ServerPlayer p, List<Ingredient> required) {
        // Counted on a copy: otherwise a repeated ingredient would count as covered twice
        // by the same stack.
        List<ItemStack> copy = new ArrayList<>();
        for (ItemStack stack : p.getInventory().items) copy.add(stack.copy());
        for (Ingredient ing : required) {
            boolean found = false;
            for (ItemStack stack : copy) {
                if (!stack.isEmpty() && ing.test(stack)) {
                    stack.shrink(1);
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    private static void spendOne(ServerPlayer p, Ingredient ing) {
        var inv = p.getInventory();
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack stack = inv.items.get(i);
            if (!stack.isEmpty() && ing.test(stack)) {
                stack.shrink(1);
                if (stack.isEmpty()) inv.items.set(i, ItemStack.EMPTY);
                return;
            }
        }
    }
}
