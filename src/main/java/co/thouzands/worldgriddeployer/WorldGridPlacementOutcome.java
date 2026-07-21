package co.thouzands.worldgriddeployer;

/** Final server-authoritative result for one world-grid placement candidate. */
public enum WorldGridPlacementOutcome {
    PLACED,
    CREATE_REJECTED,
    CHUNK_UNLOADED,
    TARGET_OCCUPIED,
    NO_BLOCK_ITEM,
    NO_POWER,
    REDSTONE_LOCKED
}
