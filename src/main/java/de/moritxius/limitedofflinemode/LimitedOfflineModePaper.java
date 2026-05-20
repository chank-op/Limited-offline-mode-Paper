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
                Class<?> holder = Class.forName("io.papermc.paper.network.ChannelInitializeListenerHolder");
                holder.getMethod("removeListener", NamespacedKey.class)
                      .invoke(null, new NamespacedKey(this, LISTENER_KEY));
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
        if (isPaperServer()) {
            try {
                setupPaperInjection();
                usePaperApi = true;
                getLogger().info("[LOM] Using Paper channel injection API.");
            } catch (Exception e) {
                getLogger().warning("[LOM] Paper injection failed, falling back to Spigot: " + e.getMessage());
            }
        }
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

    private boolean isPaperServer() {
        try {
            Class.forName("io.papermc.paper.network.ChannelInitializeListenerHolder");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private void setupPaperInjection() throws Exception {
        Class<?> holder = Class.forName("io.papermc.paper.network.ChannelInitializeListenerHolder");
        NamespacedKey key = new NamespacedKey(this, LISTENER_KEY);
        java.util.function.Consumer<Channel> listener =
                channel -> channel.pipeline().addBefore("packet_handler", HANDLER_KEY, new LoginInterceptor(this));
        holder.getMethod("addListener", NamespacedKey.class, java.util.function.Consumer.class)
              .invoke(null, key, listener);
    }

    private void setupSpigotInjection() throws Exception {
        Object craftServer    = Bukkit.getServer();
        Object minecraftServer = craftServer.getClass().getMethod("getServer").invoke(craftServer);

        Object serverConnection = findFieldByTypeName(minecraftServer, "ServerConnection");
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

    private static Object findFieldByTypeName(Object obj, String simpleTypeName) {
        Class<?> clazz = obj.getClass();
        while (clazz != null) {
            for (Field f : clazz.getDeclaredFields()) {
                if (f.getType().getSimpleName().equals(simpleTypeName)) {
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
