package co.thouzands.worldgriddeployer.mixin;

import co.thouzands.worldgriddeployer.client.WorldGridDeployerAnimationAccess;
import com.simibubi.create.content.kinetics.base.ShaftVisual;
import com.simibubi.create.content.kinetics.deployer.DeployerBlockEntity;
import com.simibubi.create.content.kinetics.deployer.DeployerVisual;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.OrientedInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DeployerVisual.class)
public abstract class DeployerVisualMixin extends ShaftVisual<DeployerBlockEntity> {
    @Shadow
    @Final
    private Direction facing;

    @Shadow
    @Final
    protected OrientedInstance pole;

    @Shadow
    protected OrientedInstance hand;

    @Shadow
    private float progress;

    @Unique
    private boolean worldgriddeployer$wasAnimating;

    protected DeployerVisualMixin(
        VisualizationContext context,
        DeployerBlockEntity blockEntity,
        float partialTick
    ) {
        super(context, blockEntity, partialTick);
    }

    @Inject(method = "beginFrame", at = @At("HEAD"), cancellable = true)
    private void worldgriddeployer$animateFromWorldGridMotion(DynamicVisual.Context context, CallbackInfo ci) {
        float distance = ((WorldGridDeployerAnimationAccess) this.blockEntity)
            .worldgriddeployer$getHandExtension(context.partialTick());
        if (!Float.isFinite(distance)) {
            if (this.worldgriddeployer$wasAnimating) {
                // Force Create to restore its ordinary position even when its state progress
                // happens to equal the stale value from before the Sable visual took over.
                this.progress = Float.NaN;
                this.worldgriddeployer$wasAnimating = false;
            }
            return;
        }

        this.worldgriddeployer$wasAnimating = true;
        Vec3i facingVector = this.facing.getNormal();
        BlockPos visualPosition = this.getVisualPosition();
        float x = visualPosition.getX() + facingVector.getX() * distance;
        float y = visualPosition.getY() + facingVector.getY() * distance;
        float z = visualPosition.getZ() + facingVector.getZ() * distance;

        this.pole.position(x, y, z).setChanged();
        this.hand.position(x, y, z).setChanged();
        ci.cancel();
    }
}
