package co.thouzands.worldgriddeployer;

import co.thouzands.worldgriddeployer.client.WorldGridConfigClient;
import com.mojang.logging.LogUtils;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

@Mod(CreateWorldGridDeployer.MOD_ID)
public final class CreateWorldGridDeployer {
    public static final String MOD_ID = "worldgriddeployer";
    public static final Logger LOGGER = LogUtils.getLogger();

    public CreateWorldGridDeployer(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(WorldGridDebugNetworking::registerPayloadHandlers);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            WorldGridConfigClient.register(modContainer);
        }
        LOGGER.info("Create World-Grid Deployer initialized");
    }
}
