package co.thouzands.worldgriddeployer.client;

import co.thouzands.worldgriddeployer.CreateWorldGridDeployer;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.createmod.catnip.config.ui.ConfigScreen;
import net.createmod.catnip.gui.element.TextStencilElement;
import net.createmod.catnip.gui.widget.AbstractSimiWidget;
import net.createmod.catnip.gui.widget.BoxWidget;
import net.createmod.catnip.lang.FontHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

abstract class WorldGridConfigScreenBase extends ConfigScreen {
    protected WorldGridConfigScreenBase(@Nullable Screen parent) {
        super(parent);
    }

    @Override
    protected void init() {
        super.init();
        ConfigScreen.modID = CreateWorldGridDeployer.MOD_ID;
    }

    protected LabeledBox addButton(
        int x,
        int y,
        int width,
        int height,
        Component label,
        Runnable callback,
        boolean active,
        Component... tooltip
    ) {
        TextStencilElement text = new TextStencilElement(this.font, label.copy()).centered(true, true);
        BoxWidget box = new TooltipBoxWidget(x, y, width, height).showingElement(text);
        text.withElementRenderer(BoxWidget.gradientFactory.apply(box));
        box.withCallback(callback);
        box.setActive(active);
        box.updateGradientFromState();
        box.getToolTip().addAll(wrappedTooltip(tooltip));
        this.addRenderableWidget(box);
        return new LabeledBox(box, text);
    }

    protected void addTitle(int y, MutableComponent title) {
        TextStencilElement text = new TextStencilElement(this.font, title.withStyle(ChatFormatting.BOLD))
            .centered(true, true);
        BoxWidget box = new BoxWidget(-5, y, this.width + 10, 28)
            .<BoxWidget>setActive(false)
            .withBorderColors(AbstractSimiWidget.COLOR_IDLE)
            .withPadding(0, 3)
            .showingElement(text);
        this.addRenderableWidget(box);
    }

    protected void open(@Nullable Screen screen) {
        if (this.minecraft != null) {
            this.minecraft.setScreen(screen);
        }
    }

    @Override
    public void onClose() {
        this.open(this.parent);
    }

    protected static Component tipTitle(String key) {
        return Component.translatable(key).withStyle(ChatFormatting.GOLD);
    }

    protected static Component tip(String key, Object... arguments) {
        return Component.translatable(key, arguments).withStyle(ChatFormatting.GRAY);
    }

    protected static Component tipCurrent(String key, Object... arguments) {
        return Component.translatable(key, arguments).withStyle(ChatFormatting.AQUA);
    }

    protected static Component tipAction(String key, Object... arguments) {
        return Component.translatable(key, arguments).withStyle(ChatFormatting.YELLOW);
    }

    protected static List<Component> wrappedTooltip(Component... tooltip) {
        if (tooltip.length == 0) {
            return List.of();
        }
        List<Component> lines = new ArrayList<>();
        lines.add(tooltip[0]);
        for (int index = 1; index < tooltip.length; index++) {
            Component line = tooltip[index];
            lines.addAll(FontHelper.cutTextComponent(line, line.getStyle(), line.getStyle()));
        }
        return lines;
    }

    protected record LabeledBox(BoxWidget box, TextStencilElement text) {
        void label(Component label) {
            this.text.withText(label.copy());
        }

        void active(boolean active) {
            this.box.setActive(active);
            this.box.updateGradientFromState();
        }
    }

    /** Keeps explanatory tooltips reachable while preserving disabled click and color behavior. */
    private static final class TooltipBoxWidget extends BoxWidget {
        private TooltipBoxWidget(int x, int y, int width, int height) {
            super(x, y, width, height);
        }

        @Override
        public boolean isMouseOver(double mouseX, double mouseY) {
            if (!this.visible) {
                return false;
            }
            float horizontalPadding = 2 + this.paddingX;
            float verticalPadding = 2 + this.paddingY;
            return this.getX() - horizontalPadding <= mouseX
                && this.getY() - verticalPadding <= mouseY
                && mouseX < this.getX() + horizontalPadding + this.width
                && mouseY < this.getY() + verticalPadding + this.height;
        }
    }
}
