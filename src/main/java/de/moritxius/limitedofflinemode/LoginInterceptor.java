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
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Per-connection Netty handler injected by LimitedOfflineModePaper via
 * Paper's ChannelInitializeListenerHolder.
 *
 * Login sequence:
 *   C→S  ServerboundHelloPacket       (LoginStart)
 *   S→C  ClientboundHelloPacket       (EncryptionRequest) ← we CANCEL for whitelisted players
 *
 * After cancelling we call Paper's own
 * ServerLoginPacketListenerImpl#verifyLoginAndFinishConnectionSetup(GameProfile)
 * with an offline profile.  That method fires AsyncPlayerPreLoginEvent,
 * handles compression, and sends ClientboundLoginFinishedPacket (LoginSuccess)
 * exactly as normal — we just skipped the Mojang auth step.
 *
 * Verified against Paper 26.1.2 (Mojang-mapped).
 * Key fields/methods used:
 *   Connection.packetListener                                          (private volatile)
 *   ServerLoginPacketListenerImpl.authenticatedProfile                 (public)
 *   ServerLoginPacketListenerImpl#verifyLoginAndFinishConnectionSetup  (private)
 *   ServerLoginPacketListenerImpl#createOfflineProfile                 (protected)
 */
public class LoginInterceptor extends ChannelDuplexHandler {

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
            if (cls.contains("ServerboundHello") || cls.contains("LoginStart")) {
                String username = extractUsername(msg);
                if (username != null && plugin.isUserAllowed(username)) {
                    pendingUsername = username;
                    plugin.getLogger().info("[LOM] Whitelisted offline player connecting: " + username);
                }
            }
        }

        // ── Play phase: intercept chat before Paper checks profile key ────────
        if (isOfflinePlayer) {
            String cls = msg.getClass().getSimpleName();
            // ServerboundChatPacket but NOT ServerboundChatCommandPacket
            if (cls.contains("ServerboundChat") && !cls.contains("Command")) {
                String message = extractChatMessage(msg);
                if (message != null && !message.isBlank()) {
                    String username = offlineUsername;
                    LimitedOfflineModePaper.scheduleTask(plugin, () -> {
                        Player player = plugin.getServer().getPlayer(username);
                        if (player != null) firePlayerChat(player, message);
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
            if (cls.contains("ClientboundHello") || cls.contains("EncryptionRequest")) {
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
            // Prefer Paper's own createOfflineProfile() so the UUID matches what
            // Paper uses everywhere else (consistent with /whitelist, player data files, etc.)
            Object offlineProfile = createOfflineProfile(loginListener, username);

            // Also set authenticatedProfile so any code reading that field sees our profile
            setFieldByName(loginListener, "authenticatedProfile", offlineProfile);

            // Call verifyLoginAndFinishConnectionSetup — this is what Paper calls after
            // successful Mojang auth.  It fires login events, sets up compression, and
            // sends ClientboundLoginFinishedPacket (LoginSuccess).
            invokePrivate(loginListener, "verifyLoginAndFinishConnectionSetup",
                    new Class<?>[]{Class.forName("com.mojang.authlib.GameProfile")},
                    offlineProfile);

            isOfflinePlayer = true;
            plugin.getLogger().info("[LOM] Offline login injected for " + username
                    + " (UUID: " + getProfileUUID(offlineProfile) + ")");

        } catch (Exception e) {
            plugin.getLogger().severe("[LOM] Failed to inject offline login for "
                    + username + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ── Reflection helpers ───────────────────────────────────────────────────

    /**
     * packet_handler in the Netty pipeline is net.minecraft.network.Connection.
     * Connection.packetListener holds ServerLoginPacketListenerImpl during login.
     */
    private static Object resolveLoginListener(Channel channel) throws Exception {
        Object connection = channel.pipeline().get("packet_handler");
        if (connection == null) return null;

        // Try by field name first (stable in Mojang-mapped Paper)
        Object listener = getFieldValueByName(connection, "packetListener");
        if (listener != null && listener.getClass().getSimpleName().contains("Login")) {
            return listener;
        }

        // Fallback: scan all fields for a ServerLoginPacketListenerImpl
        Class<?> clazz = connection.getClass();
        while (clazz != null) {
            for (Field f : clazz.getDeclaredFields()) {
                f.setAccessible(true);
                Object val = f.get(connection);
                if (val != null && val.getClass().getSimpleName().contains("ServerLoginPacketListenerImpl")) {
                    return val;
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    /**
     * Uses Paper's own createOfflineProfile(String) method so the UUID is
     * generated the same way Paper generates it everywhere else.
     * Falls back to the standard offline UUID algorithm if the method is absent.
     */
    private static Object createOfflineProfile(Object loginListener, String username) throws Exception {
        try {
            Method m = loginListener.getClass().getDeclaredMethod("createOfflineProfile", String.class);
            m.setAccessible(true);
            return m.invoke(loginListener, username);
        } catch (NoSuchMethodException ignored) {
            // Manual fallback
            UUID uuid = UUID.nameUUIDFromBytes(
                    ("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8));
            Class<?> profileClass = Class.forName("com.mojang.authlib.GameProfile");
            return profileClass.getConstructor(UUID.class, String.class).newInstance(uuid, username);
        }
    }

    /** Sets a field by exact name, searching up the class hierarchy. */
    private static void setFieldByName(Object obj, String name, Object value) throws Exception {
        Class<?> clazz = obj.getClass();
        while (clazz != null) {
            try {
                Field f = clazz.getDeclaredField(name);
                f.setAccessible(true);
                f.set(obj, value);
                return;
            } catch (NoSuchFieldException ignored) {}
            clazz = clazz.getSuperclass();
        }
        throw new IllegalStateException("Field '" + name + "' not found in " + obj.getClass().getName());
    }

    /** Gets a field value by name, searching up the class hierarchy. */
    private static Object getFieldValueByName(Object obj, String name) {
        Class<?> clazz = obj.getClass();
        while (clazz != null) {
            try {
                Field f = clazz.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(obj);
            } catch (NoSuchFieldException ignored) {
            } catch (Exception e) {
                return null;
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    /** Invokes a private/protected method by name with the given argument types and args. */
    private static void invokePrivate(Object obj, String methodName,
                                      Class<?>[] paramTypes, Object... args) throws Exception {
        Class<?> clazz = obj.getClass();
        while (clazz != null) {
            try {
                Method m = clazz.getDeclaredMethod(methodName, paramTypes);
                m.setAccessible(true);
                m.invoke(obj, args);
                return;
            } catch (NoSuchMethodException ignored) {}
            clazz = clazz.getSuperclass();
        }
        throw new IllegalStateException("Method '" + methodName + "' not found in " + obj.getClass().getName());
    }

    /** Fires AsyncPlayerChatEvent for an offline player and broadcasts if not cancelled. */
    @SuppressWarnings("deprecation")
    private void firePlayerChat(Player player, String message) {
        Set<Player> recipients = new HashSet<>(plugin.getServer().getOnlinePlayers());
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

    /** Extracts the chat message string from a ServerboundChatPacket via reflection. */
    private static String extractChatMessage(Object packet) {
        // Try accessor methods first
        for (String name : new String[]{"getMessage", "message"}) {
            try {
                Method m = packet.getClass().getMethod(name);
                Object val = m.invoke(packet);
                if (val instanceof String s && !s.isBlank()) return s;
            } catch (Exception ignored) {}
        }
        // Fallback: scan String fields (message is usually the first String field)
        Class<?> clazz = packet.getClass();
        while (clazz != null) {
            for (Field f : clazz.getDeclaredFields()) {
                if (f.getType() == String.class) {
                    f.setAccessible(true);
                    try {
                        Object val = f.get(packet);
                        if (val instanceof String s && !s.isBlank()) return s;
                    } catch (Exception ignored) {}
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    /** Extracts the username from a ServerboundHelloPacket via method or field. */
    private static String extractUsername(Object packet) {
        for (String name : new String[]{"name", "getName", "getPlayerName", "getUsername"}) {
            try {
                Method m = packet.getClass().getMethod(name);
                Object val = m.invoke(packet);
                if (val instanceof String s && !s.isBlank()) return s;
            } catch (NoSuchMethodException ignored) {
            } catch (Exception ignored) {}
        }
        Class<?> clazz = packet.getClass();
        while (clazz != null) {
            for (Field f : clazz.getDeclaredFields()) {
                if (f.getType() == String.class) {
                    f.setAccessible(true);
                    try {
                        Object val = f.get(packet);
                        if (val instanceof String s && !s.isBlank()) return s;
                    } catch (Exception ignored) {}
                }
            }
            clazz = clazz.getSuperclass();
        }
        return null;
    }

    private static UUID getProfileUUID(Object profile) {
        for (String name : new String[]{"getId", "id"}) {
            try {
                return (UUID) profile.getClass().getMethod(name).invoke(profile);
            } catch (Exception ignored) {}
        }
        // Try field access
        try {
            Field f = profile.getClass().getDeclaredField("id");
            f.setAccessible(true);
            return (UUID) f.get(profile);
        } catch (Exception ignored) {}
        return null;
    }
}
