package com.warevent;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.scores.PlayerTeam;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

import java.util.Arrays;
import java.util.Locale;

@EventBusSubscriber(modid = WarEventMod.MOD_ID)
public final class TeamCommands {
    private static final String[] COLORS = {"black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray", "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white"};

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        var team = Commands.literal("team").requires(source -> source.hasPermission(2));
        team.then(Commands.literal("create").then(Commands.argument("name", StringArgumentType.word()).then(
                Commands.argument("color", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(COLORS, b)).executes(TeamCommands::create))));
        team.then(Commands.literal("delete").then(Commands.argument("name", StringArgumentType.word()).suggests(teamSuggestions).executes(TeamCommands::delete)));
        team.then(Commands.literal("add").then(Commands.argument("team", StringArgumentType.word()).suggests(teamSuggestions).then(
                Commands.argument("player", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(c.getSource().getServer().getPlayerList().getPlayers().stream().map(ServerPlayer::getGameProfile).map(p -> p.getName()), b)).executes(TeamCommands::add))));
        team.then(Commands.literal("remove").then(Commands.argument("team", StringArgumentType.word()).suggests(teamSuggestions).then(
                Commands.argument("player", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(c.getSource().getServer().getPlayerList().getPlayers().stream().map(ServerPlayer::getGameProfile).map(p -> p.getName()), b)).executes(TeamCommands::remove))));
        team.then(Commands.literal("rename").then(Commands.argument("team", StringArgumentType.word()).suggests(teamSuggestions).then(
                Commands.argument("new_name", StringArgumentType.word()).executes(TeamCommands::rename))));
        team.then(Commands.literal("recolor").then(Commands.argument("team", StringArgumentType.word()).suggests(teamSuggestions).then(
                Commands.argument("color", StringArgumentType.word()).suggests((c, b) -> SharedSuggestionProvider.suggest(COLORS, b)).executes(TeamCommands::recolor))));
        event.getDispatcher().register(team);
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            TeamData data = TeamData.get(player.server);
            for (TeamData.WarTeam team : data.all()) {
                if (team.members.contains(player.getUUID())) {
                    apply(player.server, player, team);
                    break;
                }
            }
        }
    }

    private static int create(CommandContext<CommandSourceStack> c) {
        String name = StringArgumentType.getString(c, "name");
        String color = StringArgumentType.getString(c, "color").toLowerCase(Locale.ROOT);
        if (parseColor(color) == null) return fail(c, "commands.warevent.team.invalid_color");
        TeamData data = TeamData.get(c.getSource().getServer());
        if (data.get(name) != null) return fail(c, "commands.warevent.team.exists", name);
        data.put(new TeamData.WarTeam(name, color));
        c.getSource().sendSuccess(() -> Component.translatable("commands.warevent.team.created", name, color), true);
        return 1;
    }

    private static int delete(CommandContext<CommandSourceStack> c) {
        String name = StringArgumentType.getString(c, "name"); TeamData data = TeamData.get(c.getSource().getServer());
        TeamData.WarTeam team = data.get(name); if (team == null) return fail(c, "commands.warevent.team.missing", name);
        for (var player : c.getSource().getServer().getPlayerList().getPlayers()) if (team.members.contains(player.getUUID())) clearVanillaTeam(c.getSource().getServer(), player);
        data.remove(name); c.getSource().sendSuccess(() -> Component.translatable("commands.warevent.team.deleted", name), true); return 1;
    }

    private static int add(CommandContext<CommandSourceStack> c) {
        String name = StringArgumentType.getString(c, "team"); String playerName = StringArgumentType.getString(c, "player"); TeamData data = TeamData.get(c.getSource().getServer()); TeamData.WarTeam team = data.get(name);
        if (team == null) return fail(c, "commands.warevent.team.missing", name); ServerPlayer player = c.getSource().getServer().getPlayerList().getPlayerByName(playerName);
        if (player == null) return fail(c, "commands.warevent.team.missing", playerName); if (team.members.contains(player.getUUID())) return fail(c, "commands.warevent.team.player_already", playerName, name);
        data.removePlayerFromAll(player.getUUID()); team.members.add(player.getUUID()); data.setDirty(); apply(c.getSource().getServer(), player, team);
        c.getSource().sendSuccess(() -> Component.translatable("commands.warevent.team.added", playerName, name), true); return 1;
    }

    private static int remove(CommandContext<CommandSourceStack> c) {
        String name = StringArgumentType.getString(c, "team"); String playerName = StringArgumentType.getString(c, "player"); TeamData data = TeamData.get(c.getSource().getServer()); TeamData.WarTeam team = data.get(name);
        if (team == null) return fail(c, "commands.warevent.team.missing", name); ServerPlayer player = c.getSource().getServer().getPlayerList().getPlayerByName(playerName);
        if (player == null || !team.members.remove(player.getUUID())) return fail(c, "commands.warevent.team.player_missing", playerName, name);
        data.setDirty(); clearVanillaTeam(c.getSource().getServer(), player); c.getSource().sendSuccess(() -> Component.translatable("commands.warevent.team.removed", playerName, name), true); return 1;
    }

    private static int rename(CommandContext<CommandSourceStack> c) {
        String oldName = StringArgumentType.getString(c, "team"), newName = StringArgumentType.getString(c, "new_name"); TeamData data = TeamData.get(c.getSource().getServer()); TeamData.WarTeam team = data.get(oldName);
        if (team == null) return fail(c, "commands.warevent.team.missing", oldName); if (data.get(newName) != null) return fail(c, "commands.warevent.team.exists", newName);
        data.remove(oldName); team.name = newName; data.put(team); refresh(c.getSource().getServer(), team); c.getSource().sendSuccess(() -> Component.translatable("commands.warevent.team.renamed", oldName, newName), true); return 1;
    }

    private static int recolor(CommandContext<CommandSourceStack> c) {
        String name = StringArgumentType.getString(c, "team"), color = StringArgumentType.getString(c, "color").toLowerCase(Locale.ROOT); ChatFormatting parsed = parseColor(color); TeamData data = TeamData.get(c.getSource().getServer()); TeamData.WarTeam team = data.get(name);
        if (team == null) return fail(c, "commands.warevent.team.missing", name); if (parsed == null) return fail(c, "commands.warevent.team.invalid_color"); team.color = color; data.setDirty(); refresh(c.getSource().getServer(), team); c.getSource().sendSuccess(() -> Component.translatable("commands.warevent.team.recolored", name, color), true); return 1;
    }

    private static void refresh(MinecraftServer server, TeamData.WarTeam team) { for (var player : server.getPlayerList().getPlayers()) if (team.members.contains(player.getUUID())) apply(server, player, team); }
    private static void apply(MinecraftServer server, ServerPlayer player, TeamData.WarTeam team) { clearVanillaTeam(server, player); PlayerTeam display = server.getScoreboard().getPlayerTeam("warevent_" + team.key()); if (display == null) display = server.getScoreboard().addPlayerTeam("warevent_" + team.key()); ChatFormatting color = parseColor(team.color); display.setColor(color); display.setPlayerPrefix(Component.literal("■ ").withStyle(color)); display.setNameTagVisibility(net.minecraft.world.scores.Team.Visibility.ALWAYS); server.getScoreboard().addPlayerToTeam(player.getScoreboardName(), display); }
    private static void clearVanillaTeam(MinecraftServer server, ServerPlayer player) { PlayerTeam old = server.getScoreboard().getPlayersTeam(player.getScoreboardName()); if (old != null) server.getScoreboard().removePlayerFromTeam(player.getScoreboardName(), old); }
    private static final SuggestionProvider<CommandSourceStack> teamSuggestions = (c, b) -> SharedSuggestionProvider.suggest(TeamData.get(c.getSource().getServer()).all().stream().map(t -> t.name), b);
    private static ChatFormatting parseColor(String value) { try { return ChatFormatting.valueOf(value.toUpperCase(Locale.ROOT)); } catch (IllegalArgumentException e) { return null; } }
    private static int fail(CommandContext<CommandSourceStack> c, String key, Object... args) { c.getSource().sendFailure(Component.translatable(key, args)); return 0; }
}
