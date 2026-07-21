package co.thouzands.worldgriddeployer.client;

import net.minecraft.util.Mth;

/**
 * Spatial animation shared by Create's fallback and Flywheel deployer renderers.
 */
public final class WorldGridDeployerAnimation {
    private static final double MAX_EXTENSION = 0.5;

    private WorldGridDeployerAnimation() {
    }

    public static float handExtension(double targetX, double targetY, double targetZ) {
        double centerX = Mth.floor(targetX) + 0.5;
        double centerY = Mth.floor(targetY) + 0.5;
        double centerZ = Mth.floor(targetZ) + 0.5;
        double dx = targetX - centerX;
        double dy = targetY - centerY;
        double dz = targetZ - centerZ;
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return (float) (MAX_EXTENSION - Mth.clamp(distance, 0.0, MAX_EXTENSION));
    }
}
