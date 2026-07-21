package co.thouzands.worldgriddeployer;

import co.thouzands.worldgriddeployer.client.WorldGridDebugClient;
import co.thouzands.worldgriddeployer.client.WorldGridSettingsClient;
import com.mojang.authlib.GameProfile;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.ChatFormatting;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.GameProfileCache;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Opt-in server-authoritative placement diagnostics. Results are accumulated
 * during deployer ticks and sent as one small batch per dimension per server
 * tick, avoiding a packet for every crossed block.
 */
@EventBusSubscriber(modid = CreateWorldGridDeployer.MOD_ID)
public final class WorldGridDebugNetworking {
    private static final int MAX_OUTCOMES_PER_DIMENSION_TICK = 4096;
    private static final int MAX_OUTCOMES_PER_PACKET = 4096;
    private static final int MAX_WHITELIST_ENTRIES = 256;
    private static final int MAX_ADDED_NAMES = 32;
    private static final int MAX_SUGGESTIONS = 256;
    private static final int MAX_PLAYER_NAME_LENGTH = 64;
    private static final Set<UUID> REQUESTED_SUBSCRIBERS = new HashSet<>();
    private static final Set<UUID> ACTIVE_SUBSCRIBERS = new HashSet<>();
    private static final Map<ResourceKey<Level>, List<OutcomeEntry>> PENDING = new HashMap<>();

    private WorldGridDebugNetworking() {}

    public static void emit(ServerLevel level, BlockPos position, WorldGridPlacementOutcome outcome) {
        if (!hasSubscriber(level)) {
            return;
        }

        List<OutcomeEntry> entries = PENDING.computeIfAbsent(level.dimension(), ignored -> new ArrayList<>());
        if (entries.size() < MAX_OUTCOMES_PER_DIMENSION_TICK) {
            entries.add(new OutcomeEntry(position.immutable(), outcome));
        }
    }

    @SubscribeEvent
    public static void flush(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        refreshAuthorization(server);
        if (PENDING.isEmpty()) {
            return;
        }

        for (Map.Entry<ResourceKey<Level>, List<OutcomeEntry>> pending : PENDING.entrySet()) {
            ServerLevel level = server.getLevel(pending.getKey());
            if (level == null || pending.getValue().isEmpty()) {
                continue;
            }

            OutcomeBatchPayload payload = new OutcomeBatchPayload(pending.getValue());
            for (ServerPlayer player : level.players()) {
                if (ACTIVE_SUBSCRIBERS.contains(player.getUUID())) {
                    PacketDistributor.sendToPlayer(player, payload);
                }
            }
        }
        PENDING.clear();
    }

    @SubscribeEvent
    public static void loggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        REQUESTED_SUBSCRIBERS.remove(event.getEntity().getUUID());
        ACTIVE_SUBSCRIBERS.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void serverStopped(ServerStoppedEvent event) {
        REQUESTED_SUBSCRIBERS.clear();
        ACTIVE_SUBSCRIBERS.clear();
        PENDING.clear();
    }

    public static void registerPayloadHandlers(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1").optional();
        registrar.playToServer(
            DebugSubscriptionPayload.TYPE,
            DebugSubscriptionPayload.STREAM_CODEC,
            WorldGridDebugNetworking::handleSubscription
        );
        registrar.playToClient(
            OutcomeBatchPayload.TYPE,
            OutcomeBatchPayload.STREAM_CODEC,
            WorldGridDebugNetworking::handleOutcomesClient
        );
        registrar.playToServer(
            SettingsRequestPayload.TYPE,
            SettingsRequestPayload.STREAM_CODEC,
            WorldGridDebugNetworking::handleSettingsRequest
        );
        registrar.playToServer(
            SettingsUpdatePayload.TYPE,
            SettingsUpdatePayload.STREAM_CODEC,
            WorldGridDebugNetworking::handleSettingsUpdate
        );
        registrar.playToClient(
            SettingsSnapshotPayload.TYPE,
            SettingsSnapshotPayload.STREAM_CODEC,
            WorldGridDebugNetworking::handleSettingsSnapshotClient
        );
    }

