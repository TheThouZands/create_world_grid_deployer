package co.thouzands.worldgriddeployer.mixin;

import static com.simibubi.create.content.kinetics.deployer.DeployerBlock.FACING;

import com.mojang.datafixers.util.Pair;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity;
import com.simibubi.create.content.kinetics.deployer.DeployerFakePlayer;
import com.simibubi.create.content.kinetics.deployer.DeployerHandler;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import co.thouzands.worldgriddeployer.FaceConnectedVoxelTraversal;
import co.thouzands.worldgriddeployer.FaceConnectedVoxelTraversal.Step;
import co.thouzands.worldgriddeployer.WorldGridDeployerAccess;
import co.thouzands.worldgriddeployer.WorldGridDeployerSubLevelState;
import co.thouzands.worldgriddeployer.WorldGridDebugNetworking;
import co.thouzands.worldgriddeployer.WorldGridPlacementOutcome;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(DeployerBlockEntity.class)
public abstract class DeployerBlockEntityMixin extends KineticBlockEntity
    implements BlockEntitySubLevelActor, WorldGridDeployerAccess {

    @Unique
    private static final String WORLD_GRID_NBT_KEY = "WorldGridDeployerMode";
    @Unique
    private static final int MAX_CELLS_PER_TICK = 64;
    @Unique
    private static final double MAX_SWEEP_DISTANCE_SQUARED = 64.0 * 64.0;
    @Unique
    private static final double TARGET_BIAS = 1.0e-6;
    @Unique
    private static final Field WORLDGRIDDEPLOYER_MODE_FIELD = worldgriddeployer$findField(
        DeployerBlockEntity.class,
        "mode"
    );
    @Unique
    private static final Field WORLDGRIDDEPLOYER_STATE_FIELD = worldgriddeployer$findField(
        DeployerBlockEntity.class,
        "state"
    );
    @Unique
    private static final Field WORLDGRIDDEPLOYER_BREAKING_FIELD = worldgriddeployer$findField(
        DeployerFakePlayer.class,
        "blockBreakingProgress"
    );
    @Unique
    private static final Object WORLDGRIDDEPLOYER_MODE_PUNCH = worldgriddeployer$enumConstant(
        WORLDGRIDDEPLOYER_MODE_FIELD,
        "PUNCH"
    );
    @Unique
    private static final Object WORLDGRIDDEPLOYER_MODE_USE = worldgriddeployer$enumConstant(
        WORLDGRIDDEPLOYER_MODE_FIELD,
        "USE"
    );
    @Unique
    private static final Method WORLDGRIDDEPLOYER_ACTIVATE_METHOD = worldgriddeployer$findMethod(
        DeployerHandler.class,
        "activate",
        DeployerFakePlayer.class,
        Vec3.class,
        BlockPos.class,
        Vec3.class,
        WORLDGRIDDEPLOYER_MODE_FIELD.getType()
    );
    @Unique
    private static final Object WORLDGRIDDEPLOYER_STATE_WAITING = worldgriddeployer$enumConstant(
        WORLDGRIDDEPLOYER_STATE_FIELD,
        "WAITING"
    );

    @Shadow
    protected ItemStack heldItem;
    @Shadow
    protected DeployerFakePlayer player;
    @Shadow
    protected int timer;
    @Shadow
    protected float reach;
    @Shadow
    protected boolean fistBump;
    @Shadow
    protected boolean redstoneLocked;

    @Unique
    private boolean worldgriddeployer$enabled;
    @Unique
    private boolean worldgriddeployer$subLevelStateLoaded;
    @Unique
    private Vec3 worldgriddeployer$lastCenter;
    @Unique
    private Vec3 worldgriddeployer$lastTarget;
    @Unique
    private Vec3 worldgriddeployer$lastForward;
    @Unique
    private Vec3 worldgriddeployer$lastYawReference;

    protected DeployerBlockEntityMixin(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public boolean worldgriddeployer$isEnabled() {
        return this.worldgriddeployer$enabled;
    }

    /**
     * Cycle PUNCH -> USE -> WORLD_GRID_USE -> PUNCH without extending Create's
     * serialized two-value enum.
     */
    @Inject(method = "changeMode", at = @At("HEAD"), cancellable = true)
    private void worldgriddeployer$cycleWorldGridMode(CallbackInfo ci) {
        ServerSubLevel subLevel = this.worldgriddeployer$getServerSubLevel();
        this.worldgriddeployer$restoreSubLevelMode(subLevel);

        if (this.worldgriddeployer$enabled) {
            this.worldgriddeployer$enabled = false;
            this.worldgriddeployer$setReflected(WORLDGRIDDEPLOYER_MODE_FIELD, WORLDGRIDDEPLOYER_MODE_PUNCH);
            this.worldgriddeployer$resetOperatingState();
            this.worldgriddeployer$persistSubLevelMode(subLevel);
            this.setChanged();
            this.sendData();
            ci.cancel();
            return;
        }

        if (this.worldgriddeployer$getReflected(WORLDGRIDDEPLOYER_MODE_FIELD) == WORLDGRIDDEPLOYER_MODE_USE) {
            this.worldgriddeployer$enabled = true;
            this.worldgriddeployer$resetOperatingState();
            this.worldgriddeployer$persistSubLevelMode(subLevel);
            this.setChanged();
            this.sendData();
            ci.cancel();
        }
        // PUNCH is intentionally left to Create, which changes it to USE.
    }

    @Inject(method = "write", at = @At("TAIL"))
    private void worldgriddeployer$writeMode(
        CompoundTag tag,
        HolderLookup.Provider registries,
        boolean clientPacket,
        CallbackInfo ci
    ) {
        if (!clientPacket) {
            this.worldgriddeployer$restoreSubLevelMode(this.worldgriddeployer$getServerSubLevel());
        }
        tag.putBoolean(WORLD_GRID_NBT_KEY, this.worldgriddeployer$enabled);
    }

    /**
     * Create uses this reduced NBT path when a block entity is copied into a
     * contraption/sublevel snapshot. Keep the extended mode alongside Create's
     * own safe "Mode" value so Sable can restore it after a server restart.
     */
    @Inject(method = "writeSafe", at = @At("TAIL"))
    private void worldgriddeployer$writeSafeMode(
        CompoundTag tag,
        HolderLookup.Provider registries,
        CallbackInfo ci
    ) {
        this.worldgriddeployer$restoreSubLevelMode(this.worldgriddeployer$getServerSubLevel());
        tag.putBoolean(WORLD_GRID_NBT_KEY, this.worldgriddeployer$enabled);
    }

    @Inject(method = "read", at = @At("TAIL"))
    private void worldgriddeployer$readMode(
        CompoundTag tag,
        HolderLookup.Provider registries,
        boolean clientPacket,
        CallbackInfo ci
    ) {
        // Some Create/Sable update tags are intentionally partial. Absence must
        // not erase a value that was already restored from the full save tag.
        if (tag.contains(WORLD_GRID_NBT_KEY)) {
            this.worldgriddeployer$enabled = tag.getBoolean(WORLD_GRID_NBT_KEY);
        }
        this.worldgriddeployer$resetTraversal();
    }

    /**
     * Preserve KineticBlockEntity's base tick, then suppress Create's RPM state
     * machine only while this deployer is actually mounted on a Sable sublevel.
     */
    @Inject(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lcom/simibubi/create/content/kinetics/base/KineticBlockEntity;tick()V",
            shift = At.Shift.AFTER
        ),
        cancellable = true
    )
    private void worldgriddeployer$skipRpmStateMachine(CallbackInfo ci) {
        if (!this.worldgriddeployer$enabled || this.getLevel() == null) {
            return;
        }

        if (dev.ryanhcode.sable.Sable.HELPER.getContaining((BlockEntity) (Object) this) != null) {
            ci.cancel();
        }
    }

    @Override
    public void sable$tick(ServerSubLevel subLevel) {
        this.worldgriddeployer$restoreSubLevelMode(subLevel);

        if (!this.worldgriddeployer$isArmed()) {
            WorldGridPlacementOutcome inactivity = this.worldgriddeployer$inactivityOutcome();
            if (inactivity != null) {
                Sample current = this.worldgriddeployer$sample(subLevel);
                if (current != null) {
                    WorldGridDebugNetworking.emit(
                        subLevel.getLevel(),
                        BlockPos.containing(current.target()),
                        inactivity
                    );
                }
            }
            this.worldgriddeployer$resetTraversal();
            return;
        }

        ServerLevel parentLevel = subLevel.getLevel();
        if (this.player.serverLevel() != parentLevel) {
            this.worldgriddeployer$resetTraversal();
            return;
        }

        Sample current = this.worldgriddeployer$sample(subLevel);
        if (current == null) {
            this.worldgriddeployer$resetTraversal();
            return;
        }

        boolean attempted;
        if (this.worldgriddeployer$lastTarget == null) {
            attempted = this.worldgriddeployer$tryPlace(
                parentLevel,
                BlockPos.containing(current.target()),
                current.center(),
                current.forward(),
                current.yawReference()
            );
        } else if (this.worldgriddeployer$lastTarget.distanceToSqr(current.target()) > MAX_SWEEP_DISTANCE_SQUARED) {
            // Treat implausibly large motion as a teleport instead of building a
            // long accidental line. The current cell still gets one normal visit.
            attempted = this.worldgriddeployer$tryPlace(
                parentLevel,
                BlockPos.containing(current.target()),
                current.center(),
                current.forward(),
                current.yawReference()
            );
        } else {
            attempted = this.worldgriddeployer$runSweep(parentLevel, current);
        }

        this.worldgriddeployer$lastCenter = current.center();
        this.worldgriddeployer$lastTarget = current.target();
        this.worldgriddeployer$lastForward = current.forward();
        this.worldgriddeployer$lastYawReference = current.yawReference();

        if (attempted) {
            this.heldItem = this.player.getMainHandItem();
            this.setChanged();
            this.sendData();
        }
    }

    @Unique
    private boolean worldgriddeployer$runSweep(ServerLevel parentLevel, Sample current) {
        List<Step> crossed = FaceConnectedVoxelTraversal.trace(
            this.worldgriddeployer$lastTarget.x,
            this.worldgriddeployer$lastTarget.y,
            this.worldgriddeployer$lastTarget.z,
            current.target().x,
            current.target().y,
            current.target().z,
            MAX_CELLS_PER_TICK
        );

        boolean attempted = false;
        for (int stepIndex = 0; stepIndex < crossed.size(); stepIndex++) {
            Step step = crossed.get(stepIndex);
            double interpolation = step.interpolation();
            Vec3 center = this.worldgriddeployer$lerp(this.worldgriddeployer$lastCenter, current.center(), interpolation);
            Vec3 forward = this.worldgriddeployer$normalizedLerp(
                this.worldgriddeployer$lastForward,
                current.forward(),
                interpolation,
                current.forward()
            );
            Vec3 yawReference = this.worldgriddeployer$normalizedLerp(
                this.worldgriddeployer$lastYawReference,
                current.yawReference(),
                interpolation,
                current.yawReference()
            );

            attempted |= this.worldgriddeployer$tryPlace(
                parentLevel,
                new BlockPos(step.x(), step.y(), step.z()),
                center,
                forward,
                yawReference
            );

            if (!(this.player.getMainHandItem().getItem() instanceof BlockItem)) {
                for (int skippedIndex = stepIndex + 1; skippedIndex < crossed.size(); skippedIndex++) {
                    Step skipped = crossed.get(skippedIndex);
                    WorldGridDebugNetworking.emit(
                        parentLevel,
                        new BlockPos(skipped.x(), skipped.y(), skipped.z()),
                        WorldGridPlacementOutcome.NO_BLOCK_ITEM
                    );
                }
                break;
            }
        }
        return attempted;
    }

    @Unique
    private boolean worldgriddeployer$tryPlace(
        ServerLevel parentLevel,
        BlockPos target,
        Vec3 center,
        Vec3 forward,
        Vec3 yawReference
    ) {
        if (!parentLevel.hasChunkAt(target)) {
            WorldGridDebugNetworking.emit(parentLevel, target, WorldGridPlacementOutcome.CHUNK_UNLOADED);
            return false;
        }
        BlockState beforePlacement = parentLevel.getBlockState(target);
        if (!beforePlacement.canBeReplaced()) {
            WorldGridDebugNetworking.emit(parentLevel, target, WorldGridPlacementOutcome.TARGET_OCCUPIED);
            return false;
        }
        if (!(this.player.getMainHandItem().getItem() instanceof BlockItem blockItem)) {
            WorldGridDebugNetworking.emit(parentLevel, target, WorldGridPlacementOutcome.NO_BLOCK_ITEM);
            return false;
        }

        float xRot = AbstractContraptionEntity.pitchFromVector(forward) - 90.0f;
        Vec3 yawVector = Math.abs(xRot) > 89.0f ? yawReference : forward;
        this.player.setYRot(AbstractContraptionEntity.yawFromVector(yawVector));
        this.player.setXRot(xRot);

        // Create and NeoForge perform their normal placement/event checks inside
        // this synchronous call. The replaceability check above is therefore a
        // fast conflict guard, not the sole validity test. Reflection is
        // intentional: Create's Mode enum
        // and handler method are package-private, and linking either from mixin-
        // generated bytecode causes an IllegalAccessError on a modular server.
        this.worldgriddeployer$activate(center, target, forward);
        BlockState afterPlacement = parentLevel.getBlockState(target);
        WorldGridDebugNetworking.emit(
            parentLevel,
            target,
            afterPlacement.is(blockItem.getBlock()) || !afterPlacement.equals(beforePlacement)
                ? WorldGridPlacementOutcome.PLACED
                : WorldGridPlacementOutcome.CREATE_REJECTED
        );
        return true;
    }

    @Unique
    private void worldgriddeployer$activate(Vec3 center, BlockPos target, Vec3 forward) {
        try {
            WORLDGRIDDEPLOYER_ACTIVATE_METHOD.invoke(
                null,
                this.player,
                center,
                target,
                forward,
                WORLDGRIDDEPLOYER_MODE_USE
            );
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Could not access Create's deployer placement handler", exception);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("Create's deployer placement handler failed", cause);
        }
    }

    @Unique
    private boolean worldgriddeployer$isArmed() {
        return this.worldgriddeployer$enabled
            && this.worldgriddeployer$getReflected(WORLDGRIDDEPLOYER_MODE_FIELD) == WORLDGRIDDEPLOYER_MODE_USE
            && !this.redstoneLocked
            && this.getSpeed() != 0
            && this.player != null
            && this.player.getMainHandItem().getItem() instanceof BlockItem;
    }

    @Unique
    private WorldGridPlacementOutcome worldgriddeployer$inactivityOutcome() {
        if (!this.worldgriddeployer$enabled
            || this.worldgriddeployer$getReflected(WORLDGRIDDEPLOYER_MODE_FIELD) != WORLDGRIDDEPLOYER_MODE_USE) {
            return null;
        }
        if (this.redstoneLocked) {
            return WorldGridPlacementOutcome.REDSTONE_LOCKED;
        }
        if (this.getSpeed() == 0) {
            return WorldGridPlacementOutcome.NO_POWER;
        }
        if (this.player == null || !(this.player.getMainHandItem().getItem() instanceof BlockItem)) {
            return WorldGridPlacementOutcome.NO_BLOCK_ITEM;
        }
        return null;
    }

    @Unique
    private Sample worldgriddeployer$sample(ServerSubLevel subLevel) {
        Direction facing = this.getBlockState().getValue(FACING);
        Vec3 localCenter = Vec3.atCenterOf(this.worldPosition);
        Vec3 localForward = Vec3.atLowerCornerOf(facing.getNormal());
        Vec3 localTarget = localCenter.add(localForward.scale(2.0));
        Vec3 localYawReference = facing.getAxis().isVertical() ? new Vec3(0.0, 0.0, 1.0) : localForward;

        Vec3 center = this.worldgriddeployer$transformPosition(subLevel, localCenter);
        Vec3 target = this.worldgriddeployer$transformPosition(subLevel, localTarget);
        Vec3 yawPoint = this.worldgriddeployer$transformPosition(subLevel, localCenter.add(localYawReference));

        Vec3 forward = target.subtract(center);
        Vec3 yawReference = yawPoint.subtract(center);
        if (!this.worldgriddeployer$isUsableVector(forward) || !this.worldgriddeployer$isUsableVector(yawReference)) {
            return null;
        }

        forward = forward.normalize();
        yawReference = yawReference.normalize();
        target = target.add(forward.scale(TARGET_BIAS));
        return new Sample(center, target, forward, yawReference);
    }

    @Unique
    private Vec3 worldgriddeployer$transformPosition(ServerSubLevel subLevel, Vec3 local) {
        Vector3d transformed = new Vector3d(local.x, local.y, local.z);
        subLevel.logicalPose().transformPosition(transformed);
        return new Vec3(transformed.x, transformed.y, transformed.z);
    }

    @Unique
    private boolean worldgriddeployer$isUsableVector(Vec3 vector) {
        return Double.isFinite(vector.x)
            && Double.isFinite(vector.y)
            && Double.isFinite(vector.z)
            && vector.lengthSqr() > 1.0e-12;
    }

    @Unique
    private Vec3 worldgriddeployer$lerp(Vec3 start, Vec3 end, double interpolation) {
        return start.scale(1.0 - interpolation).add(end.scale(interpolation));
    }

    @Unique
    private Vec3 worldgriddeployer$normalizedLerp(Vec3 start, Vec3 end, double interpolation, Vec3 fallback) {
        Vec3 blended = this.worldgriddeployer$lerp(start, end, interpolation);
        return this.worldgriddeployer$isUsableVector(blended) ? blended.normalize() : fallback;
    }

    @Unique
    private void worldgriddeployer$resetOperatingState() {
        this.worldgriddeployer$setReflected(WORLDGRIDDEPLOYER_STATE_FIELD, WORLDGRIDDEPLOYER_STATE_WAITING);
        this.timer = 0;
        this.reach = 0.0f;
        this.fistBump = false;
        this.worldgriddeployer$resetTraversal();

        if (this.player != null && this.getLevel() != null) {
            Object progress = this.worldgriddeployer$getReflected(this.player, WORLDGRIDDEPLOYER_BREAKING_FIELD);
            if (progress instanceof Pair<?, ?> pair && pair.getFirst() instanceof BlockPos breakingPos) {
                this.getLevel().destroyBlockProgress(this.player.getId(), breakingPos, -1);
                this.worldgriddeployer$setReflected(this.player, WORLDGRIDDEPLOYER_BREAKING_FIELD, null);
            }
        }
    }

    @Unique
    private static Field worldgriddeployer$findField(Class<?> owner, String name) {
        try {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @Unique
    private static Method worldgriddeployer$findMethod(Class<?> owner, String name, Class<?>... parameterTypes) {
        try {
            Method method = owner.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @Unique
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object worldgriddeployer$enumConstant(Field field, String name) {
        return Enum.valueOf((Class<? extends Enum>) field.getType().asSubclass(Enum.class), name);
    }

    @Unique
    private Object worldgriddeployer$getReflected(Field field) {
        return this.worldgriddeployer$getReflected(this, field);
    }

    @Unique
    private Object worldgriddeployer$getReflected(Object owner, Field field) {
        try {
            return field.get(owner);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Could not read Create deployer state", exception);
        }
    }

    @Unique
    private void worldgriddeployer$setReflected(Field field, Object value) {
        this.worldgriddeployer$setReflected(this, field, value);
    }

    @Unique
    private void worldgriddeployer$setReflected(Object owner, Field field, Object value) {
        try {
            field.set(owner, value);
        } catch (IllegalAccessException exception) {
            throw new IllegalStateException("Could not update Create deployer state", exception);
        }
    }

    @Unique
    private void worldgriddeployer$resetTraversal() {
        this.worldgriddeployer$lastCenter = null;
        this.worldgriddeployer$lastTarget = null;
        this.worldgriddeployer$lastForward = null;
        this.worldgriddeployer$lastYawReference = null;
    }

    @Unique
    private ServerSubLevel worldgriddeployer$getServerSubLevel() {
        if (this.getLevel() == null || this.getLevel().isClientSide) {
            return null;
        }

        Object containing = dev.ryanhcode.sable.Sable.HELPER.getContaining((BlockEntity) (Object) this);
        return containing instanceof ServerSubLevel subLevel ? subLevel : null;
    }

    @Unique
    private void worldgriddeployer$restoreSubLevelMode(ServerSubLevel subLevel) {
        if (subLevel == null || this.worldgriddeployer$subLevelStateLoaded) {
            return;
        }

        this.worldgriddeployer$subLevelStateLoaded = true;
        CompoundTag userData = subLevel.getUserDataTag();
        if (!WorldGridDeployerSubLevelState.contains(userData, this.worldPosition)) {
            // First tick after assembly (or upgrade): seed Sable's extension
            // data from the ordinary block-entity value.
            this.worldgriddeployer$persistSubLevelMode(subLevel);
            return;
        }

        boolean restored = WorldGridDeployerSubLevelState.isEnabled(userData, this.worldPosition);
        if (this.worldgriddeployer$enabled != restored) {
            this.worldgriddeployer$enabled = restored;
            this.worldgriddeployer$resetOperatingState();
            this.sendData();
        }
    }

    @Unique
    private void worldgriddeployer$persistSubLevelMode(ServerSubLevel subLevel) {
        if (subLevel == null) {
            return;
        }

        CompoundTag userData = WorldGridDeployerSubLevelState.setEnabled(
            subLevel.getUserDataTag(),
            this.worldPosition,
            this.worldgriddeployer$enabled
        );
        subLevel.setUserDataTag(userData);
        this.worldgriddeployer$subLevelStateLoaded = true;
    }

    @Inject(method = "addToGoggleTooltip", at = @At("TAIL"), cancellable = true)
    private void worldgriddeployer$appendGoggleMode(
        List<Component> tooltip,
        boolean isPlayerSneaking,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (this.worldgriddeployer$enabled) {
            tooltip.add(Component.translatable("worldgriddeployer.mode").withStyle(ChatFormatting.AQUA));
            cir.setReturnValue(true);
        }
    }

    @Unique
    private record Sample(Vec3 center, Vec3 target, Vec3 forward, Vec3 yawReference) {}
}
