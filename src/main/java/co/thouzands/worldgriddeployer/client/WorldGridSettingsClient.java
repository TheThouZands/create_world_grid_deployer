package co.thouzands.worldgriddeployer.client;

import co.thouzands.worldgriddeployer.WorldGridDebugNetworking.SettingsRequestPayload;
import co.thouzands.worldgriddeployer.WorldGridDebugNetworking.SettingsSnapshotPayload;
import co.thouzands.worldgriddeployer.WorldGridDebugNetworking.SettingsUpdatePayload;
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

    private WorldGridSettingsClient() {}

    public static void request() {
        if (supported()) {
            PacketDistributor.sendToServer(new SettingsRequestPayload());
        }
    }

    public static void update(long revision, String policy, List<UUID> retained, List<String> added) {
        if (supported()) {
            PacketDistributor.sendToServer(new SettingsUpdatePayload(revision, policy, retained, added));
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

    @Nullable
    public static SettingsSnapshotPayload snapshot() {
        return snapshot;
    }

    public static long generation() {
        return generation;
    }

    public static void reset() {
        snapshot = null;
        generation++;
    }
}
