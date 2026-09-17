package marionette.server;

import marionette.common.Request;
import com.mojang.logging.LogUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.TriState;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * The server side: the source of truth.
 * The Minecraft client GUESSES: it predicts the result of its actions and its copy of the
 * world lags behind. The server does not. Everything that is a question ("is the block
 * still there?", "where is that player?") is answered here, which is how the bot stops
 * wondering "did I do it?".
 * It talks over HTTP and not over a Minecraft network channel, on purpose: whoever asks
 * is the agent, which lives outside the game, and that way there is no mod negotiation:
 * nobody joining the server needs to install anything.
 *
 * <p><b>Configuration</b>: {@code marionette.properties} in the server folder (host,
 * port, token). If it does not exist it is written with the defaults: localhost only,
 * which is the safe choice when nobody said otherwise. It goes through a file and not
 * through JVM arguments because servers are often run by a panel, and touching the
 * arguments behind its back desyncs the panel from what really runs.
 */
@Mod(MarionetteServer.ID)
public class MarionetteServer {

    public static final String ID = "marionette_server";
    private static final Logger LOG = LogUtils.getLogger();
    private static final Path CONFIG = Path.of("marionette.properties");

    /** Cap for a /search sweep. 32 is already ~260,000 tiles. */
    private static final int RADIUS_MAX = 48;

    private HttpServer http;
    private MinecraftServer server;
    private Workshop workshop;
    private String token = "";
    /** The players that are bots (marionette.properties, key bots). */
    private Set<String> bots = PickupRule.bots(DEFAULT_BOTS);
    private static final String DEFAULT_BOTS = "";

    /**
     * Latest chat messages, for the agent to read over HTTP. Its logic lives apart
     * because it can be tested without opening Minecraft.
     */
    private final ChatLog chat = new ChatLog(200);
    /** The state icon of each bot in the TAB list (see {@link Tab}). */
    private final Tab tab = new Tab();
    /** Who owns each bot and the hotbar notice (see {@link Owners}). */
    private final Owners owners = new Owners(tab);
    private final StatusBoard statusBoard = new StatusBoard(tab, owners);

    public MarionetteServer(IEventBus bus) {
        NeoForge.EVENT_BUS.register(this);
        NeoForge.EVENT_BUS.register(tab);
        NeoForge.EVENT_BUS.register(owners);
        NeoForge.EVENT_BUS.register(statusBoard);
    }

    @SubscribeEvent
    public void onStart(ServerStartedEvent event) {
        this.server = event.getServer();
        this.workshop = new Workshop(server);
        Properties cfg = loadConfig();
        String host = cfg.getProperty("host", "127.0.0.1").trim();
        int port = Integer.parseInt(cfg.getProperty("port", "8477").trim());
        token = cfg.getProperty("token", "").trim();
        bots = PickupRule.bots(cfg.getProperty("bots", DEFAULT_BOTS));
        LOG.info("[marionette] bots: {}", bots);

        if (!host.equals("127.0.0.1") && token.isEmpty()) {
            LOG.warn("[marionette] listening on {} WITHOUT a token: anyone who reaches "
                     + "this IP can command the bot", host);
        }

        try {
            http = HttpServer.create(new InetSocketAddress(host, port), 0);
            http.createContext("/health", x -> attend(x, this::health));
            http.createContext("/players", x -> attend(x, this::players));
            http.createContext("/block", x -> attend(x, this::block));
            http.createContext("/search", x -> attend(x, this::search));
            http.createContext("/chat", x -> attend(x, this::seeChat));
            http.createContext("/entities", x -> attend(x, this::entities));
            http.createContext("/where", x -> attend(x, this::where));
            http.createContext("/objects", x -> attend(x, this::objects));
            http.createContext("/recipe", x -> attend(x, this::recipe));
            http.createContext("/inventory", x -> attend(x, this::inventory));
            http.createContext("/craft", x -> attend(x, this::craft));
            http.createContext("/tab", x -> attend(x, this::tab));
            http.setExecutor(null);
            http.start();
            LOG.info("[marionette] escuchando en http://{}:{}  (token: {})",
                     host, port, token.isEmpty() ? "no" : "yes");
        } catch (IOException e) {
            LOG.error("[marionette] could NOT open {}:{}", host, port, e);
        }
    }

    @SubscribeEvent
    public void onStop(ServerStoppingEvent event) {
        if (http != null) {
            http.stop(0);
            LOG.info("[marionette] port closed");
        }
    }

    /**
     * What a bot tosses is not picked up again by THAT SAME bot. The rule and its reasons
     * are in {@link PickupRule}; here it is only told who steps on the item and whether
     * the item carries their signature ({@code getOwner()} is whoever dropped it).
     */
    @SubscribeEvent
    public void onPickup(ItemEntityPickupEvent.Pre event) {
        var who = event.getPlayer();
        var owner = event.getItemEntity().getOwner();
        boolean droppedItHimself = owner != null && owner.getUUID().equals(who.getUUID());
        if (PickupRule.veto(bots, who.getGameProfile().getName(), droppedItHimself)) {
            event.setCanPickup(TriState.FALSE);
        }
    }

    @SubscribeEvent
    public void onChat(ServerChatEvent event) {
        chat.add(event.getPlayer().getGameProfile().getName(),
                     event.getMessage().getString());
    }

    /**
     * /chat?from=N returns what was said AFTER id N, so it can be polled without
     * rereading or losing anything. Without 'from', it only tells the last id.
     */
    private String seeChat(Map<String, String> q) {
        String from = q.get("since");
        return chat.json(from == null ? null : Long.parseLong(from));
    }

    // --- the part that really matters ------------------------------------------

    /**
     * Runs something ON THE GAME THREAD and waits for the result.
     * HTTP handlers run on their own thread. Reading the world from there returns
     * half-baked state: the same "timing, not logic" mistake that once cost three bugs in
     * a single night. Everything that touches the world goes through here, no exceptions.
     */
    private <T> T inGame(Supplier<T> task) throws Exception {
        return server.submit(task::get).get(5, TimeUnit.SECONDS);
    }

    private String health(Map<String, String> q) throws Exception {
        return inGame(() -> {
            // The spawn is needed to have a starting point with loaded chunks: with no
            // players inside, almost the whole world is asleep.
            BlockPos s = server.overworld().getSharedSpawnPos();
            return String.format(
                "{\"ok\":true,\"mod\":\"%s\",\"players\":%d,\"tick\":%d,"
                + "\"spawn\":{\"x\":%d,\"y\":%d,\"z\":%d}}",
                ID, server.getPlayerCount(), server.getTickCount(),
                s.getX(), s.getY(), s.getZ());
        });
    }

    /**
     * /tab?player=Alice&state=thinking: the icon next to the name in the TAB list (idle,
     * working, thinking, combat, error, dead; none = as usual). The bridge sends it when
     * the state changes. The refresh runs on the game thread, like everything that
     * touches a player.
     */
    private String tab(Map<String, String> q) throws Exception {
        String player = q.getOrDefault("player", "").trim();
        String state = q.getOrDefault("state", "").trim().toLowerCase();
        if (player.isEmpty()) return "{\"ok\":false,\"error\":\"player missing\"}";
        String bad = tab.place(player, state);
        if (bad != null) {
            return "{\"ok\":false,\"error\":\"" + Request.escape(bad) + "\"}";
        }
        return inGame(() -> {
            ServerPlayer p = server.getPlayerList().getPlayerByName(player);
            if (p != null) p.refreshTabListName();
            return String.format(
                    "{\"ok\":true,\"player\":\"%s\",\"state\":\"%s\",\"connected\":%s}",
                    Request.escape(player), Request.escape(state), p != null);
        });
    }

    private String players(Map<String, String> q) throws Exception {
        return inGame(() -> {
            List<String> cards = new ArrayList<>();
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                cards.add(String.format(
                        "{\"name\":\"%s\",\"x\":%d,\"y\":%d,\"z\":%d,"
                        + "\"world\":\"%s\",\"hp\":%.1f}",
                        p.getGameProfile().getName(),
                        p.blockPosition().getX(), p.blockPosition().getY(),
                        p.blockPosition().getZ(),
                        p.level().dimension().location(), p.getHealth()));
            }
            return "{\"ok\":true,\"players\":[" + String.join(",", cards) + "]}";
        });
    }

    /**
     * The living things near a point, according to the SERVER.
     * It exists because the client cannot vouch for a mob's health: when hitting, its
     * copy lags a few ticks and returns a number that is not true yet. To know whether
     * something really died, ask here.
     */
    /**
     * Where an entity is, by its uuid, in ANY world and loaded or not. It is what the
     * client cannot answer: the bot only sees what it has loaded, so its horse stops
     * existing for it as soon as it wanders off.
     *
     * <p>A horse left alone wanders away, and the bot loses it; the server can tell it
     * where the horse is.
     */
    private String where(Map<String, String> q) throws Exception {
        String which = q.getOrDefault("uuid", "").trim();
        if (which.isEmpty()) return "{\"ok\":false,\"error\":\"uuid missing\"}";
        final UUID id;
        try {
            id = UUID.fromString(which);
        } catch (IllegalArgumentException e) {
            return "{\"ok\":false,\"error\":\"malformed uuid\"}";
        }
        return inGame(() -> {
            for (ServerLevel levelValue : server.getAllLevels()) {
                Entity e = levelValue.getEntity(id);
                if (e == null) continue;
                return String.format(
                        "{\"ok\":true,\"present\":true,\"type\":\"%s\",\"name\":\"%s\","
                        + "\"world\":\"%s\",\"x\":%d,\"y\":%d,\"z\":%d,\"alive\":%b}",
                        BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath(),
                        Request.escape(e.getName().getString()),
                        levelValue.dimension().location(),
                        e.blockPosition().getX(), e.blockPosition().getY(),
                        e.blockPosition().getZ(), e.isAlive());
            }
            // Not in any world: either it died, or it never existed.
            return "{\"ok\":true,\"present\":false}";
        });
    }

    private String entities(Map<String, String> q) throws Exception {
        BlockPos center = posOf(q);
        int radius = Math.min(RADIUS_MAX,
                Math.max(1, Integer.parseInt(q.getOrDefault("radius", "16"))));
        ServerLevel levelValue = world(q);
        return inGame(() -> {
            List<String> cards = new ArrayList<>();
            AABB box = new AABB(center).inflate(radius);
            for (LivingEntity e : levelValue.getEntitiesOfClass(LivingEntity.class, box)) {
                double d = Math.sqrt(e.blockPosition().distSqr(center));
                // The box is a cube; the requested radius is a sphere. Without this cut,
                // mobs farther than asked would be returned, and the number would stop
                // meaning what it says.
                if (d > radius) continue;
                cards.add(String.format(
                        "{\"type\":\"%s\",\"x\":%d,\"y\":%d,\"z\":%d,"
                        + "\"distance\":%.1f,\"hp\":%.1f,\"max\":%.1f}",
                        BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath(),
                        e.blockPosition().getX(), e.blockPosition().getY(),
                        e.blockPosition().getZ(), d, e.getHealth(), e.getMaxHealth()));
            }
            return String.format("{\"ok\":true,\"radius\":%d,\"creatures\":[%s]}",
                                 radius, String.join(",", cards));
        });
    }

    /**
     * The items LYING ON THE GROUND near a point.
     *
     * <p>Sibling of {@link #entities}, and it exists because of what that one lacks:
     * {@code getEntitiesOfClass(LivingEntity.class, ...)} leaves out {@code ItemEntity},
     * which are not living things. Without this the bot chopped a log, the log fell to
     * the ground, and **stopped existing for it**: it did not forget to pick it up,
     * nothing told it about it.
     *
     * <p>It returns the real id of what is inside the stack ({@code oak_log}), not the
     * entity type ({@code item}), which says nothing.
     *
     * <p>{@code seconds_on_ground} is not decoration: Minecraft **deletes items after 5
     * minutes**. Knowing how long they have been lying there is the difference between
     * going for them and walking towards something that is already gone.
     */
    private String objects(Map<String, String> q) throws Exception {
        BlockPos center = posOf(q);
        int radius = Math.min(RADIUS_MAX,
                Math.max(1, Integer.parseInt(q.getOrDefault("radius", "16"))));
        ServerLevel levelValue = world(q);
        return inGame(() -> {
            List<String> cards = new ArrayList<>();
            AABB box = new AABB(center).inflate(radius);
            for (ItemEntity e : levelValue.getEntitiesOfClass(ItemEntity.class, box)) {
                double d = Math.sqrt(e.blockPosition().distSqr(center));
                // The same sphere cut as for entities: the box is a cube and the
                // requested radius is not.
                if (d > radius) continue;
                ItemStack stack = e.getItem();
                if (stack.isEmpty()) continue;
                cards.add(String.format(
                        "{\"what\":\"%s\",\"count\":%d,\"x\":%d,\"y\":%d,"
                        + "\"z\":%d,\"distance\":%.1f,\"seconds_on_ground\":%d}",
                        BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath(),
                        stack.getCount(),
                        e.blockPosition().getX(), e.blockPosition().getY(),
                        e.blockPosition().getZ(), d, e.getAge() / 20));
            }
            return String.format(
                    "{\"ok\":true,\"radius\":%d,\"despawn_at\":300,"
                    + "\"objects\":[%s]}", radius, String.join(",", cards));
        });
    }

    /**
     * The inventory according to the SERVER.
     * It exists because the client's lags: right after crafting it does not yet reflect
     * what just happened. To know what the bot really carries, ask here.
     */
    private String inventory(Map<String, String> q) throws Exception {
        return inGame(() -> {
            var p = server.getPlayerList()
                    .getPlayerByName(q.getOrDefault("player", ""));
            if (p == null) {
                return "{\"ok\":false,\"error\":\"that player is not connected\"}";
            }
            java.util.Map<String, Integer> counts = new java.util.TreeMap<>();
            for (var stack : p.getInventory().items) {
                if (stack.isEmpty()) continue;
                counts.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath(),
                             stack.getCount(), Integer::sum);
            }
            List<String> rows = new ArrayList<>();
            counts.forEach((k, v) -> rows.add(
                    String.format("{\"what\":\"%s\",\"count\":%d}", k, v)));
            return "{\"ok\":true,\"things\":[" + String.join(",", rows) + "]}";
        });
    }

    /** What is needed to craft something. The game knows it, not a table. */
    private String recipe(Map<String, String> q) throws Exception {
        return inGame(() -> workshop.recipe(q));
    }

    /** Crafts for real: checks the rules, consumes and hands over. */
    private String craft(Map<String, String> q) throws Exception {
        return inGame(() -> workshop.craft(q));
    }

    private String block(Map<String, String> q) throws Exception {
        BlockPos pos = posOf(q);
        ServerLevel levelValue = world(q);
        return inGame(() -> {
            // Asking about an unloaded chunk would load it at once on the game thread.
            // Better to tell the truth: "I do not know yet".
            if (!levelValue.isLoaded(pos)) {
                return String.format(
                        "{\"ok\":false,\"error\":\"chunk not loaded\","
                        + "\"x\":%d,\"y\":%d,\"z\":%d}",
                        pos.getX(), pos.getY(), pos.getZ());
            }
            Block b = levelValue.getBlockState(pos).getBlock();
            return String.format(
                    "{\"ok\":true,\"block\":\"%s\",\"x\":%d,\"y\":%d,\"z\":%d}",
                    BuiltInRegistries.BLOCK.getKey(b).getPath(),
                    pos.getX(), pos.getY(), pos.getZ());
        });
    }

    /** The nearest block of a type. A sweep, not something per tick. */
    private String search(Map<String, String> q) throws Exception {
        String name = q.getOrDefault("block", "").toLowerCase()
                         .replace("minecraft:", "");
        ResourceLocation id = ResourceLocation.tryParse("minecraft:" + name);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
            // Loud on purpose: the model tends to say "log" instead of "oak_log", and
            // accepting that silently leaves the bot standing still.
            return String.format(
                    "{\"ok\":false,\"error\":\"I do not know the block '%s'; "
                    + "ids go in English, like oak_log or coal_ore\"}", name);
        }
        Block target = BuiltInRegistries.BLOCK.get(id);
        BlockPos center = posOf(q);
        int radius = Math.min(RADIUS_MAX,
                Math.max(1, Integer.parseInt(q.getOrDefault("radius", "16"))));
        ServerLevel levelValue = world(q);

        return inGame(() -> {
            BlockPos best = null;
            double bestDist = Double.MAX_VALUE;
            int looked = 0, notLoaded = 0;
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    for (int dy = -radius; dy <= radius; dy++) {
                        p.set(center.getX() + dx, center.getY() + dy,
                              center.getZ() + dz);
                        if (!levelValue.isLoaded(p)) { notLoaded++; continue; }
                        looked++;
                        if (levelValue.getBlockState(p).getBlock() != target) continue;
                        double d = center.distSqr(p);
                        if (d < bestDist) { bestDist = d; best = p.immutable(); }
                    }
                }
            }
            if (best == null) {
                return String.format(
                        "{\"ok\":true,\"found\":false,\"looked_at\":%d,"
                        + "\"unloaded\":%d}", looked, notLoaded);
            }
            return String.format(
                    "{\"ok\":true,\"found\":true,\"block\":\"%s\","
                    + "\"x\":%d,\"y\":%d,\"z\":%d,\"distance\":%.1f,"
                    + "\"looked_at\":%d,\"unloaded\":%d}",
                    name, best.getX(), best.getY(), best.getZ(),
                    Math.sqrt(bestDist), looked, notLoaded);
        });
    }

    // --- plumbing -------------------------------------------------------------

    private ServerLevel world(Map<String, String> q) {
        String d = q.getOrDefault("world", "overworld");
        for (ServerLevel n : server.getAllLevels()) {
            if (n.dimension().location().getPath().equals(d)) return n;
        }
        return server.overworld();
    }

    private static BlockPos posOf(Map<String, String> q) {
        return new BlockPos(Request.whole(q, "x"),
                            Request.whole(q, "y"),
                            Request.whole(q, "z"));
    }

    private interface RouteHandler {
        String respond(Map<String, String> query) throws Exception;
    }

    private void attend(HttpExchange x, RouteHandler m) throws IOException {
        if (!token.isEmpty()
                && !token.equals(x.getRequestHeaders().getFirst("X-Marionette-Token"))) {
            respond(x, 401, "{\"ok\":false,\"error\":\"token\"}");
            return;
        }
        try {
            respond(x, 200, m.respond(query(x.getRequestURI())));
        } catch (IllegalArgumentException e) {
            respond(x, 400, "{\"ok\":false,\"error\":\"" + escape(e.getMessage()) + "\"}");
        } catch (Exception e) {
            // Make it visible. A silent failure here leaves the agent believing things.
            LOG.error("[marionette] error handling {}", x.getRequestURI(), e);
            respond(x, 500, "{\"ok\":false,\"error\":\"" + escape(String.valueOf(e)) + "\"}");
        }
    }

    private static Map<String, String> query(URI uri) {
        return Request.query(uri.getRawQuery());
    }

    private static String escape(String s) {
        return Request.escape(s);
    }

    private Properties loadConfig() {
        Properties p = new Properties();
        try {
            if (Files.exists(CONFIG)) {
                try (var in = Files.newInputStream(CONFIG)) { p.load(in); }
            } else {
                Files.writeString(CONFIG, """
                        # Marionette server mod: the source of truth for the bots.
                        # host 127.0.0.1 = this machine only. For the agent to reach
                        # it from elsewhere, put a private-network IP here AND a
                        # token, or anyone who reaches that address can command it.
                        host=127.0.0.1
                        port=8477
                        token=
                        # Players that are bots, comma separated: what they toss on
                        # the ground is not picked up again by themselves.
                        bots=
                        """);
                LOG.info("[marionette] wrote {} with defaults", CONFIG.toAbsolutePath());
            }
        } catch (IOException e) {
            LOG.error("[marionette] could not read {}, using defaults", CONFIG, e);
        }
        return p;
    }

    private static void respond(HttpExchange x, int code, String json)
            throws IOException {
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().set("Content-Type", "application/json");
        x.sendResponseHeaders(code, payload.length);
        try (OutputStream out = x.getResponseBody()) { out.write(payload); }
    }
}
