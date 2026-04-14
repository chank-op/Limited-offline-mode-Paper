package de.moritxius.limitedofflinemode;

import io.papermc.paper.network.ChannelInitializeListenerHolder;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

@SuppressWarnings("UnstableApiUsage") // ChannelInitializeListenerHolder is @Experimental but stable in practice
public class LimitedOfflineModePaper extends JavaPlugin {

    private static final int BSTATS_PLUGIN_ID = 29813;
    private static final String LISTENER_KEY = "login_interceptor";

    private final Set<String> allowedUsers = new HashSet<>();
    private final Map<String, Set<String>> playerGroups = new HashMap<>();
    private final Set<String> enabledGroups = new HashSet<>();

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onEnable() {
        printBanner();
        loadAllowedUsers();
        loadPlayerGroups();

        // Inject our handler into every new player connection pipeline.
        // ChannelInitializeListenerHolder is a stable Paper API available since 1.18.
        ChannelInitializeListenerHolder.addListener(
                new NamespacedKey(this, LISTENER_KEY),
                channel -> channel.pipeline().addBefore(
                        "packet_handler",
                        "lom_login_interceptor",
                        new LoginInterceptor(this))
        );

        new Metrics(this, BSTATS_PLUGIN_ID);

        getLogger().info("LimitedOfflineMode enabled — "
                + allowedUsers.size() + " allowed users, "
                + playerGroups.size() + " groups.");
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

    @Override
    public void onDisable() {
        ChannelInitializeListenerHolder.removeListener(new NamespacedKey(this, LISTENER_KEY));
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

        String action = args[1].toLowerCase(Locale.ROOT);
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
}
