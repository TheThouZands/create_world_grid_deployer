package co.thouzands.worldgriddeployer.client;

import javax.annotation.Nullable;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class WorldGridConfigScreen extends WorldGridConfigScreenBase {
    public WorldGridConfigScreen(@Nullable Screen parent) {
        super(parent);
    }

    @Override
    protected void init() {
        super.init();
        int center = this.width / 2;
        int top = this.height / 2 - 82;
        this.addTitle(top, Component.translatable("worldgriddeployer.config.title"));

        boolean inWorld = this.minecraft != null && this.minecraft.level != null;
        this.addButton(
            center - 110,
            top + 42,
            220,
            24,
            Component.translatable("worldgriddeployer.config.debug"),
            () -> this.open(new WorldGridDebugConfigScreen(this)),
            inWorld,
            tipTitle("worldgriddeployer.config.debug"),
            tip(inWorld ? "worldgriddeployer.config.debug.tooltip" : "worldgriddeployer.config.world_required")
        );

        boolean privateSingleplayer = inWorld
            && this.minecraft.getSingleplayerServer() != null
            && !this.minecraft.getSingleplayerServer().isPublished();
        boolean accessAvailable = inWorld && !privateSingleplayer;
        this.addButton(
            center - 110,
            top + 76,
            220,
            24,
            Component.translatable("worldgriddeployer.config.access"),
            () -> this.open(new WorldGridServerAccessScreen(this)),
            accessAvailable,
            tipTitle("worldgriddeployer.config.access"),
            tip(privateSingleplayer
                ? "worldgriddeployer.config.access.private_singleplayer"
                : inWorld
                    ? "worldgriddeployer.config.access.tooltip"
                    : "worldgriddeployer.config.world_required")
        );

        this.addButton(
            center - 60,
            top + 120,
            120,
            20,
            Component.translatable("gui.done"),
            this::onClose,
            true,
            tipTitle("gui.done"),
            tip("worldgriddeployer.config.done.tooltip")
        );
    }

    @Override
    protected void renderWindow(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        graphics.drawCenteredString(
            this.font,
            Component.translatable("worldgriddeployer.config.subtitle"),
            this.width / 2,
            this.height / 2 - 47,
            0xffe0d8c8
        );
    }
}
