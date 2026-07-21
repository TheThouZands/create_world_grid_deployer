package co.thouzands.worldgriddeployer;

import static org.junit.jupiter.api.Assertions.assertEquals;

import co.thouzands.worldgriddeployer.client.WorldGridDeployerAnimation;
import org.junit.jupiter.api.Test;

class WorldGridDeployerAnimationTest {
    @Test
    void extendsAtCellCentersAndRetractsAtFaces() {
        assertEquals(0.5f, WorldGridDeployerAnimation.handExtension(4.5, -2.5, 9.5));
        assertEquals(0.25f, WorldGridDeployerAnimation.handExtension(4.75, -2.5, 9.5));
        assertEquals(0.0f, WorldGridDeployerAnimation.handExtension(5.0, -2.5, 9.5));
    }

    @Test
    void neverPushesBehindTheDeployerAtCorners() {
        assertEquals(0.0f, WorldGridDeployerAnimation.handExtension(5.0, -2.0, 10.0));
    }

    @Test
    void repeatsAcrossPositiveAndNegativeCells() {
        assertEquals(
            WorldGridDeployerAnimation.handExtension(7.625, 3.5, 1.5),
            WorldGridDeployerAnimation.handExtension(-2.375, -8.5, 14.5)
        );
    }
}
