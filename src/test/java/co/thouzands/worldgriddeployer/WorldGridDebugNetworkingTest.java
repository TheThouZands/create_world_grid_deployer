package co.thouzands.worldgriddeployer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import co.thouzands.worldgriddeployer.WorldGridDebugNetworking.DebugSubscriptionPayload;
import co.thouzands.worldgriddeployer.WorldGridDebugNetworking.OutcomeBatchPayload;
import co.thouzands.worldgriddeployer.WorldGridDebugNetworking.OutcomeEntry;
import co.thouzands.worldgriddeployer.WorldGridDebugNetworking.PlayerEntry;
import co.thouzands.worldgriddeployer.WorldGridDebugNetworking.NameLookupRequestPayload;
import co.thouzands.worldgriddeployer.WorldGridDebugNetworking.NameLookupResultPayload;
import co.thouzands.worldgriddeployer.WorldGridDebugNetworking.NameLookupStatus;
import co.thouzands.worldgriddeployer.WorldGridDebugNetworking.SettingsRequestPayload;
import co.thouzands.worldgriddeployer.WorldGridDebugNetworking.SettingsSnapshotPayload;
import co.thouzands.worldgriddeployer.WorldGridDebugNetworking.SettingsUpdatePayload;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import org.junit.jupiter.api.Test;

class WorldGridDebugNetworkingTest {
    @Test
    void roundTripsSubscriptionPayload() {
        RegistryFriendlyByteBuf buffer = buffer();
        try {
            DebugSubscriptionPayload.STREAM_CODEC.encode(buffer, new DebugSubscriptionPayload(true));
            assertEquals(new DebugSubscriptionPayload(true), DebugSubscriptionPayload.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void roundTripsBatchedOutcomePayload() {
        OutcomeBatchPayload expected = new OutcomeBatchPayload(List.of(
            new OutcomeEntry(new BlockPos(-12, 64, 37), WorldGridPlacementOutcome.PLACED),
            new OutcomeEntry(new BlockPos(4, -20, 9), WorldGridPlacementOutcome.CREATE_REJECTED),
            new OutcomeEntry(new BlockPos(0, 0, 0), WorldGridPlacementOutcome.NO_BLOCK_ITEM)
        ));
        RegistryFriendlyByteBuf buffer = buffer();
        try {
            OutcomeBatchPayload.STREAM_CODEC.encode(buffer, expected);
            assertEquals(expected, OutcomeBatchPayload.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void roundTripsSettingsRequestPayload() {
        RegistryFriendlyByteBuf buffer = buffer();
        try {
            SettingsRequestPayload.STREAM_CODEC.encode(buffer, new SettingsRequestPayload());
            assertEquals(new SettingsRequestPayload(), SettingsRequestPayload.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void roundTripsSettingsUpdatePayload() {
        SettingsUpdatePayload expected = new SettingsUpdatePayload(
            37L,
            "whitelist",
            List.of(UUID.randomUUID(), UUID.randomUUID()),
            List.of("FuturePlayer"),
            List.of("Angus", "Julianca")
        );
        RegistryFriendlyByteBuf buffer = buffer();
        try {
            SettingsUpdatePayload.STREAM_CODEC.encode(buffer, expected);
            assertEquals(expected, SettingsUpdatePayload.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void roundTripsSettingsSnapshotPayload() {
        SettingsSnapshotPayload expected = new SettingsSnapshotPayload(
            82L,
            true,
            "ops",
            List.of(new PlayerEntry(UUID.randomUUID(), "thethouzands")),
            List.of("FuturePlayer"),
            List.of("Angus", "Julianca", "thethouzands"),
            "unknown_player",
            "NotCached"
        );
        RegistryFriendlyByteBuf buffer = buffer();
        try {
            SettingsSnapshotPayload.STREAM_CODEC.encode(buffer, expected);
            assertEquals(expected, SettingsSnapshotPayload.STREAM_CODEC.decode(buffer));
        } finally {
            buffer.release();
        }
    }

    @Test
    void roundTripsNameLookupPayloads() {
        assertEquals(NameLookupStatus.SERVER_KNOWN, NameLookupStatus.byName("server_known"));
        RegistryFriendlyByteBuf requestBuffer = buffer();
        try {
            NameLookupRequestPayload request = new NameLookupRequestPayload("FuturePlayer");
            NameLookupRequestPayload.STREAM_CODEC.encode(requestBuffer, request);
            assertEquals(request, NameLookupRequestPayload.STREAM_CODEC.decode(requestBuffer));
        } finally {
            requestBuffer.release();
        }

        RegistryFriendlyByteBuf resultBuffer = buffer();
        try {
            NameLookupResultPayload result = new NameLookupResultPayload(
                "futureplayer",
                "found",
                "FuturePlayer"
            );
            NameLookupResultPayload.STREAM_CODEC.encode(resultBuffer, result);
            assertEquals(result, NameLookupResultPayload.STREAM_CODEC.decode(resultBuffer));
        } finally {
            resultBuffer.release();
        }
    }

    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
    }
}
