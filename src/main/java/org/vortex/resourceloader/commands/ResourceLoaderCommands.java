package org.vortex.resourceloader.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import org.vortex.resourceloader.ResourceLoaderMod;
import org.vortex.resourceloader.util.FileUtil;
import org.vortex.resourceloader.validation.PackValidator;

import java.io.File;
import java.util.*;

public class ResourceLoaderCommands {

    private static final SuggestionProvider<CommandSourceStack> PACK_SUGGESTIONS = (ctx, builder) -> {
        ResourceLoaderMod mod = ResourceLoaderMod.getInstance();
        if (mod != null) {
            Set<String> packs = new HashSet<>(mod.getPackManager().getResourcePacks().keySet());
            if (mod.getConfig().resourcePacks != null) {
                packs.addAll(mod.getConfig().resourcePacks.keySet());
            }
            return SharedSuggestionProvider.suggest(packs, builder);
        }
        return builder.buildFuture();
    };

    private static final SuggestionProvider<CommandSourceStack> AUTOLOAD_SUGGESTIONS = (ctx, builder) -> {
        ResourceLoaderMod mod = ResourceLoaderMod.getInstance();
        if (mod != null) {
            Set<String> packs = new HashSet<>(mod.getPackManager().getResourcePacks().keySet());
            if (mod.getConfig().resourcePacks != null) {
                packs.addAll(mod.getConfig().resourcePacks.keySet());
            }
            packs.add("clear");
            return SharedSuggestionProvider.suggest(packs, builder);
        }
        return builder.buildFuture();
    };

    private static boolean hasAdminPermission(CommandSourceStack source) {
        return source.permissions() != null && source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        registerLoadCommand(dispatcher, "load");
        registerLoadCommand(dispatcher, "resourceload");

        registerUnloadCommand(dispatcher, "unload");
        registerUnloadCommand(dispatcher, "resourceunload");

        registerPackListCommand(dispatcher, "packlist");
        registerPackListCommand(dispatcher, "listpacks");
        registerPackListCommand(dispatcher, "resourcepacks");

        registerAutoLoadCommand(dispatcher, "autoload");

        registerMergeCommand(dispatcher, "mergepack");
        registerMergeCommand(dispatcher, "merge");

        registerCheckPackCommand(dispatcher, "checkpack");
        registerCheckPackCommand(dispatcher, "validatepack");

        registerReloadCommand(dispatcher, "resourcereload");
        registerReloadCommand(dispatcher, "rreload");

        registerClearCacheCommand(dispatcher, "clearcache");
        registerClearCacheCommand(dispatcher, "cacheclear");

        registerVersionCommand(dispatcher, "resourceversion");
        registerVersionCommand(dispatcher, "rversion");

        registerHelpCommand(dispatcher, "resourcehelp");
        registerHelpCommand(dispatcher, "rhelp");
    }

