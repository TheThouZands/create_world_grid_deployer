package dev.thouzands.worldgriddeployer.mixin;

import static com.simibubi.create.content.kinetics.deployer.DeployerBlock.FACING;

import com.mojang.datafixers.util.Pair;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity;
import com.simibubi.create.content.kinetics.deployer.DeployerFakePlayer;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.thouzands.worldgriddeployer.FaceConnectedVoxelTraversal;
import dev.thouzands.worldgriddeployer.FaceConnectedVoxelTraversal.Step;
import dev.thouzands.worldgriddeployer.WorldGridDeployerAccess;
import java.lang.reflect.Field;
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
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

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
    private Vec3 worldgriddeployer$lastCenter;
    @Unique
    private Vec3 worldgriddeployer$lastTarget;
    @Unique
    private Vec3 worldgriddeployer$lastForward;
    @Unique
    private Vec3 worldgriddeployer$lastYawReference;
    @Unique
    private boolean worldgriddeployer$redirectActivation;
    @Unique
    private Vec3 worldgriddeployer$activationCenter;
    @Unique
    private BlockPos worldgriddeployer$activationTarget;
    @Unique
    private Vec3 worldgriddeployer$activationForward;
    @Unique
    private float worldgriddeployer$activationYaw;

    protected DeployerBlockEntityMixin(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public boolean worldgriddeployer$isEnabled() {
        return this.worldgriddeployer$enabled;
    }

    @Invoker("activate")
    protected abstract void worldgriddeployer$invokeActivate();

    /**
     * Cycle PUNCH -> USE -> WORLD_GRID_USE -> PUNCH without extending Create's
     * serialized two-value enum.
     */
    @Inject(method = "changeMode", at = @At("HEAD"), cancellable = true)
    private void worldgriddeployer$cycleWorldGridMode(CallbackInfo ci) {
        if (this.worldgriddeployer$enabled) {
            this.worldgriddeployer$enabled = false;
            this.worldgriddeployer$setReflected(WORLDGRIDDEPLOYER_MODE_FIELD, WORLDGRIDDEPLOYER_MODE_PUNCH);
            this.worldgriddeployer$resetOperatingState();
            this.setChanged();
            this.sendData();
            ci.cancel();
            return;
        }

        if (this.worldgriddeployer$getReflected(WORLDGRIDDEPLOYER_MODE_FIELD) == WORLDGRIDDEPLOYER_MODE_USE) {
            this.worldgriddeployer$enabled = true;
            this.worldgriddeployer$resetOperatingState();
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
        tag.putBoolean(WORLD_GRID_NBT_KEY, this.worldgriddeployer$enabled);
    }

    @Inject(method = "read", at = @At("TAIL"))
    private void worldgriddeployer$readMode(
        CompoundTag tag,
        HolderLookup.Provider registries,
        boolean clientPacket,
        CallbackInfo ci
    ) {
        this.worldgriddeployer$enabled = tag.getBoolean(WORLD_GRID_NBT_KEY);
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
        if (!this.worldgriddeployer$isArmed()) {
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
        for (Step step : crossed) {
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
            return false;
        }
        if (!parentLevel.getBlockState(target).isAir()) {
            return false;
        }
        if (!(this.player.getMainHandItem().getItem() instanceof BlockItem)) {
            return false;
        }

        float xRot = AbstractContraptionEntity.pitchFromVector(forward) - 90.0f;
        Vec3 yawVector = Math.abs(xRot) > 89.0f ? yawReference : forward;
        this.player.setYRot(AbstractContraptionEntity.yawFromVector(yawVector));
        this.player.setXRot(xRot);

        // Create and NeoForge perform their normal placement/event checks again
        // inside this synchronous call. The strict air check above is therefore
        // a fast conflict guard, not the sole validity test.
        this.worldgriddeployer$redirectActivation = true;
        this.worldgriddeployer$activationCenter = center;
        this.worldgriddeployer$activationTarget = target;
        this.worldgriddeployer$activationForward = forward;
        this.worldgriddeployer$activationYaw = this.player.getYRot();
        try {
            this.worldgriddeployer$invokeActivate();
        } finally {
            this.worldgriddeployer$redirectActivation = false;
            this.worldgriddeployer$activationCenter = null;
            this.worldgriddeployer$activationTarget = null;
            this.worldgriddeployer$activationForward = null;
        }
        return true;
    }

    /**
     * Keep Create's activation/event/inventory flow intact while replacing
     * only the plot-space coordinates it calculated with parent-world data.
     * Args avoids linking against Create's package-private Mode enum.
     */
    @ModifyArgs(
        method = "activate",
        at = @At(
            value = "INVOKE",
            target = "Lcom/simibubi/create/content/kinetics/deployer/DeployerHandler;activate(Lcom/simibubi/create/content/kinetics/deployer/DeployerFakePlayer;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/Vec3;Lcom/simibubi/create/content/kinetics/deployer/DeployerBlockEntity$Mode;)V"
        )
    )
    private void worldgriddeployer$redirectActivationArguments(Args args) {
        if (!this.worldgriddeployer$redirectActivation) {
            return;
        }
        args.set(1, this.worldgriddeployer$activationCenter);
        args.set(2, this.worldgriddeployer$activationTarget);
        args.set(3, this.worldgriddeployer$activationForward);

        float xRot = AbstractContraptionEntity.pitchFromVector(this.worldgriddeployer$activationForward) - 90.0f;
        this.player.setXRot(xRot);
        this.player.setYRot(this.worldgriddeployer$activationYaw);
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

    @Inject(method = "addToGoggleTooltip", at = @At("TAIL"))
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
