package co.thouzands.worldgriddeployer.client;

import net.minecraft.client.gui.screens.Screen;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/** Client-only registration kept behind the physical-side check in the mod constructor. */
public final class WorldGridConfigClient {
    private WorldGridConfigClient() {}

    public static void register(ModContainer container) {
        container.registerExtensionPoint(
            IConfigScreenFactory.class,
            (ignored, parent) -> createScreen(parent)
        );
    }

    public static Screen createScreen(Screen parent) {
        return new WorldGridConfigScreen(parent);
    }
}