    private static void registerLoadCommand(CommandDispatcher<CommandSourceStack> dispatcher, String name) {
        dispatcher.register(Commands.literal(name)
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayer();
                    if (player == null) {
                        ctx.getSource().sendFailure(ResourceLoaderMod.getInstance().getMessageManager().getMessage("general.players-only"));
                        return 0;
                    }
                    String defaultPack = ResourceLoaderMod.getInstance().getConfig().serverPack;
                    if (defaultPack == null || defaultPack.isBlank()) {
                        player.sendSystemMessage(ResourceLoaderMod.getInstance().getMessageManager().getMessage("resource-packs.no-default"));
                        return 0;
                    }
                    ResourceLoaderMod.getInstance().getPackManager().sendResourcePack(player, "server");
                    return 1;
                })
                .then(Commands.argument("pack", StringArgumentType.string())
                        .suggests(PACK_SUGGESTIONS)
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayer();
                            if (player == null) {
                                ctx.getSource().sendFailure(ResourceLoaderMod.getInstance().getMessageManager().getMessage("general.players-only"));
                                return 0;
                            }
                            String packName = StringArgumentType.getString(ctx, "pack");
                            ResourceLoaderMod.getInstance().getPackManager().sendResourcePack(player, packName);
                            return 1;
                        })
                        .then(Commands.argument("targets", EntityArgument.players())
                                .requires(ResourceLoaderCommands::hasAdminPermission)
                                .executes(ctx -> {
                                    String packName = StringArgumentType.getString(ctx, "pack");
                                    Collection<ServerPlayer> targets = EntityArgument.getPlayers(ctx, "targets");
                                    for (ServerPlayer target : targets) {
                                        ResourceLoaderMod.getInstance().getPackManager().sendResourcePack(target, packName);
                                    }
                                    ctx.getSource().sendSuccess(() -> Component.literal("§aSent resource pack '§e" + packName + "§a' to " + targets.size() + " player(s)."), false);
                                    return targets.size();
                                })
                        )
                )
        );
    }

    private static void registerUnloadCommand(CommandDispatcher<CommandSourceStack> dispatcher, String name) {
        dispatcher.register(Commands.literal(name)
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayer();
                    if (player == null) {
                        ctx.getSource().sendFailure(ResourceLoaderMod.getInstance().getMessageManager().getMessage("general.players-only"));
                        return 0;
                    }
                    ResourceLoaderMod.getInstance().getPackManager().unloadResourcePack(player, null);
                    return 1;
                })
                .then(Commands.argument("pack", StringArgumentType.string())
                        .suggests(PACK_SUGGESTIONS)
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayer();
                            if (player == null) {
                                ctx.getSource().sendFailure(ResourceLoaderMod.getInstance().getMessageManager().getMessage("general.players-only"));
                                return 0;
                            }
                            String packName = StringArgumentType.getString(ctx, "pack");
                            ResourceLoaderMod.getInstance().getPackManager().unloadResourcePack(player, packName);
                            return 1;
                        })
                )
        );
    }

    private static void registerPackListCommand(CommandDispatcher<CommandSourceStack> dispatcher, String name) {
        dispatcher.register(Commands.literal(name)
                .executes(ctx -> {
                    ResourceLoaderMod mod = ResourceLoaderMod.getInstance();
                    Map<String, File> packs = mod.getPackManager().getResourcePacks();
                    Map<String, String> configPacks = mod.getConfig().resourcePacks;

                    ctx.getSource().sendSuccess(() -> mod.getMessageManager().getMessageWithoutPrefix("list.header"), false);

                    String serverPack = mod.getConfig().serverPack;
                    if (serverPack != null && !serverPack.isBlank()) {
                        ctx.getSource().sendSuccess(() -> mod.getMessageManager().formatMessageWithoutPrefix("list.default-pack", "pack", serverPack), false);
                    }

                    Set<String> allPackNames = new TreeSet<>(packs.keySet());
                    if (configPacks != null) {
                        allPackNames.addAll(configPacks.keySet());
                    }

                    if (allPackNames.isEmpty()) {
                        ctx.getSource().sendSuccess(() -> mod.getMessageManager().getMessageWithoutPrefix("list.no-packs"), false);
                    } else {
                        for (String packName : allPackNames) {
                            File file = packs.get(packName);
                            String type = (file != null) ? "Local" : "Remote URL";
                            String size = (file != null && file.exists()) ? FileUtil.formatFileSize(file.length()) : "Remote";

                            MutableComponent line = Component.literal("§7• §f" + packName + " §7(§b" + type + "§7, §a" + size + "§7) ")
                                    .append(Component.literal("§e[Load]")
                                            .withStyle(style -> style
                                                    .withColor(ChatFormatting.YELLOW)
                                                    .withClickEvent(new ClickEvent.RunCommand("/load " + packName))
                                                    .withHoverEvent(new HoverEvent.ShowText(Component.literal("§eClick to load " + packName)))
                                            )
                                    );
                            ctx.getSource().sendSuccess(() -> line, false);
                        }
                    }

                    ctx.getSource().sendSuccess(() -> mod.getMessageManager().getMessageWithoutPrefix("list.footer"), false);
                    return 1;
                })
        );
    }

    private static void registerAutoLoadCommand(CommandDispatcher<CommandSourceStack> dispatcher, String name) {
        dispatcher.register(Commands.literal(name)
                .executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayer();
                    if (player == null) {
                        ctx.getSource().sendFailure(ResourceLoaderMod.getInstance().getMessageManager().getMessage("general.players-only"));
                        return 0;
                    }
                    ResourceLoaderMod mod = ResourceLoaderMod.getInstance();
                    List<String> preferences = mod.getPackManager().getPlayerPreferences(player.getUUID());
                    if (preferences.isEmpty()) {
                        player.sendSystemMessage(mod.getMessageManager().getMessage("autoload.no-preference"));
                    } else {
                        player.sendSystemMessage(mod.getMessageManager().formatMessage("autoload.current-preference", "pack", preferences.get(0)));
                    }
                    return 1;
                })
                .then(Commands.argument("pack", StringArgumentType.string())
                        .suggests(AUTOLOAD_SUGGESTIONS)
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayer();
                            if (player == null) {
                                ctx.getSource().sendFailure(ResourceLoaderMod.getInstance().getMessageManager().getMessage("general.players-only"));
                                return 0;
                            }
                            String packName = StringArgumentType.getString(ctx, "pack").toLowerCase();
                            ResourceLoaderMod mod = ResourceLoaderMod.getInstance();

                            if (packName.equalsIgnoreCase("clear")) {
                                mod.getPackManager().clearPlayerPreferences(player.getUUID());
                                player.sendSystemMessage(mod.getMessageManager().getMessage("autoload.cleared"));
                                return 1;
                            }

                            if (!mod.getPackManager().getResourcePacks().containsKey(packName) &&
                                    (mod.getConfig().resourcePacks == null || !mod.getConfig().resourcePacks.containsKey(packName))) {
                                player.sendSystemMessage(mod.getMessageManager().formatMessage("general.invalid-pack", "pack", packName));
                                return 0;
                            }

                            mod.getPackManager().setPlayerPreference(player.getUUID(), packName);
                            player.sendSystemMessage(mod.getMessageManager().formatMessage("autoload.set", "pack", packName));
                            mod.getPackManager().sendResourcePack(player, packName);
                            return 1;
                        })
                )
        );
    }

    private static void registerMergeCommand(CommandDispatcher<CommandSourceStack> dispatcher, String name) {
        dispatcher.register(Commands.literal(name)
                .requires(ResourceLoaderCommands::hasAdminPermission)
                .then(Commands.argument("output", StringArgumentType.string())
                        .then(Commands.argument("packs", StringArgumentType.greedyString())
                                .executes(ctx -> {
                                    String output = StringArgumentType.getString(ctx, "output");
                                    String rawPacks = StringArgumentType.getString(ctx, "packs");
                                    String[] splitPacks = rawPacks.split("\\s+");

                                    if (splitPacks.length < 2) {
                                        ctx.getSource().sendFailure(ResourceLoaderMod.getInstance().getMessageManager().getMessage("merge.no-packs"));
                                        return 0;
                                    }

                                    ResourceLoaderMod mod = ResourceLoaderMod.getInstance();
                                    List<File> filesToMerge = new ArrayList<>();
                                    for (String p : splitPacks) {
                                        File f = mod.getPackManager().getResourcePacks().get(p.toLowerCase());
                                        if (f == null || !f.exists()) {
                                            ctx.getSource().sendFailure(mod.getMessageManager().formatMessage("merge.invalid-pack", "pack", p));
                                            return 0;
                                        }
                                        filesToMerge.add(f);
                                    }

                                    ctx.getSource().sendSuccess(() -> mod.getMessageManager().getMessage("merge.started"), false);
                                    new Thread(() -> {
                                        try {
                                            File merged = mod.getPackMerger().mergeResourcePacks(filesToMerge, output);
                                            mod.getPackManager().loadResourcePacks(false);
                                            ctx.getSource().sendSuccess(() -> mod.getMessageManager().formatMessage("merge.success", "pack", merged.getName()), false);
                                        } catch (Exception e) {
                                            ctx.getSource().sendFailure(mod.getMessageManager().formatMessage("merge.failed", "error", e.getMessage()));
                                        }
                                    }, "ResourceLoader-Merge").start();

                                    return 1;
                                })
                        )
                )
        );
    }

    private static void registerCheckPackCommand(CommandDispatcher<CommandSourceStack> dispatcher, String name) {
        dispatcher.register(Commands.literal(name)
                .requires(ResourceLoaderCommands::hasAdminPermission)
                .then(Commands.argument("pack", StringArgumentType.string())
                        .suggests(PACK_SUGGESTIONS)
                        .executes(ctx -> {
                            String packName = StringArgumentType.getString(ctx, "pack").toLowerCase();
                            ResourceLoaderMod mod = ResourceLoaderMod.getInstance();
                            File packFile = mod.getPackManager().getResourcePacks().get(packName);

                            if (packFile == null || !packFile.exists()) {
                                ctx.getSource().sendFailure(mod.getMessageManager().formatMessage("resource-packs.file-not-found", "pack", packName));
                                return 0;
                            }

                            PackValidator validator = new PackValidator();
                            PackValidator.ValidationResult result = validator.validate(packFile);

                            ctx.getSource().sendSuccess(() -> Component.literal("§6=== Pack Validation: §e" + packName + " §6==="), false);
                            for (String issue : result.getFormattedIssues()) {
                                ctx.getSource().sendSuccess(() -> Component.literal(issue), false);
                            }
                            ctx.getSource().sendSuccess(() -> Component.literal("§6================================="), false);
                            return 1;
                        })
                )
        );
    }

    private static void registerReloadCommand(CommandDispatcher<CommandSourceStack> dispatcher, String name) {
        dispatcher.register(Commands.literal(name)
                .requires(ResourceLoaderCommands::hasAdminPermission)
                .executes(ctx -> {
                    try {
                        ResourceLoaderMod mod = ResourceLoaderMod.getInstance();
                        mod.reload();
                        ctx.getSource().sendSuccess(() -> mod.getMessageManager().getMessage("general.reload-success"), false);
                        return 1;
                    } catch (Exception e) {
                        ctx.getSource().sendFailure(ResourceLoaderMod.getInstance().getMessageManager().formatMessage("general.reload-failed", "error", e.getMessage()));
                        return 0;
                    }
                })
        );
    }

    private static void registerClearCacheCommand(CommandDispatcher<CommandSourceStack> dispatcher, String name) {
        dispatcher.register(Commands.literal(name)
                .requires(ResourceLoaderCommands::hasAdminPermission)
                .executes(ctx -> {
                    ResourceLoaderMod mod = ResourceLoaderMod.getInstance();
                    mod.getPackManager().getPackCache().clearCache();
                    mod.getPackManager().getHashCache().clearAllCache();
                    ctx.getSource().sendSuccess(() -> mod.getMessageManager().getMessage("cache.cleared"), false);
                    return 1;
                })
        );
    }

    private static void registerVersionCommand(CommandDispatcher<CommandSourceStack> dispatcher, String name) {
        dispatcher.register(Commands.literal(name)
                .requires(ResourceLoaderCommands::hasAdminPermission)
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal("§7[§eResourceLoader§7] §aVersion: §f0.7 (Beta) §7(Fabric 26.3 Server Mod)"), false);
                    return 1;
                })
        );
    }

    private static void registerHelpCommand(CommandDispatcher<CommandSourceStack> dispatcher, String name) {
        dispatcher.register(Commands.literal(name)
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal("§6=== ResourceLoader Help ==="), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("§e/load [pack] §7- Load server resource pack"), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("§e/unload [pack] §7- Unload / remove server resource pack"), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("§e/packlist §7- List available resource packs"), false);
                    ctx.getSource().sendSuccess(() -> Component.literal("§e/autoload <pack|clear> §7- Set preferred join pack"), false);
                    if (hasAdminPermission(ctx.getSource())) {
                        ctx.getSource().sendSuccess(() -> Component.literal("§6=== Admin Commands ==="), false);
                        ctx.getSource().sendSuccess(() -> Component.literal("§e/load <pack> <targets> §7- Send pack to specific players"), false);
                        ctx.getSource().sendSuccess(() -> Component.literal("§e/mergepack <output> <p1> <p2>... §7- Merge resource packs"), false);
                        ctx.getSource().sendSuccess(() -> Component.literal("§e/checkpack <pack> §7- Validate pack structure & textures"), false);
                        ctx.getSource().sendSuccess(() -> Component.literal("§e/resourcereload §7- Reload configuration & packs"), false);
                        ctx.getSource().sendSuccess(() -> Component.literal("§e/clearcache §7- Clear downloaded packs cache"), false);
                        ctx.getSource().sendSuccess(() -> Component.literal("§e/resourceversion §7- Show mod version"), false);
                    }
                    ctx.getSource().sendSuccess(() -> Component.literal("§6=========================="), false);
                    return 1;
                })
        );
    }
}
