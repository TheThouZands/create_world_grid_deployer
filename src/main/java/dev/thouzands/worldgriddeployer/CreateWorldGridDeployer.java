package dev.thouzands.worldgriddeployer;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(CreateWorldGridDeployer.MOD_ID)
public final class CreateWorldGridDeployer {
    public static final String MOD_ID = "worldgriddeployer";
    public static final Logger LOGGER = LogUtils.getLogger();

    public CreateWorldGridDeployer() {
        LOGGER.info("Create World-Grid Deployer initialized");
    }
}
