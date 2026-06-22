package de.moritxius.limitedofflinemode;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerChatEvent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Per-connection Netty handler injected by LimitedOfflineModePaper via
 * Paper's ChannelInitializeListenerHolder.
 *
 * <p>This class has been optimized to:</p>
 * <ul>
 *   <li>Cache reflection Method/Field objects statically (avoid repeated class hierarchy scans)</li>
 *   <li>Use cached simple class name strings for fast packet type matching</li>
 *   <li>Minimize object allocation in the hot chat path</li>
 * </ul>
 */
public class LoginInterceptor extends ChannelDuplexHandler {

    private static final String SIMPLE_NAME_HELLO      = "ServerboundHelloPacket";
    private static final String SIMPLE_NAME_LOGIN_START = "LoginStart";
    private static final String SIMPLE_NAME_CHAT        = "ServerboundChatPacket";
    private static final String SIMPLE_NAME_CHAT_COMMAND = "ServerboundChatCommandPacket";
    private static final String SIMPLE_NAME_ENCRYPT_REQ = "ClientboundHelloPacket";
    private static final String SIMPLE_NAME_ENCRYPT_REQ_ALT = "EncryptionRequest";

    // ── Cached reflection objects ──────────────────────────────────────────

    /** Cached method for extracting username from ServerboundHelloPacket. */
    private static Field  cachedUsernameField  = null;
    private static Method cachedUsernameMethod = null;
    private static volatile boolean usernameReflectInit = false;

    /** Cached method/field for extracting chat message from ServerboundChatPacket. */
    private static Method cachedChatMethod = null;
    private static Field  cachedChatField  = null;
    private static volatile boolean chatReflectInit = false;

    /** Cached field for Connection.packetListener. */
    private static Field cachedPacketListenerField = null;
    private static volatile boolean packetListenerReflectInit = false;

    /** Cached method for createOfflineProfile. */
    private static Method cachedCreateOfflineProfileMethod = null;

    /** Cached method for verifyLoginAndFinishConnectionSetup. */
    private static Method cachedVerifyLoginMethod = null;

    /** Cached field for authenticatedProfile. */
    private static Field cachedAuthProfileField = null;

    // ── Instance state ─────────────────────────────────────────────────────

    private final LimitedOfflineModePaper plugin;
    private String pendingUsername  = null;
    private String offlineUsername  = null;  // set once offline login succeeds
    private boolean isOfflinePlayer = false;

    LoginInterceptor(LimitedOfflineModePaper plugin) {
        this.plugin = plugin;
    }

    // ── Inbound: CLIENT → SERVER ─────────────────────────────────────────────

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        // ── Login phase: detect whitelisted player ───────────────────────────
        if (pendingUsername == null && !isOfflinePlayer) {
            String cls = msg.getClass().getSimpleName();
            if (cls.equals(SIMPLE_NAME_HELLO) || cls.equals(SIMPLE_NAME_LOGIN_START)) {
                String username = extractUsername(msg);
                if (username != null && plugin.isUserAllowed(username)) {
                    pendingUsername = username;
                    plugin.getLogger().info("[LOM] Whitelisted offline player connecting: " + username);
                }
            }
        }

        // ── Play phase: intercept chat before Paper checks profile key ────────
        if (isOfflinePlayer && offlineUsername != null) {
            String cls = msg.getClass().getSimpleName();
            // Match ServerboundChatPacket but NOT ServerboundChatCommandPacket
            if (cls.equals(SIMPLE_NAME_CHAT) && !cls.equals(SIMPLE_NAME_CHAT_COMMAND)) {
                String message = extractChatMessage(msg);
                if (message != null && !message.isBlank()) {
                    String username = offlineUsername;
                    // Obtain the player reference on a safe thread, then delegate the
                    // player-affecting work to the player's own region thread (Folia) or
                    // execute directly on the current thread (non-Folia).
                    LimitedOfflineModePaper.scheduleTask(plugin, () -> {
                        Player player = plugin.getServer().getPlayer(username);
                        if (player != null) {
                            LimitedOfflineModePaper.scheduleOnEntity(plugin, player,
                                    () -> firePlayerChat(player, message));
                        }
                    });
                    return; // swallow — never reaches Paper's handleChat()
                }
            }
        }

