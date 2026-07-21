package co.thouzands.worldgriddeployer.client;

import co.thouzands.worldgriddeployer.WorldGridDebugHistory;
import co.thouzands.worldgriddeployer.client.WorldGridDebugClient.DebugSettings;
import javax.annotation.Nullable;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class WorldGridDebugConfigScreen extends WorldGridConfigScreenBase {
    private static final int[] LIFETIMES = {20, 100, 200, 400, 1200, 2400, 6000, 12000};

    private boolean targets;
    private boolean pointPath;
    private int pointLifetime;
    private boolean blockTrail;
    private int blockLifetime;
    private boolean outcomes;
    private int outcomeLifetime;

    WorldGridDebugConfigScreen(@Nullable Screen parent) {
        super(parent);
        DebugSettings settings = WorldGridDebugClient.settings();
        this.targets = settings.targetsEnabled();
        this.pointPath = settings.pointPathEnabled();
        this.pointLifetime = settings.pointLifetimeTicks();
        this.blockTrail = settings.blockTrailEnabled();
        this.blockLifetime = settings.blockLifetimeTicks();
        this.outcomes = settings.outcomesEnabled();
        this.outcomeLifetime = settings.outcomeLifetimeTicks();
    }

    @Override
    protected void init() {
        super.init();
        int center = this.width / 2;
        int top = this.height / 2 - 111;
        this.addTitle(top, Component.translatable("worldgriddeployer.config.debug.title"));

        this.addToggleRow(center, top + 46, "targets", this.targets, () -> {
            this.targets = !this.targets;
            this.rebuildWidgets();
        });
        this.addTimedRow(center, top + 76, "point_path", this.pointPath, this.pointLifetime, () -> {
            this.pointPath = !this.pointPath;
            this.rebuildWidgets();
        }, () -> {
            this.pointLifetime = nextLifetime(this.pointLifetime);
            this.rebuildWidgets();
        });
        this.addTimedRow(center, top + 106, "block_trail", this.blockTrail, this.blockLifetime, () -> {
            this.blockTrail = !this.blockTrail;
            this.rebuildWidgets();
        }, () -> {
            this.blockLifetime = nextLifetime(this.blockLifetime);
            this.rebuildWidgets();
        });
        this.addTimedRow(center, top + 136, "outcomes", this.outcomes, this.outcomeLifetime, () -> {
            this.outcomes = !this.outcomes;
            this.rebuildWidgets();
        }, () -> {
            this.outcomeLifetime = nextLifetime(this.outcomeLifetime);
            this.rebuildWidgets();
        });

        this.addButton(
            center - 154, top + 174, 95, 20,
            Component.translatable("worldgriddeployer.config.debug.all_on"),
            () -> {
                this.targets = this.pointPath = this.blockTrail = this.outcomes = true;
                this.rebuildWidgets();
            },
            true,
            tipTitle("worldgriddeployer.config.debug.all_on"),
            tip("worldgriddeployer.config.debug.all_on.tooltip")
        );
        this.addButton(
            center - 48, top + 174, 95, 20,
            Component.translatable("worldgriddeployer.config.debug.all_off"),
            () -> {
                this.targets = this.pointPath = this.blockTrail = this.outcomes = false;
                this.rebuildWidgets();
            },
            true,
            tipTitle("worldgriddeployer.config.debug.all_off"),
            tip("worldgriddeployer.config.debug.all_off.tooltip")
        );
        this.addButton(
            center + 58, top + 174, 95, 20,
            Component.translatable("worldgriddeployer.config.debug.clear"),
            WorldGridDebugClient::clearDebugData,
            true,
            tipTitle("worldgriddeployer.config.debug.clear"),
            tip("worldgriddeployer.config.debug.clear.tooltip")
        );

        this.addButton(
            center - 104, top + 207, 95, 20,
            Component.translatable("gui.cancel"),
            this::onClose,
            true,
            tipTitle("gui.cancel"),
            tip("worldgriddeployer.config.cancel.tooltip")
        );
        this.addButton(
            center + 9, top + 207, 95, 20,
            Component.translatable("gui.done"),
            this::save,
            true,
            tipTitle("gui.done"),
            tip("worldgriddeployer.config.debug.save.tooltip")
        );
    }

    private void addToggleRow(int center, int y, String key, boolean enabled, Runnable toggle) {
        this.addButton(
            center - 154, y, 308, 21,
            rowLabel(key, enabled),
            toggle,
            true,
            tipTitle("worldgriddeployer.config.debug." + key),
            tip("worldgriddeployer.config.debug." + key + ".tooltip")
        );
    }

    private void addTimedRow(
        int center,
        int y,
        String key,
        boolean enabled,
        int lifetime,
        Runnable toggle,
        Runnable cycleLifetime
    ) {
        this.addButton(
            center - 154, y, 205, 21,
            rowLabel(key, enabled),
            toggle,
            true,
            tipTitle("worldgriddeployer.config.debug." + key),
            outcomeOrLocalTooltip(key)
        );
        this.addButton(
            center + 59, y, 95, 21,
            Component.translatable("worldgriddeployer.config.debug.duration", duration(lifetime)),
            cycleLifetime,
            enabled,
            tipTitle("worldgriddeployer.config.debug.duration.title"),
            tip("worldgriddeployer.config.debug.duration.tooltip"),
            tip("worldgriddeployer.config.debug.duration.value", duration(lifetime), lifetime)
        );
    }

    private void save() {
        WorldGridDebugClient.applySettings(new DebugSettings(
            this.targets,
            this.pointPath,
            this.pointLifetime,
            this.blockTrail,
            this.blockLifetime,
            this.outcomes,
            this.outcomeLifetime
        ));
        this.onClose();
    }

    private Component outcomeOrLocalTooltip(String key) {
        if (!key.equals("outcomes")) {
            return tip("worldgriddeployer.config.debug." + key + ".tooltip");
        }
        if (this.minecraft == null || this.minecraft.level == null) {
            return tip("worldgriddeployer.config.debug.outcomes.offline.tooltip");
        }
        if (!WorldGridDebugClient.serverOutcomesSupported()) {
            return tip("worldgriddeployer.config.debug.outcomes.unsupported.tooltip");
        }
        return tip("worldgriddeployer.config.debug.outcomes.tooltip");
    }

    private static Component rowLabel(String key, boolean enabled) {
        return Component.translatable(
            "worldgriddeployer.config.debug.row",
            Component.translatable("worldgriddeployer.config.debug." + key),
            Component.translatable(enabled ? "options.on" : "options.off")
        );
    }

    private static Component duration(int ticks) {
        return Component.translatable("worldgriddeployer.config.debug.seconds", ticks / 20);
    }

    private static int nextLifetime(int current) {
        for (int lifetime : LIFETIMES) {
            if (lifetime > current) {
                return lifetime;
            }
        }
        return LIFETIMES[0];
    }

    @Override
    protected void renderWindow(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        graphics.drawCenteredString(
            this.font,
            Component.translatable("worldgriddeployer.config.debug.subtitle", WorldGridDebugHistory.MAX_LIFETIME_TICKS / 20),
            this.width / 2,
            this.height / 2 - 79,
            0xffe0d8c8
        );
    }
}