    private static void handleSubscription(DebugSubscriptionPayload payload, IPayloadContext context) {
        Player player = context.player();
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        if (payload.enabled()) {
            boolean newlyRequested = REQUESTED_SUBSCRIBERS.add(serverPlayer.getUUID());
            if (WorldGridDebugAccess.canUse(serverPlayer)) {
                ACTIVE_SUBSCRIBERS.add(serverPlayer.getUUID());
                if (newlyRequested) {
                    serverPlayer.sendSystemMessage(
                        Component.literal("World-grid server outcome debugging enabled")
                            .withStyle(ChatFormatting.GREEN)
                    );
                }
            } else {
                ACTIVE_SUBSCRIBERS.remove(serverPlayer.getUUID());
                if (newlyRequested) {
                    serverPlayer.sendSystemMessage(
                        Component.literal(
                            "World-grid server outcome debugging is private; request retained pending access"
                        ).withStyle(ChatFormatting.RED)
                    );
                }
            }
        } else {
            REQUESTED_SUBSCRIBERS.remove(serverPlayer.getUUID());
            ACTIVE_SUBSCRIBERS.remove(serverPlayer.getUUID());
        }
    }

    private static void refreshAuthorization(MinecraftServer server) {
        ACTIVE_SUBSCRIBERS.clear();
        for (UUID playerId : REQUESTED_SUBSCRIBERS) {
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player != null && WorldGridDebugAccess.canUse(player)) {
                ACTIVE_SUBSCRIBERS.add(playerId);
            }
        }
    }

    /** This handler is registered on both physical sides but invoked only for a client-bound play payload. */
    private static void handleOutcomesClient(OutcomeBatchPayload payload, IPayloadContext context) {
        WorldGridDebugClient.receiveOutcomes(payload.entries());
    }

    private static void handleSettingsRequest(SettingsRequestPayload payload, IPayloadContext context) {
        if (context.player() instanceof ServerPlayer serverPlayer) {
            context.enqueueWork(() -> sendSettingsSnapshot(serverPlayer, SettingsResult.NONE, ""));
        }
    }

    private static void handleSettingsUpdate(SettingsUpdatePayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer serverPlayer)) {
            return;
        }

        context.enqueueWork(() -> {
            MinecraftServer server = serverPlayer.getServer();
            if (server == null) {
                return;
            }

            WorldGridDebugAccess access = WorldGridDebugAccess.get(server);
            if (!serverPlayer.hasPermissions(3)) {
                sendSettingsSnapshot(serverPlayer, SettingsResult.NO_PERMISSION, "");
                return;
            }
            if (payload.expectedRevision() != access.revision()) {
                sendSettingsSnapshot(serverPlayer, SettingsResult.STALE, "");
                return;
            }

            Optional<WorldGridDebugAccess.Policy> policy = WorldGridDebugAccess.Policy.find(payload.policy());
            if (policy.isEmpty()) {
                sendSettingsSnapshot(serverPlayer, SettingsResult.INVALID, "policy");
                return;
            }

            Set<UUID> current = access.allowedPlayers();
            LinkedHashSet<UUID> replacement = new LinkedHashSet<>(payload.retainedPlayers());
            if (!current.containsAll(replacement)) {
                sendSettingsSnapshot(serverPlayer, SettingsResult.INVALID, "whitelist");
                return;
            }

            for (String name : payload.addedPlayers()) {
                Optional<GameProfile> profile = resolveProfile(server, name);
                if (profile.isEmpty() || profile.get().getId() == null) {
                    sendSettingsSnapshot(serverPlayer, SettingsResult.UNKNOWN_PLAYER, name);
                    return;
                }
                replacement.add(profile.get().getId());
            }
            if (replacement.size() > MAX_WHITELIST_ENTRIES) {
                sendSettingsSnapshot(serverPlayer, SettingsResult.INVALID, "whitelist_size");
                return;
            }

            access.replaceSettings(policy.get(), replacement);
            refreshAuthorization(server);
            sendSettingsSnapshot(serverPlayer, SettingsResult.SAVED, "");
        });
    }

    private static void handleSettingsSnapshotClient(SettingsSnapshotPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> WorldGridSettingsClient.receive(payload));
    }

    private static Optional<GameProfile> resolveProfile(MinecraftServer server, String name) {
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null) {
            return Optional.of(online.getGameProfile());
        }
        GameProfileCache cache = server.getProfileCache();
        return cache == null ? Optional.empty() : cache.get(name);
    }

    private static void sendSettingsSnapshot(ServerPlayer player, SettingsResult result, String detail) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }

        WorldGridDebugAccess access = WorldGridDebugAccess.get(server);
        List<PlayerEntry> whitelist = access.allowedPlayers().stream()
            .map(playerId -> new PlayerEntry(playerId, WorldGridDebugAccess.profileName(server, playerId)))
            .sorted(Comparator.comparing(PlayerEntry::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(entry -> entry.id().toString()))
            .toList();

        LinkedHashSet<String> suggestions = new LinkedHashSet<>();
        server.getPlayerList().getPlayers().stream()
            .map(serverPlayer -> serverPlayer.getGameProfile().getName())
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .forEach(suggestions::add);
        whitelist.stream().map(PlayerEntry::name).forEach(suggestions::add);

        PacketDistributor.sendToPlayer(player, new SettingsSnapshotPayload(
            access.revision(),
            player.hasPermissions(3),
            access.policy().serializedName(),
            whitelist,
            suggestions.stream().limit(MAX_SUGGESTIONS).toList(),
            result.serializedName(),
            detail
        ));
    }

    public record DebugSubscriptionPayload(boolean enabled) implements CustomPacketPayload {
        public static final Type<DebugSubscriptionPayload> TYPE = new Type<>(id("debug_subscription"));
        public static final StreamCodec<RegistryFriendlyByteBuf, DebugSubscriptionPayload> STREAM_CODEC =
            CustomPacketPayload.codec(DebugSubscriptionPayload::write, DebugSubscriptionPayload::new);

        private DebugSubscriptionPayload(RegistryFriendlyByteBuf buffer) {
            this(buffer.readBoolean());
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeBoolean(this.enabled);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record OutcomeBatchPayload(List<OutcomeEntry> entries) implements CustomPacketPayload {
        public static final Type<OutcomeBatchPayload> TYPE = new Type<>(id("placement_outcomes"));
        public static final StreamCodec<RegistryFriendlyByteBuf, OutcomeBatchPayload> STREAM_CODEC =
            CustomPacketPayload.codec(OutcomeBatchPayload::write, OutcomeBatchPayload::new);

        public OutcomeBatchPayload {
            entries = List.copyOf(entries);
            if (entries.size() > MAX_OUTCOMES_PER_PACKET) {
                throw new IllegalArgumentException("Too many placement outcomes in one packet");
            }
        }

        private OutcomeBatchPayload(RegistryFriendlyByteBuf buffer) {
            this(readEntries(buffer));
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeVarInt(this.entries.size());
            for (OutcomeEntry entry : this.entries) {
                buffer.writeLong(entry.position().asLong());
                buffer.writeByte(entry.outcome().ordinal());
            }
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record OutcomeEntry(BlockPos position, WorldGridPlacementOutcome outcome) {}

    public record SettingsRequestPayload() implements CustomPacketPayload {
        public static final Type<SettingsRequestPayload> TYPE = new Type<>(id("settings_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SettingsRequestPayload> STREAM_CODEC =
            CustomPacketPayload.codec(SettingsRequestPayload::write, SettingsRequestPayload::new);

        private SettingsRequestPayload(RegistryFriendlyByteBuf buffer) {
            this();
        }

        private void write(RegistryFriendlyByteBuf buffer) {}

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SettingsUpdatePayload(
        long expectedRevision,
        String policy,
        List<UUID> retainedPlayers,
        List<String> addedPlayers
    ) implements CustomPacketPayload {
        public static final Type<SettingsUpdatePayload> TYPE = new Type<>(id("settings_update"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SettingsUpdatePayload> STREAM_CODEC =
            CustomPacketPayload.codec(SettingsUpdatePayload::write, SettingsUpdatePayload::new);

        public SettingsUpdatePayload {
            policy = checkedString(policy, 16, "policy");
            retainedPlayers = checkedUuids(retainedPlayers, MAX_WHITELIST_ENTRIES, "retained players");
            addedPlayers = checkedStrings(addedPlayers, MAX_ADDED_NAMES, MAX_PLAYER_NAME_LENGTH, "added players");
        }

        private SettingsUpdatePayload(RegistryFriendlyByteBuf buffer) {
            this(
                buffer.readVarLong(),
                buffer.readUtf(16),
                readUuids(buffer, MAX_WHITELIST_ENTRIES),
                readStrings(buffer, MAX_ADDED_NAMES, MAX_PLAYER_NAME_LENGTH)
            );
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeVarLong(this.expectedRevision);
            buffer.writeUtf(this.policy, 16);
            writeUuids(buffer, this.retainedPlayers);
            writeStrings(buffer, this.addedPlayers, MAX_PLAYER_NAME_LENGTH);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SettingsSnapshotPayload(
        long revision,
        boolean editable,
        String policy,
        List<PlayerEntry> whitelist,
        List<String> suggestions,
        String result,
        String detail
    ) implements CustomPacketPayload {
        public static final Type<SettingsSnapshotPayload> TYPE = new Type<>(id("settings_snapshot"));
        public static final StreamCodec<RegistryFriendlyByteBuf, SettingsSnapshotPayload> STREAM_CODEC =
            CustomPacketPayload.codec(SettingsSnapshotPayload::write, SettingsSnapshotPayload::new);

        public SettingsSnapshotPayload {
            policy = checkedString(policy, 16, "policy");
            whitelist = checkedPlayers(whitelist);
            suggestions = checkedStrings(suggestions, MAX_SUGGESTIONS, MAX_PLAYER_NAME_LENGTH, "suggestions");
            result = checkedString(result, 24, "result");
            detail = checkedString(detail, MAX_PLAYER_NAME_LENGTH, "detail");
        }

        private SettingsSnapshotPayload(RegistryFriendlyByteBuf buffer) {
            this(
                buffer.readVarLong(),
                buffer.readBoolean(),
                buffer.readUtf(16),
                readPlayers(buffer),
                readStrings(buffer, MAX_SUGGESTIONS, MAX_PLAYER_NAME_LENGTH),
                buffer.readUtf(24),
                buffer.readUtf(MAX_PLAYER_NAME_LENGTH)
            );
        }

        private void write(RegistryFriendlyByteBuf buffer) {
            buffer.writeVarLong(this.revision);
            buffer.writeBoolean(this.editable);
            buffer.writeUtf(this.policy, 16);
            buffer.writeVarInt(this.whitelist.size());
            for (PlayerEntry entry : this.whitelist) {
                buffer.writeUUID(entry.id());
                buffer.writeUtf(entry.name(), MAX_PLAYER_NAME_LENGTH);
            }
            writeStrings(buffer, this.suggestions, MAX_PLAYER_NAME_LENGTH);
            buffer.writeUtf(this.result, 24);
            buffer.writeUtf(this.detail, MAX_PLAYER_NAME_LENGTH);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record PlayerEntry(UUID id, String name) {
        public PlayerEntry {
            if (id == null) {
                throw new IllegalArgumentException("Player UUID is required");
            }
            name = checkedString(name, MAX_PLAYER_NAME_LENGTH, "player name");
        }
    }

    public enum SettingsResult {
        NONE("none"),
        SAVED("saved"),
        STALE("stale"),
        NO_PERMISSION("no_permission"),
        UNKNOWN_PLAYER("unknown_player"),
        INVALID("invalid");

        private final String serializedName;

        SettingsResult(String serializedName) {
            this.serializedName = serializedName;
        }

        public String serializedName() {
            return this.serializedName;
        }
    }

    private static List<OutcomeEntry> readEntries(RegistryFriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        if (size < 0 || size > MAX_OUTCOMES_PER_PACKET) {
            throw new IllegalArgumentException("Invalid placement outcome count: " + size);
        }

        List<OutcomeEntry> entries = new ArrayList<>(size);
        WorldGridPlacementOutcome[] outcomes = WorldGridPlacementOutcome.values();
        for (int index = 0; index < size; index++) {
            BlockPos position = BlockPos.of(buffer.readLong());
            int outcomeId = buffer.readUnsignedByte();
            if (outcomeId >= outcomes.length) {
                throw new IllegalArgumentException("Invalid placement outcome id: " + outcomeId);
            }
            entries.add(new OutcomeEntry(position, outcomes[outcomeId]));
        }
        return entries;
    }

    private static List<UUID> readUuids(RegistryFriendlyByteBuf buffer, int maximum) {
        int size = checkedSize(buffer.readVarInt(), maximum, "UUID list");
        List<UUID> values = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            values.add(buffer.readUUID());
        }
        return List.copyOf(values);
    }

    private static void writeUuids(RegistryFriendlyByteBuf buffer, List<UUID> values) {
        buffer.writeVarInt(values.size());
        values.forEach(buffer::writeUUID);
    }

    private static List<String> readStrings(RegistryFriendlyByteBuf buffer, int maximum, int maximumLength) {
        int size = checkedSize(buffer.readVarInt(), maximum, "string list");
        List<String> values = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            values.add(buffer.readUtf(maximumLength));
        }
        return List.copyOf(values);
    }

    private static void writeStrings(RegistryFriendlyByteBuf buffer, List<String> values, int maximumLength) {
        buffer.writeVarInt(values.size());
        values.forEach(value -> buffer.writeUtf(value, maximumLength));
    }

    private static List<PlayerEntry> readPlayers(RegistryFriendlyByteBuf buffer) {
        int size = checkedSize(buffer.readVarInt(), MAX_WHITELIST_ENTRIES, "player list");
        List<PlayerEntry> players = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            players.add(new PlayerEntry(buffer.readUUID(), buffer.readUtf(MAX_PLAYER_NAME_LENGTH)));
        }
        return List.copyOf(players);
    }

    private static List<PlayerEntry> checkedPlayers(List<PlayerEntry> players) {
        checkedSize(players.size(), MAX_WHITELIST_ENTRIES, "player list");
        return List.copyOf(players);
    }

    private static List<UUID> checkedUuids(List<UUID> values, int maximum, String field) {
        checkedSize(values.size(), maximum, field);
        if (values.stream().anyMatch(value -> value == null)) {
            throw new IllegalArgumentException(field + " contains null");
        }
        return List.copyOf(values);
    }

    private static List<String> checkedStrings(List<String> values, int maximum, int maximumLength, String field) {
        checkedSize(values.size(), maximum, field);
        return values.stream().map(value -> checkedString(value, maximumLength, field)).toList();
    }

    private static String checkedString(String value, int maximumLength, String field) {
        if (value == null || value.length() > maximumLength) {
            throw new IllegalArgumentException("Invalid " + field);
        }
        return value;
    }

    private static int checkedSize(int size, int maximum, String field) {
        if (size < 0 || size > maximum) {
            throw new IllegalArgumentException("Invalid " + field + " size: " + size);
        }
        return size;
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(CreateWorldGridDeployer.MOD_ID, path);
    }

    private static boolean hasSubscriber(ServerLevel level) {
        if (ACTIVE_SUBSCRIBERS.isEmpty()) {
            return false;
        }
        for (ServerPlayer player : level.players()) {
            if (ACTIVE_SUBSCRIBERS.contains(player.getUUID())) {
                return true;
            }
        }
        return false;
    }
}
