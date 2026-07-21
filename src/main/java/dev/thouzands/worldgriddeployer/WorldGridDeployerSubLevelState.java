package dev.thouzands.worldgriddeployer;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/**
 * Stores the mode that Create cannot represent in Sable's per-sublevel extension data.
 */
public final class WorldGridDeployerSubLevelState {
    private static final String MOD_DATA_KEY = "worldgriddeployer";
    private static final String MODES_KEY = "deployer_modes";

    private WorldGridDeployerSubLevelState() {}

    public static boolean contains(CompoundTag userData, BlockPos pos) {
        if (userData == null || !userData.contains(MOD_DATA_KEY, Tag.TAG_COMPOUND)) {
            return false;
        }

        CompoundTag modData = userData.getCompound(MOD_DATA_KEY);
        return modData.contains(MODES_KEY, Tag.TAG_COMPOUND)
            && modData.getCompound(MODES_KEY).contains(positionKey(pos), Tag.TAG_BYTE);
    }

    public static boolean isEnabled(CompoundTag userData, BlockPos pos) {
        if (!contains(userData, pos)) {
            return false;
        }

        return userData
            .getCompound(MOD_DATA_KEY)
            .getCompound(MODES_KEY)
            .getBoolean(positionKey(pos));
    }

    public static CompoundTag setEnabled(CompoundTag userData, BlockPos pos, boolean enabled) {
        CompoundTag root = userData != null ? userData : new CompoundTag();
        CompoundTag modData = root.contains(MOD_DATA_KEY, Tag.TAG_COMPOUND)
            ? root.getCompound(MOD_DATA_KEY)
            : new CompoundTag();
        CompoundTag modes = modData.contains(MODES_KEY, Tag.TAG_COMPOUND)
            ? modData.getCompound(MODES_KEY)
            : new CompoundTag();

        // Retain explicit false values so a prior block-entity tag cannot revive
        // a mode that the player deliberately switched off.
        modes.putBoolean(positionKey(pos), enabled);
        modData.put(MODES_KEY, modes);
        root.put(MOD_DATA_KEY, modData);
        return root;
    }

    private static String positionKey(BlockPos pos) {
        return Long.toString(pos.asLong());
    }
}
