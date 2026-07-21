package dev.thouzands.worldgriddeployer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.thouzands.worldgriddeployer.WorldGridDebugHistory.DeployerKey;
import dev.thouzands.worldgriddeployer.WorldGridDebugNetworking.OutcomeEntry;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

class WorldGridDebugHistoryTest {
    private static final DeployerKey KEY = new DeployerKey(UUID.fromString("00000000-0000-0000-0000-000000000001"), 42L);

    @Test
    void liveTargetsExpireWhenTheDeployerStopsReporting() {
        WorldGridDebugHistory history = new WorldGridDebugHistory();
        history.setTargetsEnabled(true);
        history.capture(KEY, new Vec3(1.25, 2.5, 3.75), true);

        assertEquals(1, history.liveTargets().size());
        assertTrue(history.liveTargets().getFirst().powered());

        history.advanceTick();
        history.advanceTick();
        assertEquals(1, history.liveTargets().size());

        history.advanceTick();
        assertTrue(history.liveTargets().isEmpty());
    }

    @Test
    void candidateTrailUsesThePlacementTraversalAndExpiresPerBlock() {
        WorldGridDebugHistory history = new WorldGridDebugHistory();
        history.startBlockTrail(2);

        history.capture(KEY, new Vec3(0.25, 4.25, 8.25), true);
        history.advanceTick();
        history.capture(KEY, new Vec3(2.25, 4.25, 8.25), true);

        assertEquals(
            Set.of(new BlockPos(0, 4, 8), new BlockPos(1, 4, 8), new BlockPos(2, 4, 8)),
            positions(history)
        );

        history.advanceTick();
        history.advanceTick();
        assertEquals(Set.of(new BlockPos(1, 4, 8), new BlockPos(2, 4, 8)), positions(history));

        history.advanceTick();
        assertTrue(history.blockTrail().isEmpty());
    }

    @Test
    void pointPathStartsDisconnectedThenConnectsContinuousSamples() {
        WorldGridDebugHistory history = new WorldGridDebugHistory();
        history.startPointPath(20);

        history.capture(KEY, new Vec3(0.25, 0.25, 0.25), false);
        history.advanceTick();
        history.capture(KEY, new Vec3(0.75, 0.25, 0.25), false);

        var points = history.pointPaths().get(KEY);
        assertEquals(2, points.size());
        assertTrue(points.get(0).breakBefore());
        assertFalse(points.get(1).breakBefore());
    }

    @Test
    void rejectsUnboundedHistoryDurations() {
        WorldGridDebugHistory history = new WorldGridDebugHistory();
        assertThrows(IllegalArgumentException.class, () -> history.startPointPath(0));
        assertThrows(
            IllegalArgumentException.class,
            () -> history.startBlockTrail(WorldGridDebugHistory.MAX_LIFETIME_TICKS + 1)
        );
    }

    @Test
    void authoritativeOutcomesOverwritePerCellAndExpire() {
        WorldGridDebugHistory history = new WorldGridDebugHistory();
        BlockPos position = new BlockPos(7, 8, 9);
        history.startOutcomes(2);

        history.recordOutcomes(List.of(new OutcomeEntry(position, WorldGridPlacementOutcome.CREATE_REJECTED)));
        history.recordOutcomes(List.of(new OutcomeEntry(position, WorldGridPlacementOutcome.PLACED)));

        assertEquals(1, history.outcomes().size());
        assertEquals(WorldGridPlacementOutcome.PLACED, history.outcomes().getFirst().outcome());

        history.advanceTick();
        history.advanceTick();
        assertEquals(1, history.outcomes().size());
        history.advanceTick();
        assertTrue(history.outcomes().isEmpty());
    }

    private static Set<BlockPos> positions(WorldGridDebugHistory history) {
        return history.blockTrail().stream()
            .map(WorldGridDebugHistory.TimedBlock::position)
            .collect(Collectors.toSet());
    }
}
