package masurium.bot;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceLocation;

import java.util.Collection;

/**
 * Item and block ids, as the rules hold them and as the game names things. A rule names
 * a thing by its full id ({@code create:cog}), which is exactly that, or by its name
 * alone ({@code beef}, {@code cog}), which is that name in whatever mod has it: the
 * game's own id is kept bare, and a player who writes {@code cog} means the cog.
 */
final class Ids {
    private Ids() {
    }

    /**
     * Whether a list names the thing whose id is {@code id}: its full id as the game
     * gives it ({@code create:cog}, {@code minecraft:beef}), found in the list whole or
     * by its name alone.
     */
    static boolean listed(Collection<String> list, String id) {
        if (id == null) return false;
        String full = id.strip().toLowerCase();
        if (list.contains(full)) return true;
        int colon = full.indexOf(':');
        return colon >= 0 && list.contains(full.substring(colon + 1));
    }

    /**
     * Whether this registry has what a rule's id names: exactly, with a namespace;
     * without one, the game's own, or any mod's of that name.
     */
    static boolean known(Registry<?> registry, String id) {
        if (id.indexOf(':') >= 0) {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            return rl != null && registry.containsKey(rl);
        }
        ResourceLocation vanilla = ResourceLocation.tryParse("minecraft:" + id);
        if (vanilla != null && registry.containsKey(vanilla)) return true;
        for (ResourceLocation key : registry.keySet()) {
            if (key.getPath().equals(id)) return true;
        }
        return false;
    }
}
