package co.thouzands.worldgriddeployer;

import co.thouzands.worldgriddeployer.FaceConnectedVoxelTraversal.Step;
import co.thouzands.worldgriddeployer.WorldGridDebugNetworking.OutcomeEntry;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Client-local history behind the deployer debug overlays. It deliberately
 * consumes the same voxel traversal as placement, so the candidate-block view
 * describes cells the server-side sweep would visit rather than a second
 * approximation made by the renderer.
 */
public final class WorldGridDebugHistory {
    public static final int DEFAULT_LIFETIME_TICKS = 200;
    public static final int MAX_LIFETIME_TICKS = 12_000;

    private static final int LIVE_TARGET_LIFETIME_TICKS = 2;
    private static final int MAX_CELLS_PER_TICK = 64;
    private static final int MAX_HISTORY_ENTRIES = 100_000;
    private static final double MAX_SWEEP_DISTANCE_SQUARED = 64.0 * 64.0;
    private static final double POINT_EPSILON_SQUARED = 1.0e-10;

    private final Map<DeployerKey, LiveTarget> liveTargets = new HashMap<>();
    private final Map<DeployerKey, Deque<TimedPoint>> pointPaths = new HashMap<>();
    private final LinkedHashMap<BlockPos, TimedBlock> blockTrail = new LinkedHashMap<>();
    private final LinkedHashMap<BlockPos, TimedOutcome> outcomes = new LinkedHashMap<>();
    private final Map<DeployerKey, LastSample> lastSamples = new HashMap<>();

    private boolean targetsEnabled;
    private boolean pointPathEnabled;
    private boolean blockTrailEnabled;
    private boolean outcomesEnabled;
    private int pointLifetimeTicks = DEFAULT_LIFETIME_TICKS;
    private int blockLifetimeTicks = DEFAULT_LIFETIME_TICKS;
    private int outcomeLifetimeTicks = DEFAULT_LIFETIME_TICKS;
    private long tick;
    private int pointCount;

    public boolean isCapturing() {
        return this.needsLocalCapture() || this.outcomesEnabled;
    }

    public boolean needsLocalCapture() {
        return this.targetsEnabled || this.pointPathEnabled || this.blockTrailEnabled;
    }

    public boolean targetsEnabled() {
        return this.targetsEnabled;
    }

    public boolean pointPathEnabled() {
        return this.pointPathEnabled;
    }

    public boolean blockTrailEnabled() {
        return this.blockTrailEnabled;
    }

    public boolean outcomesEnabled() {
        return this.outcomesEnabled;
    }

    public int pointLifetimeTicks() {
        return this.pointLifetimeTicks;
    }

    public int blockLifetimeTicks() {
        return this.blockLifetimeTicks;
    }

    public int outcomeLifetimeTicks() {
        return this.outcomeLifetimeTicks;
    }

    public long tick() {
        return this.tick;
    }

    public void setTargetsEnabled(boolean enabled) {
        this.targetsEnabled = enabled;
        if (!enabled) {
            this.liveTargets.clear();
        }
    }

    /** Starts a fresh point history, matching the command's "since call" semantics. */
    public void startPointPath(int lifetimeTicks) {
        this.pointLifetimeTicks = checkedLifetime(lifetimeTicks);
        this.pointPathEnabled = true;
        this.clearPointPath();
    }

    public void stopPointPath() {
        this.pointPathEnabled = false;
        this.clearPointPath();
        this.clearUnusedLastSamples();
    }

    /** Starts a fresh block history, matching the command's "since call" semantics. */
    public void startBlockTrail(int lifetimeTicks) {
        this.blockLifetimeTicks = checkedLifetime(lifetimeTicks);
        this.blockTrailEnabled = true;
        this.clearBlockTrail();
    }

    public void stopBlockTrail() {
        this.blockTrailEnabled = false;
        this.clearBlockTrail();
        this.clearUnusedLastSamples();
    }

    public void startOutcomes(int lifetimeTicks) {
        this.outcomeLifetimeTicks = checkedLifetime(lifetimeTicks);
        this.outcomesEnabled = true;
        this.clearOutcomes();
    }