        super.channelRead(ctx, msg);
    }

    // ── Outbound: SERVER → CLIENT ────────────────────────────────────────────

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (pendingUsername != null) {
            String cls = msg.getClass().getSimpleName();
            if (cls.equals(SIMPLE_NAME_ENCRYPT_REQ) || cls.equals(SIMPLE_NAME_ENCRYPT_REQ_ALT)) {
                String username = pendingUsername;
                pendingUsername  = null;
                offlineUsername  = username;

                // Swallow EncryptionRequest — client must never receive it
                promise.setSuccess();

                // Must run on the global region thread — verifyLoginAndFinishConnectionSetup
                // fires Bukkit events (ProfileWhitelistVerifyEvent, AsyncPlayerPreLoginEvent)
                // which require a proper thread context.
                LimitedOfflineModePaper.scheduleTask(plugin,
                        () -> injectOfflineLogin(ctx.channel(), username));
                return;
            }
        }
        super.write(ctx, msg, promise);
    }

    // ── Core: inject offline login via reflection ────────────────────────────

    private void injectOfflineLogin(Channel channel, String username) {
        try {
            Object loginListener = resolveLoginListener(channel);
            if (loginListener == null) {
                plugin.getLogger().severe("[LOM] Could not find ServerLoginPacketListenerImpl for "
                        + username + " — player will be kicked. Check Paper version.");
                return;
            }

            // Build the offline GameProfile.
            Object offlineProfile = createOfflineProfile(loginListener, username);

            // Also set authenticatedProfile so any code reading that field sees our profile
            setAuthenticatedProfile(loginListener, offlineProfile);

            // Call verifyLoginAndFinishConnectionSetup — this is what Paper calls after
            // successful Mojang auth.  It fires login events, sets up compression, and
            // sends ClientboundLoginFinishedPacket (LoginSuccess).
            invokeVerifyLogin(loginListener, offlineProfile);

            isOfflinePlayer = true;
            plugin.getLogger().info("[LOM] Offline login injected for " + username
                    + " (UUID: " + getProfileUUID(offlineProfile) + ")");

        } catch (Exception e) {
            plugin.getLogger().severe("[LOM] Failed to inject offline login for "
                    + username + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ── Reflection helpers (with cached lookups) ───────────────────────────

    /**
     * packet_handler in the Netty pipeline is net.minecraft.network.Connection.
     * Connection.packetListener holds ServerLoginPacketListenerImpl during login.
     */
    private static Object resolveLoginListener(Channel channel) throws Exception {
        Object connection = channel.pipeline().get("packet_handler");
        if (connection == null) return null;

        // Try cached field first
        if (packetListenerField() != null) {
            Object listener = cachedPacketListenerField.get(connection);
            if (listener != null && listener.getClass().getSimpleName().contains("Login")) {
                return listener;
            }
        }

        // Fallback: scan all fields for a ServerLoginPacketListenerImpl
        Class<?> clazz = connection.getClass();
        while (clazz != null) {
            for (Field f : clazz.getDeclaredFields()) {
                f.setAccessible(true);
                Object val = f.get(connection);
                if (val != null && val.getClass().getSimpleName().contains("ServerLoginPacketListenerImpl")) {
                    cachedPacketListenerField = f; // cache for next time
                    return val;
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    private static Field packetListenerField() {
        if (!packetListenerReflectInit) {
            synchronized (LoginInterceptor.class) {
                if (!packetListenerReflectInit) {
                    try {
                        Class<?> connectionClass = Class.forName("net.minecraft.network.Connection");
                        cachedPacketListenerField = connectionClass.getDeclaredField("packetListener");
                        cachedPacketListenerField.setAccessible(true);
                    } catch (Exception ignored) {
                        // Will fall back to scanning
                    }
                    packetListenerReflectInit = true;
                }
            }
        }
        return cachedPacketListenerField;
    }

    /**
     * Uses Paper's own createOfflineProfile(String) method so the UUID is
     * generated the same way Paper generates it everywhere else.
     */
    private static Object createOfflineProfile(Object loginListener, String username) throws Exception {
        if (cachedCreateOfflineProfileMethod == null) {
            try {
                cachedCreateOfflineProfileMethod = loginListener.getClass()
                        .getDeclaredMethod("createOfflineProfile", String.class);
                cachedCreateOfflineProfileMethod.setAccessible(true);
            } catch (NoSuchMethodException ignored) {
                // Will use fallback below
            }
        }
        if (cachedCreateOfflineProfileMethod != null) {
            return cachedCreateOfflineProfileMethod.invoke(loginListener, username);
        }
        // Manual fallback
        UUID uuid = UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
        Class<?> profileClass = Class.forName("com.mojang.authlib.GameProfile");
        return profileClass.getConstructor(UUID.class, String.class).newInstance(uuid, username);
    }

    private static void setAuthenticatedProfile(Object loginListener, Object profile) throws Exception {
        if (cachedAuthProfileField == null) {
            cachedAuthProfileField = findField(loginListener.getClass(), "authenticatedProfile");
            if (cachedAuthProfileField != null) {
                cachedAuthProfileField.setAccessible(true);
            }
        }
        if (cachedAuthProfileField != null) {
            cachedAuthProfileField.set(loginListener, profile);
        }
    }

    private static void invokeVerifyLogin(Object loginListener, Object offlineProfile) throws Exception {
        if (cachedVerifyLoginMethod == null) {
            cachedVerifyLoginMethod = findMethod(loginListener.getClass(),
                    "verifyLoginAndFinishConnectionSetup",
                    Class.forName("com.mojang.authlib.GameProfile"));
            if (cachedVerifyLoginMethod != null) {
                cachedVerifyLoginMethod.setAccessible(true);
            }
        }
        if (cachedVerifyLoginMethod != null) {
            cachedVerifyLoginMethod.invoke(loginListener, offlineProfile);
        }
    }

    /** Searches up the class hierarchy for a field by name. */
    private static Field findField(Class<?> clazz, String name) {
        while (clazz != null) {
            try {
                Field f = clazz.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {}
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    /** Searches up the class hierarchy for a method by name and parameter types. */
    private static Method findMethod(Class<?> clazz, String name, Class<?>... paramTypes) {
        while (clazz != null) {
            try {
                Method m = clazz.getDeclaredMethod(name, paramTypes);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {}
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    /** Fires AsyncPlayerChatEvent for an offline player and broadcasts if not cancelled. */
    @SuppressWarnings("deprecation")
    private void firePlayerChat(Player player, String message) {
        // Reuse a pre-sized HashSet to reduce allocation overhead
        Collection<? extends Player> onlinePlayers = plugin.getServer().getOnlinePlayers();
        Set<Player> recipients;
        if (onlinePlayers instanceof Set) {
            recipients = (Set<Player>) onlinePlayers;
        } else {
            recipients = new HashSet<>(onlinePlayers);
        }
        AsyncPlayerChatEvent event = new AsyncPlayerChatEvent(false, player, message, recipients);
        plugin.getServer().getPluginManager().callEvent(event);
        if (!event.isCancelled()) {
            String formatted = String.format(event.getFormat(),
                    player.getDisplayName(), event.getMessage());
            for (Player recipient : event.getRecipients()) {
                recipient.sendMessage(formatted);
            }
            plugin.getServer().getConsoleSender().sendMessage(formatted);
        }
    }

    /** Ensures the chat-message reflection lookup is performed exactly once. */
    private static void ensureChatReflectInit(Object packet) {
        if (!chatReflectInit) {
            synchronized (LoginInterceptor.class) {
                if (!chatReflectInit) {
                    // Try accessor method first
                    for (String name : new String[]{"getMessage", "message"}) {
                        try {
                            cachedChatMethod = packet.getClass().getMethod(name);
                            break;
                        } catch (NoSuchMethodException ignored) {}
                    }
                    if (cachedChatMethod == null) {
                        // Fallback: find the first String field
                        Class<?> clazz = packet.getClass();
                        while (clazz != null && cachedChatField == null) {
                            for (Field f : clazz.getDeclaredFields()) {
                                if (f.getType() == String.class) {
                                    f.setAccessible(true);
                                    cachedChatField = f;
                                    break;
                                }
                            }
                            clazz = clazz.getSuperclass();
                        }
                    }
                    chatReflectInit = true;
                }
            }
        }
    }

    /** Extracts the chat message string from a ServerboundChatPacket via reflection. */
    private static String extractChatMessage(Object packet) {
        ensureChatReflectInit(packet);

        if (cachedChatMethod != null) {
            try {
                Object val = cachedChatMethod.invoke(packet);
                if (val instanceof String s && !s.isBlank()) return s;
            } catch (Exception ignored) {}
        }
        if (cachedChatField != null) {
            try {
                Object val = cachedChatField.get(packet);
                if (val instanceof String s && !s.isBlank()) return s;
            } catch (Exception ignored) {}
        }
        return null;
    }

    /** Ensures the username reflection lookup is performed exactly once. */
    private static void ensureUsernameReflectInit(Object packet) {
        if (!usernameReflectInit) {
            synchronized (LoginInterceptor.class) {
                if (!usernameReflectInit) {
                    // Try accessor methods first
                    for (String name : new String[]{"name", "getName", "getPlayerName", "getUsername"}) {
                        try {
                            cachedUsernameMethod = packet.getClass().getMethod(name);
                            cachedUsernameMethod.setAccessible(true);
                            break;
                        } catch (NoSuchMethodException ignored) {}
                    }
                    if (cachedUsernameMethod == null) {
                        // Fallback: find the first non-empty String field
                        Class<?> clazz = packet.getClass();
                        while (clazz != null && cachedUsernameField == null) {
                            for (Field f : clazz.getDeclaredFields()) {
                                if (f.getType() == String.class) {
                                    f.setAccessible(true);
                                    try {
                                        Object val = f.get(packet);
                                        if (val instanceof String s && !s.isBlank()) {
                                            cachedUsernameField = f;
                                            break;
                                        }
                                    } catch (Exception ignored) {}
                                }
                            }
                            clazz = clazz.getSuperclass();
                        }
                    }
                    usernameReflectInit = true;
                }
            }
        }
    }

    /** Extracts the username from a ServerboundHelloPacket via method or field. */
    private static String extractUsername(Object packet) {
        ensureUsernameReflectInit(packet);

        if (cachedUsernameMethod != null) {
            try {
                Object val = cachedUsernameMethod.invoke(packet);
                if (val instanceof String s && !s.isBlank()) return s;
            } catch (Exception ignored) {}
        }
        if (cachedUsernameField != null) {
            try {
                Object val = cachedUsernameField.get(packet);
                if (val instanceof String s && !s.isBlank()) return s;
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static UUID getProfileUUID(Object profile) {
        // Try getId() method first
        try {
            return (UUID) profile.getClass().getMethod("getId").invoke(profile);
        } catch (Exception ignored) {}
        // Try direct field access
        try {
            Field f = profile.getClass().getDeclaredField("id");
            f.setAccessible(true);
            return (UUID) f.get(profile);
        } catch (Exception ignored) {}
        return null;
    }
}
