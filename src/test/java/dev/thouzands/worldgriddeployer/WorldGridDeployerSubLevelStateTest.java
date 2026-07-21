package dev.thouzands.worldgriddeployer;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

class WorldGridDeployerSubLevelStateTest {
    private static final BlockPos FIRST = new BlockPos(20_481_026, 128, 20_481_030);
    private static final BlockPos SECOND = FIRST.above();

    @Test
    void storesIndependentExplicitModesWithoutReplacingOtherUserData() {
        CompoundTag userData = new CompoundTag();
        userData.putString("other_mod", "preserved");

        assertFalse(WorldGridDeployerSubLevelState.contains(userData, FIRST));
        assertFalse(WorldGridDeployerSubLevelState.isEnabled(userData, FIRST));

        assertSame(userData, WorldGridDeployerSubLevelState.setEnabled(userData, FIRST, true));
        WorldGridDeployerSubLevelState.setEnabled(userData, SECOND, false);

        assertTrue(WorldGridDeployerSubLevelState.contains(userData, FIRST));
        assertTrue(WorldGridDeployerSubLevelState.isEnabled(userData, FIRST));
        assertTrue(WorldGridDeployerSubLevelState.contains(userData, SECOND));
        assertFalse(WorldGridDeployerSubLevelState.isEnabled(userData, SECOND));
        assertTrue(userData.contains("other_mod"));

        WorldGridDeployerSubLevelState.setEnabled(userData, FIRST, false);
        assertTrue(WorldGridDeployerSubLevelState.contains(userData, FIRST));
        assertFalse(WorldGridDeployerSubLevelState.isEnabled(userData, FIRST));
    }
}
