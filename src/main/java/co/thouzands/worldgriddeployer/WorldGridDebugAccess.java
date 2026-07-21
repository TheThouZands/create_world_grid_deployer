package co.thouzands.worldgriddeployer;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.context.CommandContext;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.GameProfileCache;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Persistent server policy for the authoritative placement-outcome stream.
 * Client-predicted geometry is intentionally outside this policy because the
 * client already has all of the transforms needed to calculate it locally.
 */
@EventBusSubscriber(modid = CreateWorldGridDeployer.MOD_ID)
public final class WorldGridDebugAccess extends SavedData {
    public static final String COMMAND = "worldgriddeployer_access";
    private static final String DATA_NAME = "worldgriddeployer_debug_access";
    private static final String POLICY_KEY = "Policy";
    private static final String ALLOWED_PLAYERS_KEY = "AllowedPlayers";
    private static final Factory<WorldGridDebugAccess> FACTORY = new Factory<>(
        WorldGridDebugAccess::new,
        WorldGridDebugAccess::load
    );

    private Policy policy = Policy.OPS_ONLY;
    private final Set<UUID> allowedPlayers = new LinkedHashSet<>();

    public WorldGridDebugAccess() {}

    public static WorldGridDebugAccess get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public static boolean canUse(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return false;
        }

