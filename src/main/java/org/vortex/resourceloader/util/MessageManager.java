package org.vortex.resourceloader.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vortex.resourceloader.ResourceLoaderMod;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

public class MessageManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("ResourceLoader/Messages");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path messagesFile;
    private final Map<String, String> messages = new LinkedHashMap<>();

    public MessageManager(Path configDir) {
        this.messagesFile = configDir.resolve("messages.json");
        this.loadDefaults();
        this.load();
    }

    private void loadDefaults() {
        this.messages.put("prefix", "&7[&eResourceLoader&7] &r");
        this.messages.put("general.no-permission", "&cYou don't have permission to use this command.");
        this.messages.put("general.reload-success", "&aConfiguration and textures reloaded successfully!");
        this.messages.put("general.reload-failed", "&cFailed to reload configuration: %error%");
        this.messages.put("general.invalid-pack", "&cResource pack '%pack%' not found!");
        this.messages.put("general.players-only", "&cThis command can only be used by players.");

        this.messages.put("resource-packs.loading", "&eLoading resource pack %pack%...");
        this.messages.put("resource-packs.load-success", "&aResource pack loaded successfully!");
        this.messages.put("resource-packs.load-failed", "&cFailed to load resource pack: %error%");
        this.messages.put("resource-packs.no-default", "&cNo default resource pack configured.");
        this.messages.put("resource-packs.not-found", "&cResource pack '%pack%' not found.");
        this.messages.put("resource-packs.file-not-found", "&cResource pack file not found.");
        this.messages.put("resource-packs.invalid-path", "&cInvalid path for resource pack '%pack%'.");
        this.messages.put("resource-packs.unloaded", "&aResource pack has been unloaded.");
        this.messages.put("resource-packs.unload-failed", "&cFailed to unload resource pack: %error%");

        this.messages.put("list.header", "&6=== Available Resource Packs ===");
        this.messages.put("list.no-packs", "&7No resource packs available.");
        this.messages.put("list.default-pack", "&7Default Pack: &f%pack%");
        this.messages.put("list.pack-entry", "&7• &f%pack% &7(&b%type%&7, &a%size%&7)");
        this.messages.put("list.footer", "&6===========================");

        this.messages.put("autoload.no-preference", "&eYou don't have a preferred resource pack set.");
        this.messages.put("autoload.current-preference", "&eYour current preferred pack is: &6%pack%");
        this.messages.put("autoload.set", "&aSet &6%pack% &aas your preferred resource pack.");
        this.messages.put("autoload.set-failed", "&cFailed to apply resource pack &6%pack%&c: %error%");
        this.messages.put("autoload.cleared", "&aCleared your resource pack preferences.");
        this.messages.put("autoload.loaded", "&aAutomatically loaded your preferred pack: &6%pack%");

        this.messages.put("merge.started", "&7Starting to merge resource packs...");
        this.messages.put("merge.progress", "&7Merging pack &e%current%&7/&e%total%");
        this.messages.put("merge.success", "&aSuccessfully merged resource packs into: &e%pack%");
        this.messages.put("merge.failed", "&cFailed to merge resource packs: %error%");
        this.messages.put("merge.already-merging", "&cA merge operation is already in progress. Please wait.");
        this.messages.put("merge.invalid-usage", "&cUsage: /mergepack <output_name> <pack1> <pack2> [pack3...]");
        this.messages.put("merge.no-packs", "&cYou must specify at least two packs to merge!");
        this.messages.put("merge.invalid-pack", "&cResource pack '%pack%' not found!");
        this.messages.put("merge.output-exists", "&cA resource pack with name '%pack%' already exists!");

        this.messages.put("cache.cleared", "&aResource pack cache has been cleared!");
        this.messages.put("cache.using-cached", "&7Using cached version of %pack%");
        this.messages.put("cache.downloading-new", "&7Downloading new version of %pack%");

        this.messages.put("enforcement.declined", "&cYou must accept the resource pack to play on this server!");
        this.messages.put("enforcement.failed", "&cFailed to download the resource pack. Please try joining again!");
        this.messages.put("enforcement.restricted-command", "&cYou cannot use this command until the resource pack is loaded.");
    }

    public void load() {
        File file = this.messagesFile.toFile();
        if (!file.exists()) {
            this.save();
            return;
        }

        try (FileReader reader = new FileReader(file)) {
            Map<String, String> loaded = GSON.fromJson(reader, new TypeToken<Map<String, String>>() {}.getType());
            if (loaded != null) {
                this.messages.putAll(loaded);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load messages file: {}", e.getMessage());
        }

        // Apply any overrides defined in config.json
        ResourceLoaderMod mod = ResourceLoaderMod.getInstance();
        if (mod != null && mod.getConfig() != null && mod.getConfig().messages != null) {
            if (mod.getConfig().messages.prefix != null && !mod.getConfig().messages.prefix.isBlank()) {
                this.messages.put("prefix", mod.getConfig().messages.prefix);
            }
            if (mod.getConfig().messages.customMessages != null) {
                this.messages.putAll(mod.getConfig().messages.customMessages);
            }
        }
    }

    public void save() {
        try {
            Files.createDirectories(this.messagesFile.getParent());
            try (FileWriter writer = new FileWriter(this.messagesFile.toFile())) {
                GSON.toJson(this.messages, writer);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to save messages file: {}", e.getMessage());
        }
    }

    public String getRawMessage(String key) {
        ResourceLoaderMod mod = ResourceLoaderMod.getInstance();
        if (mod != null && mod.getConfig() != null && mod.getConfig().messages != null && mod.getConfig().messages.customMessages != null) {
            if (mod.getConfig().messages.customMessages.containsKey(key)) {
                return mod.getConfig().messages.customMessages.get(key);
            }
        }
        return this.messages.getOrDefault(key, key);
    }

    public Component getMessage(String key) {
        return format(getPrefix() + getRawMessage(key));
    }

    public Component getMessageWithoutPrefix(String key) {
        return format(getRawMessage(key));
    }

    public Component formatMessage(String key, String... replacements) {
        String msg = getPrefix() + getRawMessage(key);
        for (int i = 0; i < replacements.length; i += 2) {
            if (i + 1 < replacements.length) {
                msg = msg.replace("%" + replacements[i] + "%", replacements[i + 1]);
            }
        }
        return format(msg);
    }

    public Component formatMessageWithoutPrefix(String key, String... replacements) {
        String msg = getRawMessage(key);
        for (int i = 0; i < replacements.length; i += 2) {
            if (i + 1 < replacements.length) {
                msg = msg.replace("%" + replacements[i] + "%", replacements[i + 1]);
            }
        }
        return format(msg);
    }

    public void sendToPlayer(ServerPlayer player, String key, String... replacements) {
        if (player == null) return;
        ResourceLoaderMod mod = ResourceLoaderMod.getInstance();
        if (mod != null && mod.getConfig() != null && mod.getConfig().messages != null) {
            if (!mod.getConfig().messages.enabled) return;
        }
        player.sendSystemMessage(formatMessage(key, replacements));
    }

    public void sendLoading(ServerPlayer player, String packName) {
        ResourceLoaderMod mod = ResourceLoaderMod.getInstance();
        if (mod != null && mod.getConfig() != null && mod.getConfig().messages != null) {
            if (!mod.getConfig().messages.enabled || !mod.getConfig().messages.showLoadingMessages) return;
        }
        sendToPlayer(player, "resource-packs.loading", "pack", packName);
    }

    public void sendSuccess(ServerPlayer player, String key, String... replacements) {
        ResourceLoaderMod mod = ResourceLoaderMod.getInstance();
        if (mod != null && mod.getConfig() != null && mod.getConfig().messages != null) {
            if (!mod.getConfig().messages.enabled || !mod.getConfig().messages.showSuccessMessages) return;
        }
        sendToPlayer(player, key, replacements);
    }

    public void sendError(ServerPlayer player, String key, String... replacements) {
        ResourceLoaderMod mod = ResourceLoaderMod.getInstance();
        if (mod != null && mod.getConfig() != null && mod.getConfig().messages != null) {
            if (!mod.getConfig().messages.enabled || !mod.getConfig().messages.showErrorMessages) return;
        }
        sendToPlayer(player, key, replacements);
    }

    public void sendAutoload(ServerPlayer player, String key, String... replacements) {
        ResourceLoaderMod mod = ResourceLoaderMod.getInstance();
        if (mod != null && mod.getConfig() != null && mod.getConfig().messages != null) {
            if (!mod.getConfig().messages.enabled || !mod.getConfig().messages.showAutoloadMessages) return;
        }
        sendToPlayer(player, key, replacements);
    }

    public String getPrefix() {
        ResourceLoaderMod mod = ResourceLoaderMod.getInstance();
        if (mod != null && mod.getConfig() != null && mod.getConfig().messages != null) {
            if (mod.getConfig().messages.prefix != null) {
                return mod.getConfig().messages.prefix;
            }
        }
        return this.messages.getOrDefault("prefix", "");
    }

    public static Component format(String text) {
        if (text == null) {
            return Component.empty();
        }

        // Convert standard & color codes to §
        String converted = text.replace('&', '§');
        MutableComponent root = Component.empty();
        
        String[] parts = converted.split("§");
        if (parts.length == 0) {
            return Component.literal(converted);
        }

        if (!parts[0].isEmpty()) {
            root.append(Component.literal(parts[0]));
        }

        Style currentStyle = Style.EMPTY;
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty()) continue;

            char code = Character.toLowerCase(part.charAt(0));
            String content = part.substring(1);

            ChatFormatting formatting = ChatFormatting.getByCode(code);
            if (formatting != null) {
                if (formatting.ordinal() < 16 || formatting == ChatFormatting.RESET) {
                    currentStyle = Style.EMPTY.applyFormat(formatting);
                } else {
                    currentStyle = currentStyle.applyFormat(formatting);
                }
            }

            if (!content.isEmpty()) {
                root.append(Component.literal(content).setStyle(currentStyle));
            }
        }

        return root;
    }
}
