package de.moritxius.limitedofflinemode;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import org.bstats.bukkit.Metrics;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

public class LimitedOfflineModePaper extends JavaPlugin {

    private static final int BSTATS_PLUGIN_ID = 29813;
    private static final String LISTENER_KEY   = "login_interceptor";
    private static final String HANDLER_KEY    = "lom_login_interceptor";
    private static final String SERVER_HANDLER_KEY = "lom_server_init";

    private final Set<String>         allowedUsers         = new HashSet<>();
    private final Map<String, Set<String>> playerGroups    = new HashMap<>();
    private final Set<String>         enabledGroups        = new HashSet<>();
    private final List<Channel>       injectedServerChannels = new ArrayList<>();

    private boolean usePaperApi = false;

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onEnable() {
        printBanner();
        loadAllowedUsers();
        loadPlayerGroups();
        setupChannelInjection();
        new Metrics(this, BSTATS_PLUGIN_ID);
        getLogger().info("LimitedOfflineMode enabled — "
                + allowedUsers.size() + " allowed users, "
                + playerGroups.size() + " groups.");
    }

    @Override
    public void onDisable() {
        if (usePaperApi) {
            try {
                Class<?> holder  = Class.forName("io.papermc.paper.network.ChannelInitializeListenerHolder");
                Class<?> keyIf   = Class.forName("net.kyori.adventure.key.Key");
                // Use getDeclaredMethod so Folia path is also cleaned up correctly
                java.lang.reflect.Method remover = holder.getDeclaredMethod("removeListener", keyIf);
                remover.setAccessible(true);
                remover.invoke(null, new NamespacedKey(this, LISTENER_KEY));
            } catch (Exception e) {
                getLogger().warning("[LOM] Failed to remove Paper channel listener: " + e.getMessage());
            }
        } else {
            for (Channel ch : injectedServerChannels) {
                if (ch.pipeline().get(SERVER_HANDLER_KEY) != null) {
                    ch.pipeline().remove(SERVER_HANDLER_KEY);
                }
            }
            injectedServerChannels.clear();
        }
    }

    // ── Channel injection ─────────────────────────────────────────────────────