    public void stopOutcomes() {
        this.outcomesEnabled = false;
        this.clearOutcomes();
    }

    public void capture(DeployerKey key, Vec3 target, boolean powered) {
        if (!this.needsLocalCapture() || !isFinite(target)) {
            return;
        }

        BlockPos targetBlock = BlockPos.containing(target);
        if (this.targetsEnabled) {
            this.liveTargets.put(key, new LiveTarget(target, targetBlock, powered, this.tick));
        }

        if (!this.pointPathEnabled && !this.blockTrailEnabled) {
            return;
        }

        LastSample previous = this.lastSamples.get(key);
        boolean discontinuity = previous == null
            || this.tick - previous.tick() > LIVE_TARGET_LIFETIME_TICKS
            || previous.target().distanceToSqr(target) > MAX_SWEEP_DISTANCE_SQUARED;

        if (this.pointPathEnabled) {
            this.capturePoint(key, target, discontinuity);
        }
        if (this.blockTrailEnabled) {
            this.captureBlocks(previous, target, targetBlock, discontinuity);
        }

        this.lastSamples.put(key, new LastSample(target, this.tick));
        this.trimToLimits();
    }

    public void advanceTick() {
        this.tick++;
        this.purgeExpired();
    }

    public List<LiveTarget> liveTargets() {
        return List.copyOf(this.liveTargets.values());
    }

    public Map<DeployerKey, List<TimedPoint>> pointPaths() {
        Map<DeployerKey, List<TimedPoint>> copy = new HashMap<>();
        this.pointPaths.forEach((key, points) -> copy.put(key, List.copyOf(points)));
        return Map.copyOf(copy);
    }

    public List<TimedBlock> blockTrail() {
        return List.copyOf(this.blockTrail.values());
    }

    public List<TimedOutcome> outcomes() {
        return List.copyOf(this.outcomes.values());
    }

    public void recordOutcomes(List<OutcomeEntry> entries) {
        if (!this.outcomesEnabled) {
            return;
        }
        for (OutcomeEntry entry : entries) {
            BlockPos position = entry.position().immutable();
            this.outcomes.remove(position);
            this.outcomes.put(position, new TimedOutcome(position, entry.outcome(), this.tick));
        }
        while (this.outcomes.size() > MAX_HISTORY_ENTRIES) {
            Iterator<BlockPos> positions = this.outcomes.keySet().iterator();
            positions.next();
            positions.remove();
        }
    }

    public void clearPointPath() {
        this.pointPaths.clear();
        this.pointCount = 0;
    }

    public void clearBlockTrail() {
        this.blockTrail.clear();
    }

    public void clearOutcomes() {
        this.outcomes.clear();
    }

    /** Clears collected geometry while retaining which categories are enabled. */
    public void clearData() {
        this.liveTargets.clear();
        this.clearPointPath();
        this.clearBlockTrail();
        this.clearOutcomes();
        this.lastSamples.clear();
    }

    /**
     * Releases all state owned by the current client connection. Debugging is
     * intentionally opt-in again after joining another world or server.
     */
    public void resetSession() {
        this.targetsEnabled = false;
        this.pointPathEnabled = false;
        this.blockTrailEnabled = false;
        this.outcomesEnabled = false;
        this.pointLifetimeTicks = DEFAULT_LIFETIME_TICKS;
        this.blockLifetimeTicks = DEFAULT_LIFETIME_TICKS;
        this.outcomeLifetimeTicks = DEFAULT_LIFETIME_TICKS;
        this.tick = 0L;
        this.clearData();
    }

    private void capturePoint(DeployerKey key, Vec3 target, boolean discontinuity) {
        Deque<TimedPoint> points = this.pointPaths.computeIfAbsent(key, ignored -> new ArrayDeque<>());
        TimedPoint last = points.peekLast();
        if (last != null && last.position().distanceToSqr(target) <= POINT_EPSILON_SQUARED) {
            points.removeLast();
            points.addLast(new TimedPoint(target, this.tick, last.breakBefore()));
            return;
        }

        points.addLast(new TimedPoint(target, this.tick, discontinuity || last == null));
        this.pointCount++;
    }

