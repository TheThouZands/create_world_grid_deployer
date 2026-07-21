package co.thouzands.worldgriddeployer;

import java.util.ArrayList;
import java.util.List;

/**
 * Traverses all grid boundaries crossed by a segment. Simultaneous axis
 * crossings are emitted one axis at a time so consecutive cells always share
 * a face. This produces a deterministic stair-step for exact diagonals.
 */
public final class FaceConnectedVoxelTraversal {
    private static final double TIE_EPSILON = 1.0e-10;

    private FaceConnectedVoxelTraversal() {}

    public static List<Step> trace(
        double startX,
        double startY,
        double startZ,
        double endX,
        double endY,
        double endZ,
        int maxSteps
    ) {
        if (maxSteps <= 0) {
            return List.of();
        }
        if (!allFinite(startX, startY, startZ, endX, endY, endZ)) {
            throw new IllegalArgumentException("Traversal endpoints must be finite");
        }

        int x = floor(startX);
        int y = floor(startY);
        int z = floor(startZ);
        int endCellX = floor(endX);
        int endCellY = floor(endY);
        int endCellZ = floor(endZ);

        if (x == endCellX && y == endCellY && z == endCellZ) {
            return List.of();
        }

        double deltaX = endX - startX;
        double deltaY = endY - startY;
        double deltaZ = endZ - startZ;

        int stepX = Integer.compare(endCellX, x);
        int stepY = Integer.compare(endCellY, y);
        int stepZ = Integer.compare(endCellZ, z);

        double tDeltaX = stepX == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(deltaX);
        double tDeltaY = stepY == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(deltaY);
        double tDeltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : 1.0 / Math.abs(deltaZ);

        double tMaxX = firstBoundaryT(startX, x, deltaX, stepX);
        double tMaxY = firstBoundaryT(startY, y, deltaY, stepY);
        double tMaxZ = firstBoundaryT(startZ, z, deltaZ, stepZ);

        List<Step> result = new ArrayList<>(Math.min(maxSteps, 64));

        while ((x != endCellX || y != endCellY || z != endCellZ) && result.size() < maxSteps) {
            double nextT = Math.min(tMaxX, Math.min(tMaxY, tMaxZ));
            if (!Double.isFinite(nextT) || nextT > 1.0 + TIE_EPSILON) {
                break;
            }

            boolean moved = false;

            if (tMaxX <= nextT + TIE_EPSILON && x != endCellX && result.size() < maxSteps) {
                x += stepX;
                result.add(new Step(x, y, z, clampUnit(nextT)));
                tMaxX = x == endCellX ? Double.POSITIVE_INFINITY : tMaxX + tDeltaX;
                moved = true;
            }
            if (tMaxY <= nextT + TIE_EPSILON && y != endCellY && result.size() < maxSteps) {
                y += stepY;
                result.add(new Step(x, y, z, clampUnit(nextT)));
                tMaxY = y == endCellY ? Double.POSITIVE_INFINITY : tMaxY + tDeltaY;
                moved = true;
            }
            if (tMaxZ <= nextT + TIE_EPSILON && z != endCellZ && result.size() < maxSteps) {
                z += stepZ;
                result.add(new Step(x, y, z, clampUnit(nextT)));
                tMaxZ = z == endCellZ ? Double.POSITIVE_INFINITY : tMaxZ + tDeltaZ;
                moved = true;
            }

            if (!moved) {
                break;
            }
        }

        return List.copyOf(result);
    }

    private static double firstBoundaryT(double start, int cell, double delta, int step) {
        if (step > 0) {
            return (cell + 1.0 - start) / delta;
        }
        if (step < 0) {
            return (start - cell) / -delta;
        }
        return Double.POSITIVE_INFINITY;
    }

    private static boolean allFinite(double... values) {
        for (double value : values) {
            if (!Double.isFinite(value)) {
                return false;
            }
        }
        return true;
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    private static double clampUnit(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }

    public record Step(int x, int y, int z, double interpolation) {}
}