    private void setupChannelInjection() {
        // ── Folia — use getDeclaredMethod (finds package‑private methods too) ──
        if (isFoliaServer()) {
            try {
                setupPaperInjection(true);
                getLogger().info("[LOM] Using Folia channel injection.");
                return;
            } catch (Exception e) {
                getLogger().warning("[LOM] Folia injection failed, falling back: " + e.getMessage());
            }
        }

        // ── Paper — public ChannelInitializeListenerHolder API ──
        if (isPaperServer()) {
            try {
                setupPaperInjection(false);
                usePaperApi = true;
                getLogger().info("[LOM] Using Paper channel injection API.");
                return;
            } catch (Exception e) {
                getLogger().warning("[LOM] Paper injection failed, falling back to Spigot: " + e.getMessage());
            }
        }

        // ── Spigot / CraftBukkit — reflection-based ──
        if (!usePaperApi) {
            try {
                setupSpigotInjection();
                getLogger().info("[LOM] Using reflection-based channel injection (Spigot/CraftBukkit).");
            } catch (Exception e) {
                getLogger().severe("[LOM] Failed to inject channel handler: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    /** Detects Folia — the regionised multithreading fork of Paper. */
    public static boolean isFoliaServer() {
        try {
            Class.forName("io.papermc.paper.threadedregions.TickRegionScheduler");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Safely schedules a task on the "main" or global region thread.
     * <p>On Folia {@code Bukkit.getScheduler().runTask()} throws
     * {@code UnsupportedOperationException}, so we use
     * {@code Bukkit.getGlobalRegionScheduler().run()} instead.
     * On Spigot/CraftBukkit we fall back to the legacy scheduler.</p>
     */
    public static void scheduleTask(JavaPlugin plugin, Runnable task) {
        if (isFoliaServer()) {
            try {
                // GlobalRegionScheduler#run(Plugin, Consumer<ScheduledTask>)
                Object globalScheduler = Bukkit.class.getMethod("getGlobalRegionScheduler").invoke(null);
                globalScheduler.getClass().getMethod("run",
                                org.bukkit.plugin.Plugin.class,
                                java.util.function.Consumer.class)
                        .invoke(globalScheduler, plugin,
                                (java.util.function.Consumer<Object>) scheduledTask -> task.run());
                return;
            } catch (Exception ignored) {
                // fall through to BukkitScheduler
            }
        }
        plugin.getServer().getScheduler().runTask(plugin, task);
    }

    /**
     * Schedules a task on the player's owning region thread (Folia) or runs it
     * directly on the current thread (non-Folia, assumed to be main/server thread).
     * <p>This is the correct way to interact with a Player on Folia — using
     * {@link #scheduleTask(JavaPlugin, Runnable)} (which uses GlobalRegionScheduler)
     * for per-player operations violates thread-context rules.</p>
     */
    public static void scheduleOnEntity(JavaPlugin plugin, Player player, Runnable task) {
        if (isFoliaServer()) {
            try {
                // Player#getScheduler() returns EntityScheduler
                // EntityScheduler#run(Plugin, Runnable)
                Object entityScheduler = Player.class.getMethod("getScheduler").invoke(player);
                entityScheduler.getClass().getMethod("run", org.bukkit.plugin.Plugin.class, Runnable.class)
                        .invoke(entityScheduler, plugin, task);
                return;
            } catch (Exception ignored) {
                // fall through — run directly as best-effort
            }
        }
        task.run();
    }

    private boolean isPaperServer() {
        try {
            Class.forName("io.papermc.paper.network.ChannelInitializeListenerHolder");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Registers our channel listener via {@code ChannelInitializeListenerHolder}.
     *
     * @param useDeclaredMethod if {@code true} uses {@code getDeclaredMethod}+{@code setAccessible}
     *                          (needed on Folia where the method may be package‑private);
     *                          otherwise uses the standard public-only {@code getMethod}.
     */
    private void setupPaperInjection(boolean useDeclaredMethod) throws Exception {
        Class<?> holder     = Class.forName("io.papermc.paper.network.ChannelInitializeListenerHolder");
        Class<?> listenerIf = Class.forName("io.papermc.paper.network.ChannelInitializeListener");
        // The addListener method signature uses net.kyori.adventure.key.Key, NOT NamespacedKey!
        Class<?> keyIf      = Class.forName("net.kyori.adventure.key.Key");
        NamespacedKey key   = new NamespacedKey(this, LISTENER_KEY);

        Object listener = java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{listenerIf},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return method.invoke(this, args);
                    }
                    Channel ch = (Channel) args[0];
                    ch.pipeline().addBefore("packet_handler", HANDLER_KEY, new LoginInterceptor(this));
                    return null;
                });

        java.lang.reflect.Method addListener;
        if (useDeclaredMethod) {
            // Folia path — the method may be non‑public
            addListener = holder.getDeclaredMethod("addListener", keyIf, listenerIf);
            addListener.setAccessible(true);
        } else {
            addListener = holder.getMethod("addListener", keyIf, listenerIf);
        }
        addListener.invoke(null, key, listener);
    }

    private void setupSpigotInjection() throws Exception {
        Object craftServer    = Bukkit.getServer();
        Object minecraftServer = craftServer.getClass().getMethod("getServer").invoke(craftServer);

        Object serverConnection = findFieldFlexible(minecraftServer, "ServerConnection",
                "serverConnection", "connection", "listeningChannels");
        if (serverConnection == null) {
            throw new IllegalStateException("Cannot find ServerConnection on MinecraftServer");
        }

        List<ChannelFuture> futures = findChannelFutures(serverConnection);
        if (futures == null || futures.isEmpty()) {
            throw new IllegalStateException("Cannot find ChannelFuture list in ServerConnection");
        }

        LimitedOfflineModePaper plugin = this;
        ChannelInboundHandlerAdapter serverHandler = new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                if (msg instanceof Channel playerChannel) {
                    playerChannel.pipeline().addLast(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) {
                            // Defer until after Minecraft's own ChannelInitializer sets up packet_handler
                            ch.eventLoop().execute(() -> {
                                if (ch.isActive()
                                        && ch.pipeline().get("packet_handler") != null
                                        && ch.pipeline().get(HANDLER_KEY) == null) {
                                    ch.pipeline().addBefore("packet_handler", HANDLER_KEY, new LoginInterceptor(plugin));
                                }
                            });
                        }
                    });
                }
                super.channelRead(ctx, msg);
            }
        };

        for (ChannelFuture future : futures) {
            Channel serverChannel = future.channel();
            serverChannel.pipeline().addFirst(SERVER_HANDLER_KEY, serverHandler);
            injectedServerChannels.add(serverChannel);
        }
    }

    // ── Reflection helpers for Spigot injection ───────────────────────────────

    /** Tries multiple strategies to locate a field on {@code obj}.
     *  <ol>
     *   <li>By type simple name (e.g. {@code "ServerConnection"})
     *   <li>By exact field name ({@code fieldNames} in priority order)
     *   <li>Scan all fields for one whose type simple name contains {@code typeName}
     *  </ol> */
    private static Object findFieldFlexible(Object obj, String typeName, String... fieldNames) {
        // 1) Exact type match (original behaviour)
        Class<?> clazz = obj.getClass();
        while (clazz != null) {
            for (Field f : clazz.getDeclaredFields()) {
                if (f.getType().getSimpleName().equals(typeName)) {
                    f.setAccessible(true);
                    try { return f.get(obj); } catch (Exception ignored) {}
                }
            }
            clazz = clazz.getSuperclass();
        }

        // 2) By field name
        clazz = obj.getClass();
        while (clazz != null) {
            for (String name : fieldNames) {
                try {
                    Field f = clazz.getDeclaredField(name);
                    f.setAccessible(true);
                    return f.get(obj);
                } catch (NoSuchFieldException | IllegalAccessException ignored) {}
            }
            clazz = clazz.getSuperclass();
        }

        // 3) Fuzzy type name (contains)
        clazz = obj.getClass();
        while (clazz != null) {
            for (Field f : clazz.getDeclaredFields()) {
                if (f.getType().getSimpleName().contains(typeName)) {
                    f.setAccessible(true);
                    try { return f.get(obj); } catch (Exception ignored) {}
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<ChannelFuture> findChannelFutures(Object serverConnection) throws Exception {
        Class<?> clazz = serverConnection.getClass();
        while (clazz != null) {
            for (Field f : clazz.getDeclaredFields()) {
                if (List.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    List<?> list = (List<?>) f.get(serverConnection);
                    if (list != null && !list.isEmpty() && list.get(0) instanceof ChannelFuture) {
                        return (List<ChannelFuture>) list;
                    }
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    // ── Auth check (called by LoginInterceptor) ───────────────────────────────

    public boolean isUserAllowed(String username) {
        String norm = normalize(username);
        if (allowedUsers.contains(norm)) return true;
        for (String group : enabledGroups) {
            Set<String> members = playerGroups.get(group);
            if (members != null && members.contains(norm)) return true;
        }
        return false;
    }

    // ── Config loading/saving ─────────────────────────────────────────────────

    private void loadAllowedUsers() {
        try {
            allowedUsers.clear();
            Path path = getDataFolder().toPath().resolve("allowed-users.txt");
            if (Files.exists(path)) {
                Files.readAllLines(path, StandardCharsets.UTF_8).forEach(line -> {
                    String t = line.trim();
                    if (!t.isEmpty() && !t.startsWith("#")) allowedUsers.add(t.toLowerCase(Locale.ROOT));
                });
                getLogger().info("Loaded " + allowedUsers.size() + " allowed users.");
            } else {
                Files.createDirectories(getDataFolder().toPath());
                Files.write(path, "# Add usernames (one per line) that may join in offline mode\n"
                        .getBytes(StandardCharsets.UTF_8));
                getLogger().info("Created default allowed-users.txt.");
            }
        } catch (IOException e) {
            getLogger().severe("Failed to load allowed-users.txt: " + e.getMessage());
        }
    }

    private void loadPlayerGroups() {
        try {
            playerGroups.clear();
            enabledGroups.clear();
            Path path = getDataFolder().toPath().resolve("player-groups.txt");
            if (!Files.exists(path)) {
                Files.createDirectories(getDataFolder().toPath());
                Files.write(path, List.of(
                        "# Format: groupName|enabled|player1,player2",
                        "# Example:",
                        "# admins|true|Steve,Alex"
                ), StandardCharsets.UTF_8);
                getLogger().info("Created default player-groups.txt.");
                return;
            }
            Files.readAllLines(path, StandardCharsets.UTF_8).forEach(line -> {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("#")) return;
                String[] parts = t.split("\\|", -1);
                if (parts.length < 3) { getLogger().warning("Skipping invalid group line: " + t); return; }
                String name = normalize(parts[0]);
                if (name.isEmpty()) return;
                boolean enabled = Boolean.parseBoolean(parts[1].trim());
                Set<String> members = Arrays.stream(parts[2].split(","))
                        .map(this::normalize).filter(s -> !s.isEmpty())
                        .collect(Collectors.toCollection(HashSet::new));
                playerGroups.put(name, members);
                if (enabled) enabledGroups.add(name);
            });
            getLogger().info("Loaded " + playerGroups.size() + " groups (" + enabledGroups.size() + " enabled).");
        } catch (IOException e) {
            getLogger().severe("Failed to load player-groups.txt: " + e.getMessage());
        }
    }

    private void savePlayerGroups() {
        Path path = getDataFolder().toPath().resolve("player-groups.txt");
        List<String> lines = playerGroups.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "|" + enabledGroups.contains(e.getKey()) + "|"
                        + e.getValue().stream().sorted().collect(Collectors.joining(",")))
                .collect(Collectors.toList());
        lines.add(0, "# Format: groupName|enabled|player1,player2");
        try {
            Files.createDirectories(getDataFolder().toPath());
            Files.write(path, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            getLogger().severe("Failed to save player-groups.txt: " + e.getMessage());
        }
    }

    // ── /lomgroup command ─────────────────────────────────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("lomgroup")) return false;

        if (!sender.hasPermission("limitedofflinemode.admin")) {
            sender.sendMessage("No permission.");
            return true;
        }

        if (args.length < 2 || !"group".equalsIgnoreCase(args[0])) {
            sendHelp(sender);
            return true;
        }

        String action    = args[1].toLowerCase(Locale.ROOT);
        String groupName = args.length > 2 ? normalize(args[2]) : "";

        switch (action) {
            case "add" -> {
                if (args.length < 4) { sender.sendMessage("Usage: /lomgroup group add <group> <player1,player2,...>"); return true; }
                Set<String> players = Arrays.stream(args[3].split(","))
                        .map(this::normalize).filter(s -> !s.isEmpty())
                        .collect(Collectors.toCollection(HashSet::new));
                if (groupName.isEmpty() || players.isEmpty()) { sender.sendMessage("Invalid group or player list."); return true; }
                playerGroups.computeIfAbsent(groupName, k -> new HashSet<>()).addAll(players);
                savePlayerGroups();
                sender.sendMessage("Group '" + groupName + "' updated with " + players.size() + " player(s).");
            }
            case "enable", "disable", "toggle" -> {
                if (groupName.isEmpty() || !playerGroups.containsKey(groupName)) {
                    sender.sendMessage("Unknown group: " + groupName); return true;
                }
                boolean enable = "enable".equals(action)
                        || ("toggle".equals(action) && !enabledGroups.contains(groupName));
                if (enable) enabledGroups.add(groupName); else enabledGroups.remove(groupName);
                savePlayerGroups();
                sender.sendMessage("Group '" + groupName + "' " + (enable ? "enabled" : "disabled") + ".");
            }
            case "list" -> {
                sender.sendMessage("Groups:");
                if (playerGroups.isEmpty()) { sender.sendMessage("  (none)"); return true; }
                playerGroups.forEach((name, members) ->
                        sender.sendMessage("  " + name + " [" + (enabledGroups.contains(name) ? "ON" : "OFF") + "] "
                                + members.stream().sorted().collect(Collectors.joining(", "))));
            }
            case "reload" -> {
                loadAllowedUsers();
                loadPlayerGroups();
                sender.sendMessage("Config reloaded.");
            }
            default -> sendHelp(sender);
        }
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("/lomgroup group add <group> <player1,player2,...>");
        sender.sendMessage("/lomgroup group enable|disable|toggle <group>");
        sender.sendMessage("/lomgroup group list");
        sender.sendMessage("/lomgroup group reload");
    }

    // ── Util ──────────────────────────────────────────────────────────────────

    private String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }

    private void printBanner() {
        String cyan  = ChatColor.AQUA.toString();
        String green = ChatColor.GREEN.toString();
        String gold  = ChatColor.GOLD.toString();
        String bold  = ChatColor.BOLD.toString();

        InputStream is = getResource("ascii-art.txt");
        if (is != null) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Bukkit.getConsoleSender().sendMessage(cyan + line);
                }
            } catch (IOException ignored) {}
        }

        Bukkit.getConsoleSender().sendMessage(
                green + bold + "  Plugin by chank_op" +
                ChatColor.RESET + "  |  " +
                gold + "https://github.com/chank-op");
        Bukkit.getConsoleSender().sendMessage("");
    }
}
