package co.thouzands.worldgriddeployer.client;

import co.thouzands.worldgriddeployer.WorldGridDebugNetworking.SettingsRequestPayload;
import co.thouzands.worldgriddeployer.WorldGridDebugNetworking.SettingsSnapshotPayload;
import co.thouzands.worldgriddeployer.WorldGridDebugNetworking.SettingsUpdatePayload;
import co.thouzands.worldgriddeployer.WorldGridDebugNetworking.NameLookupRequestPayload;
import co.thouzands.worldgriddeployer.WorldGridDebugNetworking.NameLookupResultPayload;
import java.util.List;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

/** Connection-scoped cache for the server access editor. */
public final class WorldGridSettingsClient {
    @Nullable
    private static SettingsSnapshotPayload snapshot;
    private static long generation;
    @Nullable
    private static NameLookupResultPayload nameLookup;
    private static long nameLookupGeneration;

    private WorldGridSettingsClient() {}

    public static void request() {
        if (supported()) {
            PacketDistributor.sendToServer(new SettingsRequestPayload());
        }
    }

    public static void update(long revision, String policy, List<UUID> retained, List<String> added) {
        update(revision, policy, retained, List.of(), added);
    }

    public static void update(
        long revision,
        String policy,
        List<UUID> retained,
        List<String> retainedPending,
        List<String> added
    ) {
        if (supported()) {
            PacketDistributor.sendToServer(
                new SettingsUpdatePayload(revision, policy, retained, retainedPending, added)
            );
        }
    }

    public static void lookupName(String name) {
        if (supported()) {
            PacketDistributor.sendToServer(new NameLookupRequestPayload(name));
        }
    }

    public static boolean supported() {
        var connection = Minecraft.getInstance().getConnection();
        return connection != null && NetworkRegistry.hasChannel(connection, SettingsRequestPayload.TYPE.id());
    }

    public static void receive(SettingsSnapshotPayload payload) {
        snapshot = payload;
        generation++;
    }

    public static void receiveNameLookup(NameLookupResultPayload payload) {
        nameLookup = payload;
        nameLookupGeneration++;
    }

    @Nullable
    public static SettingsSnapshotPayload snapshot() {
        return snapshot;
    }

    public static long generation() {
        return generation;
    }

    @Nullable
    public static NameLookupResultPayload nameLookup() {
        return nameLookup;
    }

    public static long nameLookupGeneration() {
        return nameLookupGeneration;
    }

    public static void reset() {
        snapshot = null;
        generation++;
        nameLookup = null;
        nameLookupGeneration++;
    }
}
