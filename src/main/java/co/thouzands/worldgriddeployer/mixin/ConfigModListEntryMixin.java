package co.thouzands.worldgriddeployer.mixin;

import co.thouzands.worldgriddeployer.CreateWorldGridDeployer;
import co.thouzands.worldgriddeployer.client.WorldGridConfigClient;
import net.createmod.catnip.config.ui.ConfigModListScreen;
import net.createmod.catnip.config.ui.ConfigScreenList;
import net.createmod.catnip.gui.ScreenOpener;
import net.createmod.catnip.gui.element.DelegatedStencilElement;
import net.createmod.catnip.gui.widget.BoxWidget;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ConfigModListScreen.ModEntry.class)
public abstract class ConfigModListEntryMixin {
    @Shadow
    protected BoxWidget button;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void worldgriddeployer$exposeCustomConfig(String id, Screen parent, CallbackInfo ci) {
        if (!CreateWorldGridDeployer.MOD_ID.equals(id)) {
            return;
        }

        this.button.setActive(true);
        this.button.updateGradientFromState();
        this.button.modifyElement(element -> ((DelegatedStencilElement) element)
            .withElementRenderer(BoxWidget.gradientFactory.apply(this.button)));
        this.button.withCallback(() -> ScreenOpener.open(WorldGridConfigClient.createScreen(parent)));

        ConfigScreenList.LabeledEntry entry = (ConfigScreenList.LabeledEntry) (Object) this;
        entry.getLabelTooltip().clear();
    }
}
