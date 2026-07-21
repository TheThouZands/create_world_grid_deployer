package dev.thouzands.worldgriddeployer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.thouzands.worldgriddeployer.WorldGridDebugNetworking.DebugSubscriptionPayload;
import dev.thouzands.worldgriddeployer.WorldGridDebugNetworking.OutcomeBatchPayload;
import dev.thouzands.worldgriddeployer.WorldGridDebugNetworking.OutcomeEntry;
import io.netty.buffer.Unpooled;
import java.util.List;
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

    private static RegistryFriendlyByteBuf buffer() {
        return new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
    }
}