    private void captureBlocks(LastSample previous, Vec3 target, BlockPos targetBlock, boolean discontinuity) {
        if (discontinuity) {
            this.recordBlock(targetBlock);
            return;
        }

        List<Step> crossed = FaceConnectedVoxelTraversal.trace(
            previous.target().x,
            previous.target().y,
            previous.target().z,
            target.x,
            target.y,
            target.z,
            MAX_CELLS_PER_TICK
        );
        for (Step step : crossed) {
            this.recordBlock(new BlockPos(step.x(), step.y(), step.z()));
        }
    }

    private void recordBlock(BlockPos position) {
        BlockPos immutable = position.immutable();
        this.blockTrail.remove(immutable);
        this.blockTrail.put(immutable, new TimedBlock(immutable, this.tick));
    }

    private void purgeExpired() {
        this.liveTargets.values().removeIf(target -> this.tick - target.lastSeenTick() > LIVE_TARGET_LIFETIME_TICKS);

        if (this.pointPathEnabled) {
            Iterator<Map.Entry<DeployerKey, Deque<TimedPoint>>> paths = this.pointPaths.entrySet().iterator();
            while (paths.hasNext()) {
                Deque<TimedPoint> points = paths.next().getValue();
                while (!points.isEmpty() && this.tick - points.peekFirst().createdTick() > this.pointLifetimeTicks) {
                    points.removeFirst();
                    this.pointCount--;
                }
                if (points.isEmpty()) {
                    paths.remove();
                }
            }
        }

        if (this.blockTrailEnabled) {
            this.blockTrail.values().removeIf(block -> this.tick - block.createdTick() > this.blockLifetimeTicks);
        }

        if (this.outcomesEnabled) {
            this.outcomes.values().removeIf(outcome -> this.tick - outcome.createdTick() > this.outcomeLifetimeTicks);
        }

        this.lastSamples.entrySet().removeIf(entry -> this.tick - entry.getValue().tick() > LIVE_TARGET_LIFETIME_TICKS);
    }

    private void trimToLimits() {
        while (this.pointCount > MAX_HISTORY_ENTRIES) {
            DeployerKey oldestKey = null;
            TimedPoint oldest = null;
            for (Map.Entry<DeployerKey, Deque<TimedPoint>> entry : this.pointPaths.entrySet()) {
                TimedPoint candidate = entry.getValue().peekFirst();
                if (candidate != null && (oldest == null || candidate.createdTick() < oldest.createdTick())) {
                    oldest = candidate;
                    oldestKey = entry.getKey();
                }
            }
            if (oldestKey == null) {
                break;
            }
            Deque<TimedPoint> points = this.pointPaths.get(oldestKey);
            points.removeFirst();
            this.pointCount--;
            if (points.isEmpty()) {
                this.pointPaths.remove(oldestKey);
            }
        }

        while (this.blockTrail.size() > MAX_HISTORY_ENTRIES) {
            Iterator<BlockPos> positions = this.blockTrail.keySet().iterator();
            positions.next();
            positions.remove();
        }
    }

    private void clearUnusedLastSamples() {
        if (!this.pointPathEnabled && !this.blockTrailEnabled) {
            this.lastSamples.clear();
        }
    }

    private static int checkedLifetime(int lifetimeTicks) {
        if (lifetimeTicks < 1 || lifetimeTicks > MAX_LIFETIME_TICKS) {
            throw new IllegalArgumentException("Lifetime must be between 1 and " + MAX_LIFETIME_TICKS + " ticks");
        }
        return lifetimeTicks;
    }

    private static boolean isFinite(Vec3 point) {
        return Double.isFinite(point.x) && Double.isFinite(point.y) && Double.isFinite(point.z);
    }

    public record DeployerKey(UUID subLevelId, long localPosition) {}

    public record LiveTarget(Vec3 point, BlockPos block, boolean powered, long lastSeenTick) {}

    public record TimedPoint(Vec3 position, long createdTick, boolean breakBefore) {}

    public record TimedBlock(BlockPos position, long createdTick) {}

    public record TimedOutcome(BlockPos position, WorldGridPlacementOutcome outcome, long createdTick) {}

    private record LastSample(Vec3 target, long tick) {}
}
