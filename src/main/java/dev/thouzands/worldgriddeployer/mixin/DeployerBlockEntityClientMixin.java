package dev.thouzands.worldgriddeployer.mixin;

import static com.simibubi.create.content.kinetics.deployer.DeployerBlock.FACING;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.thouzands.worldgriddeployer.WorldGridDebugHistory.DeployerKey;
import dev.thouzands.worldgriddeployer.client.WorldGridDebugClient;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DeployerBlockEntity.class)
public abstract class DeployerBlockEntityClientMixin extends KineticBlockEntity {
    private static final double TARGET_BIAS = 1.0e-6;

    protected DeployerBlockEntityClientMixin(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void worldgriddeployer$captureDebugTarget(CallbackInfo ci) {
        if (!WorldGridDebugClient.isCapturing() || this.getLevel() == null || !this.getLevel().isClientSide) {
            return;
        }

        BlockEntity blockEntity = (BlockEntity) (Object) this;
        ClientSubLevel subLevel = Sable.HELPER.getContainingClient(blockEntity);
        if (subLevel == null) {
            return;
        }

        Direction facing = this.getBlockState().getValue(FACING);
        Vec3 localCenter = Vec3.atCenterOf(this.worldPosition);
        Vec3 localForward = Vec3.atLowerCornerOf(facing.getNormal());
        Vec3 center = transform(subLevel, localCenter);
        Vec3 target = transform(subLevel, localCenter.add(localForward.scale(2.0)));
        Vec3 forward = target.subtract(center);
        if (forward.lengthSqr() < 1.0e-12) {
            return;
        }
        target = target.add(forward.normalize().scale(TARGET_BIAS));

        UUID subLevelId = subLevel.getUniqueId();
        if (subLevelId == null) {
            return;
        }
        WorldGridDebugClient.capture(
            new DeployerKey(subLevelId, this.worldPosition.asLong()),
            target,
            this.getSpeed() != 0.0f
        );
    }

    private static Vec3 transform(ClientSubLevel subLevel, Vec3 local) {
        Vector3d transformed = new Vector3d(local.x, local.y, local.z);
        subLevel.logicalPose().transformPosition(transformed);
        return new Vec3(transformed.x, transformed.y, transformed.z);
    }
}
