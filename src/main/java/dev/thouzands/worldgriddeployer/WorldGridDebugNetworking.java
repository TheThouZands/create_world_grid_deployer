package dev.thouzands.worldgriddeployer;

import dev.thouzands.worldgriddeployer.client.WorldGridDebugClient;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
    private static final Set<UUID> SUBSCRIBERS = new HashSet<>();
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
        if (PENDING.isEmpty()) {
            return;
        }

        MinecraftServer server = event.getServer();
        for (Map.Entry<ResourceKey<Level>, List<OutcomeEntry>> pending : PENDING.entrySet()) {
            ServerLevel level = server.getLevel(pending.getKey());
            if (level == null || pending.getValue().isEmpty()) {
                continue;
            }

            OutcomeBatchPayload payload = new OutcomeBatchPayload(pending.getValue());
            for (ServerPlayer player : level.players()) {
                if (SUBSCRIBERS.contains(player.getUUID())) {
                    PacketDistributor.sendToPlayer(player, payload);
                }
            }
        }
        PENDING.clear();
    }

    @SubscribeEvent
    public static void loggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        SUBSCRIBERS.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void serverStopped(ServerStoppedEvent event) {
        SUBSCRIBERS.clear();
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
    }

    private static void handleSubscription(DebugSubscriptionPayload payload, IPayloadContext context) {
        Player player = context.player();
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        if (payload.enabled()) {
            SUBSCRIBERS.add(serverPlayer.getUUID());
        } else {
            SUBSCRIBERS.remove(serverPlayer.getUUID());
        }
    }

    /** This handler is registered on both physical sides but invoked only for a client-bound play payload. */
    private static void handleOutcomesClient(OutcomeBatchPayload payload, IPayloadContext context) {
        WorldGridDebugClient.receiveOutcomes(payload.entries());
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

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(CreateWorldGridDeployer.MOD_ID, path);
    }

    private static boolean hasSubscriber(ServerLevel level) {
        if (SUBSCRIBERS.isEmpty()) {
            return false;
        }
        for (ServerPlayer player : level.players()) {
            if (SUBSCRIBERS.contains(player.getUUID())) {
                return true;
            }
        }
        return false;
    }
}
