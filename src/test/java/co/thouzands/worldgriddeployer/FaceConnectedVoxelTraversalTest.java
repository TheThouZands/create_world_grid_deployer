package co.thouzands.worldgriddeployer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import co.thouzands.worldgriddeployer.FaceConnectedVoxelTraversal.Step;
import java.util.List;
import org.junit.jupiter.api.Test;

class FaceConnectedVoxelTraversalTest {
    @Test
    void staysEmptyInsideOneCell() {
        assertTrue(FaceConnectedVoxelTraversal.trace(0.1, 0.2, 0.3, 0.9, 0.8, 0.7, 64).isEmpty());
    }

    @Test
    void traversesEveryCellAtHighStraightLineSpeed() {
        List<Step> steps = FaceConnectedVoxelTraversal.trace(0.5, 2.5, -1.5, 4.5, 2.5, -1.5, 64);

        assertEquals(List.of(1, 2, 3, 4), steps.stream().map(Step::x).toList());
        assertEquals(new Step(4, 2, -2, 0.875), steps.getLast());
    }

    @Test
    void exactDiagonalProducesFaceConnectedStaircase() {
        List<Step> steps = FaceConnectedVoxelTraversal.trace(0.5, 0.5, 0.5, 2.5, 2.5, 0.5, 64);

        assertEquals(
            List.of(
                new Step(1, 0, 0, 0.25),
                new Step(1, 1, 0, 0.25),
                new Step(2, 1, 0, 0.75),
                new Step(2, 2, 0, 0.75)
            ),
            steps
        );
        assertFaceConnected(steps, 0, 0, 0);
    }

    @Test
    void handlesNegativeTravelAndNegativeCoordinates() {
        List<Step> steps = FaceConnectedVoxelTraversal.trace(1.2, 0.5, 0.5, -2.2, 0.5, 0.5, 64);

        assertEquals(List.of(0, -1, -2, -3), steps.stream().map(Step::x).toList());
        assertEquals(-3, steps.getLast().x());
    }

    @Test
    void threeAxisTieRemainsFaceConnected() {
        List<Step> steps = FaceConnectedVoxelTraversal.trace(0.5, 0.5, 0.5, 1.5, 1.5, 1.5, 64);

        assertEquals(3, steps.size());
        assertEquals(new Step(1, 1, 1, 0.5), steps.getLast());
        assertFaceConnected(steps, 0, 0, 0);
    }

    @Test
    void obeysThePerTickCellLimit() {
        List<Step> steps = FaceConnectedVoxelTraversal.trace(0.5, 0.5, 0.5, 100.5, 0.5, 0.5, 8);
        assertEquals(8, steps.size());
        assertEquals(8, steps.getLast().x());
    }

    @Test
    void rejectsNonFiniteCoordinates() {
        assertThrows(
            IllegalArgumentException.class,
            () -> FaceConnectedVoxelTraversal.trace(0, 0, 0, Double.NaN, 1, 1, 64)
        );
    }

    private static void assertFaceConnected(List<Step> steps, int startX, int startY, int startZ) {
        int x = startX;
        int y = startY;
        int z = startZ;
        for (Step step : steps) {
            int manhattan = Math.abs(step.x() - x) + Math.abs(step.y() - y) + Math.abs(step.z() - z);
            assertEquals(1, manhattan);
            x = step.x();
            y = step.y();
            z = step.z();
        }
    }
}