        boolean operator = server.getPlayerList().isOp(player.getGameProfile());
        return get(server).allows(player.getUUID(), operator);
    }

    public static Policy policy(MinecraftServer server) {
        return get(server).policy();
    }

    public Policy policy() {
        return this.policy;
    }

    public Set<UUID> allowedPlayers() {
        return Set.copyOf(this.allowedPlayers);
    }

    public boolean allows(UUID playerId, boolean operator) {
        return switch (this.policy) {
            case DISABLED -> false;
            case OPS_ONLY -> operator;
            case WHITELIST -> operator || this.allowedPlayers.contains(playerId);
            case PUBLIC -> true;
        };
    }

    public boolean setPolicy(Policy policy) {
        if (this.policy == policy) {
            return false;
        }
        this.policy = policy;
        this.setDirty();
        return true;
    }

    public boolean allow(UUID playerId) {
        boolean changed = this.allowedPlayers.add(playerId);
        if (changed) {
            this.setDirty();
        }
        return changed;
    }

    public boolean revoke(UUID playerId) {
        boolean changed = this.allowedPlayers.remove(playerId);
        if (changed) {
            this.setDirty();
        }
        return changed;
    }

    public int clearWhitelist() {
        int removed = this.allowedPlayers.size();
        if (removed > 0) {
            this.allowedPlayers.clear();
            this.setDirty();
        }
        return removed;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putString(POLICY_KEY, this.policy.serializedName());
        ListTag players = new ListTag();
        this.allowedPlayers.stream()
            .sorted(Comparator.comparing(UUID::toString))
            .map(NbtUtils::createUUID)
            .forEach(players::add);
        tag.put(ALLOWED_PLAYERS_KEY, players);
        return tag;
    }

    static WorldGridDebugAccess load(CompoundTag tag, HolderLookup.Provider registries) {
        WorldGridDebugAccess access = new WorldGridDebugAccess();
        access.policy = Policy.byName(tag.getString(POLICY_KEY));

        ListTag players = tag.getList(ALLOWED_PLAYERS_KEY, Tag.TAG_INT_ARRAY);
        for (Tag player : players) {
            try {
                access.allowedPlayers.add(NbtUtils.loadUUID(player));
            } catch (IllegalArgumentException exception) {
                CreateWorldGridDeployer.LOGGER.warn("Ignoring malformed world-grid debug whitelist UUID", exception);
            }
        }
        return access;
    }

    @SubscribeEvent
    public static void registerCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(
            Commands.literal(COMMAND)
                .requires(source -> source.hasPermission(3))
                .then(Commands.literal("status").executes(WorldGridDebugAccess::status))
                .then(Commands.literal("mode")
                    .then(Commands.literal("disabled").executes(context -> setPolicy(context, Policy.DISABLED)))
                    .then(Commands.literal("ops").executes(context -> setPolicy(context, Policy.OPS_ONLY)))
                    .then(Commands.literal("whitelist").executes(context -> setPolicy(context, Policy.WHITELIST)))
                    .then(Commands.literal("public").executes(context -> setPolicy(context, Policy.PUBLIC))))
                .then(Commands.literal("allow")
                    .then(Commands.argument("players", GameProfileArgument.gameProfile())
                        .executes(WorldGridDebugAccess::allowPlayers)))
                .then(Commands.literal("revoke")
                    .then(Commands.argument("players", GameProfileArgument.gameProfile())
                        .executes(WorldGridDebugAccess::revokePlayers)))
                .then(Commands.literal("list").executes(WorldGridDebugAccess::listPlayers))
                .then(Commands.literal("clear").executes(WorldGridDebugAccess::clearPlayers))
        );
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        WorldGridDebugAccess access = get(context.getSource().getServer());
        context.getSource().sendSuccess(
            () -> Component.literal(
                "World-grid outcome debugging: " + access.policy().serializedName()
                    + " (" + access.policy().description() + "); whitelist entries=" + access.allowedPlayers.size()
            ),
            false
        );
        return access.allowedPlayers.size();
    }

    private static int setPolicy(CommandContext<CommandSourceStack> context, Policy policy) {
        WorldGridDebugAccess access = get(context.getSource().getServer());
        access.setPolicy(policy);
        context.getSource().sendSuccess(
            () -> Component.literal(
                "World-grid outcome debugging policy set to " + policy.serializedName()
                    + " (" + policy.description() + ")"
            ),
            true
        );
        return policy.ordinal() + 1;
    }

    private static int allowPlayers(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(context, "players");
        WorldGridDebugAccess access = get(context.getSource().getServer());
        int added = 0;
        for (GameProfile profile : profiles) {
            if (profile.getId() != null && access.allow(profile.getId())) {
                added++;
            }
        }
        int result = added;
        context.getSource().sendSuccess(
            () -> Component.literal(
                "Added " + result + " player(s) to the world-grid debug whitelist; policy="
                    + access.policy().serializedName()
            ),
            true
        );
        return added;
    }

    private static int revokePlayers(CommandContext<CommandSourceStack> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(context, "players");
        WorldGridDebugAccess access = get(context.getSource().getServer());
        int removed = 0;
        for (GameProfile profile : profiles) {
            if (profile.getId() != null && access.revoke(profile.getId())) {
                removed++;
            }
        }
        int result = removed;
        context.getSource().sendSuccess(
            () -> Component.literal("Removed " + result + " player(s) from the world-grid debug whitelist"),
            true
        );
        return removed;
    }

    private static int listPlayers(CommandContext<CommandSourceStack> context) {
        MinecraftServer server = context.getSource().getServer();
        WorldGridDebugAccess access = get(server);
        String list = access.allowedPlayers.stream()
            .sorted(Comparator.comparing(UUID::toString))
            .map(playerId -> profileName(server, playerId) + " (" + playerId + ")")
            .collect(Collectors.joining(", "));
        if (list.isEmpty()) {
            list = "<empty>";
        }
        String result = list;
        context.getSource().sendSuccess(() -> Component.literal("World-grid debug whitelist: " + result), false);
        return access.allowedPlayers.size();
    }

    private static int clearPlayers(CommandContext<CommandSourceStack> context) {
        WorldGridDebugAccess access = get(context.getSource().getServer());
        int removed = access.clearWhitelist();
        context.getSource().sendSuccess(
            () -> Component.literal("Cleared " + removed + " world-grid debug whitelist entry/entries"),
            true
        );
        return removed;
    }

    private static String profileName(MinecraftServer server, UUID playerId) {
        ServerPlayer online = server.getPlayerList().getPlayer(playerId);
        if (online != null) {
            return online.getGameProfile().getName();
        }
        GameProfileCache cache = server.getProfileCache();
        if (cache != null) {
            return cache.get(playerId).map(GameProfile::getName).orElse("unknown");
        }
        return "unknown";
    }

    public enum Policy {
        DISABLED("disabled", "nobody"),
        OPS_ONLY("ops", "operators only"),
        WHITELIST("whitelist", "operators and whitelisted players"),
        PUBLIC("public", "all compatible clients");

        private final String serializedName;
        private final String description;

        Policy(String serializedName, String description) {
            this.serializedName = serializedName;
            this.description = description;
        }

        public String serializedName() {
            return this.serializedName;
        }

        public String description() {
            return this.description;
        }

        static Policy byName(String name) {
            String normalized = name.toLowerCase(Locale.ROOT);
            for (Policy policy : values()) {
                if (policy.serializedName.equals(normalized)) {
                    return policy;
                }
            }
            return OPS_ONLY;
        }
    }
}
