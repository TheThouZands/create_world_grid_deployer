package dev.thouzands.worldgriddeployer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

final class WorldGridDebugAccessTest {
    @Test
    void defaultsToOperatorsOnly() {
        WorldGridDebugAccess access = new WorldGridDebugAccess();
        UUID player = UUID.randomUUID();

        assertEquals(WorldGridDebugAccess.Policy.OPS_ONLY, access.policy());
        assertFalse(access.allows(player, false));
        assertTrue(access.allows(player, true));
    }

    @Test
    void policiesSeparateDisabledOpsWhitelistAndPublicAccess() {
        WorldGridDebugAccess access = new WorldGridDebugAccess();
        UUID allowed = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        access.allow(allowed);

        access.setPolicy(WorldGridDebugAccess.Policy.DISABLED);
        assertFalse(access.allows(allowed, false));
        assertFalse(access.allows(other, true));

        access.setPolicy(WorldGridDebugAccess.Policy.OPS_ONLY);
        assertFalse(access.allows(allowed, false));
        assertTrue(access.allows(other, true));

        access.setPolicy(WorldGridDebugAccess.Policy.WHITELIST);
        assertTrue(access.allows(allowed, false));
        assertFalse(access.allows(other, false));
        assertTrue(access.allows(other, true));

        access.setPolicy(WorldGridDebugAccess.Policy.PUBLIC);
        assertTrue(access.allows(allowed, false));
        assertTrue(access.allows(other, false));
    }

    @Test
    void roundTripsPolicyAndUuidWhitelist() {
        WorldGridDebugAccess access = new WorldGridDebugAccess();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        access.setPolicy(WorldGridDebugAccess.Policy.WHITELIST);
        access.allow(first);
        access.allow(second);

        CompoundTag saved = access.save(new CompoundTag(), null);
        WorldGridDebugAccess loaded = WorldGridDebugAccess.load(saved, null);

        assertEquals(WorldGridDebugAccess.Policy.WHITELIST, loaded.policy());
        assertEquals(Set.of(first, second), loaded.allowedPlayers());
    }

    @Test
    void unknownSavedPolicyFallsBackToOperatorsOnly() {
        CompoundTag saved = new CompoundTag();
        saved.putString("Policy", "future-policy");

        WorldGridDebugAccess loaded = WorldGridDebugAccess.load(saved, null);

        assertEquals(WorldGridDebugAccess.Policy.OPS_ONLY, loaded.policy());
    }
}
