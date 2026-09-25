package org.vortex.resourceloader.listeners;

import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vortex.resourceloader.ResourceLoaderMod;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ResourcePackEnforcer {
    private static final Logger LOGGER = LoggerFactory.getLogger("ResourceLoader/Enforcer");
    private final ResourceLoaderMod mod;
    private final Set<UUID> restrictedPlayers = ConcurrentHashMap.newKeySet();

    public ResourcePackEnforcer(ResourceLoaderMod mod) {
        this.mod = mod;
    }

    public void onPlayerJoin(ServerPlayer player) {
        if (player == null) return;
        if (hasBypass(player)) {
            LOGGER.info("Player {} has bypass permission, skipping automatic pack loading", player.getScoreboardName());
            return;
        }

        List<String> preferences = mod.getPackManager().getPlayerPreferences(player.getUUID());
        if (!preferences.isEmpty()) {
            String preferred = preferences.get(0);
            LOGGER.info("Autoloading preferred pack '{}' for player {}", preferred, player.getScoreboardName());
            mod.getPackManager().sendResourcePack(player, preferred);
            return;
        }

        if (mod.getConfig().enforcement.enabled) {
            String serverPack = mod.getConfig().serverPack;
            if (serverPack != null && !serverPack.isBlank()) {
                LOGGER.info("Enforcing default server pack for player {}", player.getScoreboardName());
                if (mod.getConfig().enforcement.preventInteraction) {
                    this.restrictedPlayers.add(player.getUUID());
                }
                mod.getPackManager().sendResourcePack(player, "server");
            }
        }
    }

    public void onPlayerDisconnect(ServerPlayer player) {
        if (player == null) return;
        this.restrictedPlayers.remove(player.getUUID());
    }

    public void onPackStatus(ServerPlayer player, ServerboundResourcePackPacket.Action status) {
        if (player == null) return;
        String playerName = player.getScoreboardName();

        switch (status) {
            case SUCCESSFULLY_LOADED -> {
                LOGGER.info("Player {} successfully loaded resource pack.", playerName);
                this.restrictedPlayers.remove(player.getUUID());
            }
            case ACCEPTED -> {
                LOGGER.info("Player {} accepted resource pack download.", playerName);
            }
            case DECLINED -> {
                LOGGER.warn("Player {} declined resource pack.", playerName);
                if (mod.getConfig().enforcement.enabled && mod.getConfig().enforcement.kickOnDecline && !hasBypass(player)) {
                    if (player.connection != null) {
                        player.connection.disconnect(mod.getMessageManager().getMessageWithoutPrefix("enforcement.declined"));
                    }
                }
            }
            case FAILED_DOWNLOAD -> {
                LOGGER.warn("Player {} failed to download resource pack.", playerName);
                if (mod.getConfig().enforcement.enabled && mod.getConfig().enforcement.kickOnFail && !hasBypass(player)) {
                    if (player.connection != null) {
                        player.connection.disconnect(mod.getMessageManager().getMessageWithoutPrefix("enforcement.failed"));
                    }
                }
            }
            case FAILED_RELOAD -> {
                LOGGER.warn("Player {} failed to reload resource pack.", playerName);
            }
            case DISCARDED -> {
                LOGGER.info("Player {} discarded previous resource pack.", playerName);
            }
            case DOWNLOADED -> {
                LOGGER.info("Player {} downloaded resource pack.", playerName);
            }
            default -> {
                LOGGER.info("Player {} resource pack status: {}", playerName, status);
            }
        }
    }

    public boolean isRestricted(ServerPlayer player) {
        return player != null && restrictedPlayers.contains(player.getUUID());
    }

    private boolean hasBypass(ServerPlayer player) {
        return player.permissions() != null && player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }
}
