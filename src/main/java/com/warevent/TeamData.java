package com.warevent;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class TeamData extends SavedData {
    public static final String ID = "warevent_teams";
    private final Map<String, WarTeam> teams = new LinkedHashMap<>();

    public static TeamData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(TeamData::new, TeamData::load, null), ID);
    }

    public static TeamData load(CompoundTag tag, net.minecraft.core.HolderLookup.Provider lookup) {
        TeamData data = new TeamData();
        for (Tag entry : tag.getList("teams", Tag.TAG_COMPOUND)) {
            CompoundTag teamTag = (CompoundTag) entry;
            WarTeam team = new WarTeam(teamTag.getString("name"), teamTag.getString("color"));
            for (Tag member : teamTag.getList("members", Tag.TAG_STRING)) {
                team.members.add(UUID.fromString(member.getAsString()));
            }
            data.teams.put(team.key(), team);
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, net.minecraft.core.HolderLookup.Provider lookup) {
        ListTag list = new ListTag();
        for (WarTeam team : teams.values()) {
            CompoundTag teamTag = new CompoundTag();
            teamTag.putString("name", team.name);
            teamTag.putString("color", team.color);
            ListTag members = new ListTag();
            team.members.forEach(uuid -> members.add(StringTag.valueOf(uuid.toString())));
            teamTag.put("members", members);
            list.add(teamTag);
        }
        tag.put("teams", list);
        return tag;
    }

    public WarTeam get(String name) { return teams.get(name.toLowerCase(Locale.ROOT)); }
    public Collection<WarTeam> all() { return teams.values(); }
    public void put(WarTeam team) { teams.put(team.key(), team); setDirty(); }
    public void remove(String name) { teams.remove(name.toLowerCase(Locale.ROOT)); setDirty(); }
    public void removePlayerFromAll(UUID uuid) {
        teams.values().forEach(team -> team.members.remove(uuid));
        setDirty();
    }

    public static final class WarTeam {
        public String name;
        public String color;
        public final List<UUID> members = new ArrayList<>();
        public WarTeam(String name, String color) { this.name = name; this.color = color; }
        public String key() { return name.toLowerCase(Locale.ROOT); }
    }
}
