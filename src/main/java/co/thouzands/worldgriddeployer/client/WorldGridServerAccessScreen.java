package co.thouzands.worldgriddeployer.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import co.thouzands.worldgriddeployer.WorldGridDebugAccess;
import co.thouzands.worldgriddeployer.WorldGridDebugNetworking.NameLookupResultPayload;
import co.thouzands.worldgriddeployer.WorldGridDebugNetworking.NameLookupStatus;
import co.thouzands.worldgriddeployer.WorldGridDebugNetworking.PlayerEntry;
import co.thouzands.worldgriddeployer.WorldGridDebugNetworking.SettingsSnapshotPayload;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;
import net.createmod.catnip.config.ui.ConfigScreenList;
import net.createmod.catnip.gui.element.TextStencilElement;
import net.createmod.catnip.gui.widget.BoxWidget;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.StringUtil;
import org.lwjgl.glfw.GLFW;

final class WorldGridServerAccessScreen extends WorldGridConfigScreenBase {
    @Nullable
    private SettingsSnapshotPayload snapshot;
    @Nullable
    private EditBox username;
    @Nullable
    private WhitelistList whitelist;
    private final Map<UUID, PlayerEntry> retained = new LinkedHashMap<>();
    private final Map<String, String> retainedPending = new LinkedHashMap<>();
    private final List<String> pendingAdds = new ArrayList<>();
    private final Set<UUID> pendingRemovals = new HashSet<>();
    private final Set<String> pendingNameRemovals = new HashSet<>();
    private WorldGridDebugAccess.Policy policy = WorldGridDebugAccess.Policy.OPS_ONLY;
    private long receivedGeneration = -1L;
    private boolean requested;
    private double whitelistScroll;
    private String inputValue = "";
    private String nameLookupQuery = "";
    private int nameLookupDelay = -1;
    private long receivedNameLookupGeneration = -1L;
    private Component nameLookupStatus = lookupMessage("prompt", ChatFormatting.DARK_GRAY);
    private Component status = Component.translatable("worldgriddeployer.config.access.loading");

    WorldGridServerAccessScreen(@Nullable Screen parent) {
        super(parent);
    }

    @Override
    protected void init() {
        super.init();
        if (!this.requested) {
            this.requested = true;
            WorldGridSettingsClient.request();
        }

        int center = this.width / 2;
        int top = Math.max(0, this.height / 2 - 118);
        this.addTitle(top, Component.translatable("worldgriddeployer.config.access.title"));

        boolean editable = this.snapshot != null && this.snapshot.editable();
        this.addButton(
            center - 154, top + 52, 308, 22,
            policyLabel(),
            () -> {
                this.policy = nextPolicy(this.policy);
                this.rebuildPreservingInput();
            },
            editable,
            tipTitle("worldgriddeployer.config.access.policy"),
            tip("worldgriddeployer.config.access.policy.tooltip"),
            tipCurrent("worldgriddeployer.config.access.policy." + this.policy.serializedName() + ".tooltip"),
            editable ? tipAction("worldgriddeployer.config.access.policy.cycle") : lockedTip()
        );

        this.username = new PlaceholderEditBox(
            this.font,
            center - 154,
            top + 82,
            232,
            20,
            Component.translatable("worldgriddeployer.config.access.username"),
            Component.translatable("worldgriddeployer.config.access.username.hint")
        );
        this.username.setMaxLength(16);
        this.username.setValue(this.inputValue);
        this.username.setEditable(editable);
        this.addRenderableWidget(this.username);

        this.addButton(
            center + 86, top + 82, 68, 20,
            Component.translatable("worldgriddeployer.config.access.add"),
            this::stageUsername,
            editable,
            tipTitle("worldgriddeployer.config.access.add"),
            tip("worldgriddeployer.config.access.add.tooltip"),
            editable ? tip("worldgriddeployer.config.access.autocomplete.tooltip") : lockedTip()
        );

        List<WhitelistRow> rows = rows();
        this.whitelist = new WhitelistList(this.minecraft, 320, 75, top + 119, 23);
        this.whitelist.setX(center - 160);
        for (WhitelistRow row : rows) {
            this.whitelist.children().add(new WhitelistEntry(
                row.label(),
                () -> {
                    row.action().run();
                    this.rebuildPreservingInput();
                },
                editable,
                tipTitle(switch (row.state()) {
                    case ADDING -> "worldgriddeployer.config.access.pending";
                    case REMOVING, PENDING_REMOVING -> "worldgriddeployer.config.access.removing";
                    case CURRENT -> "worldgriddeployer.config.access.whitelisted";
                    case PENDING -> "worldgriddeployer.config.access.awaiting";
                }),
                row.tooltip(),
                editable
                    ? tipAction(switch (row.state()) {
                        case ADDING -> "worldgriddeployer.config.access.undo_add.tooltip";
                        case REMOVING, PENDING_REMOVING -> "worldgriddeployer.config.access.keep.tooltip";
                        case CURRENT -> "worldgriddeployer.config.access.remove.tooltip";
                        case PENDING -> "worldgriddeployer.config.access.remove_pending.tooltip";
                    })
                    : lockedTip()
            ));
        }
        this.whitelist.setScrollAmount(this.whitelistScroll);
        this.addRenderableWidget(this.whitelist);

        boolean dirty = editable && this.isDirty();
        this.addButton(
            center - 154, top + 218, 92, 20,
            Component.translatable("gui.cancel"),
            this::onClose,
            true,
            tipTitle("gui.cancel"),
            tip("worldgriddeployer.config.cancel.tooltip")
        );
        this.addButton(
            center - 46, top + 218, 92, 20,
            Component.translatable("worldgriddeployer.config.access.refresh"),
            WorldGridSettingsClient::request,
            this.snapshot != null,
            tipTitle("worldgriddeployer.config.access.refresh"),
            tip("worldgriddeployer.config.access.refresh.tooltip")
        );
        this.addButton(
            center + 62, top + 218, 92, 20,
            Component.translatable("worldgriddeployer.config.access.save"),
            this::save,
            dirty,
            tipTitle("worldgriddeployer.config.access.save"),
            dirty
                ? tip("worldgriddeployer.config.access.save.tooltip")
                : editable
                    ? tip("worldgriddeployer.config.access.save.no_changes")
                    : lockedTip()
        );
    }

    @Override
    public void tick() {
        super.tick();
        if (this.username != null) {
            String currentInput = this.username.getValue();
            if (!currentInput.equals(this.inputValue)) {
                this.inputValue = currentInput;
                this.scheduleNameLookup(currentInput);
            }
            String suggestion = matchingSuggestion(this.inputValue);
            this.username.setSuggestion(suggestion == null ? null : suggestion.substring(this.inputValue.length()));
        }

        if (this.nameLookupDelay > 0 && --this.nameLookupDelay == 0) {
            WorldGridSettingsClient.lookupName(this.nameLookupQuery);
            this.nameLookupDelay = -1;
        }

        long lookupGeneration = WorldGridSettingsClient.nameLookupGeneration();
        if (lookupGeneration != this.receivedNameLookupGeneration) {
            this.receivedNameLookupGeneration = lookupGeneration;
            NameLookupResultPayload lookup = WorldGridSettingsClient.nameLookup();
            if (lookup != null && lookup.query().equalsIgnoreCase(this.nameLookupQuery)) {
                this.applyNameLookup(lookup);
            }
        }

        long generation = WorldGridSettingsClient.generation();
        SettingsSnapshotPayload received = WorldGridSettingsClient.snapshot();
        if (received != null && generation != this.receivedGeneration) {
            this.receivedGeneration = generation;
            this.applySnapshot(received);
            this.rebuildWidgets();
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.username != null && this.username.isFocused()) {
            if (keyCode == GLFW.GLFW_KEY_TAB) {
                String suggestion = matchingSuggestion(this.username.getValue());
                if (suggestion != null) {
                    this.username.setValue(suggestion);
                    this.username.moveCursorToEnd(false);
                }
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                this.stageUsername();
                return true;
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void applySnapshot(SettingsSnapshotPayload received) {
        this.snapshot = received;
        this.policy = WorldGridDebugAccess.Policy.byName(received.policy());
        this.retained.clear();
        received.whitelist().forEach(entry -> this.retained.put(entry.id(), entry));
        this.retainedPending.clear();
        received.pendingPlayers().forEach(name -> this.retainedPending.put(normalizeName(name), name));
        this.pendingAdds.clear();
        this.pendingRemovals.clear();
        this.pendingNameRemovals.clear();
        this.inputValue = "";
        this.resetNameLookup();
        this.whitelistScroll = 0;
        this.status = resultMessage(received);
    }

    private void stageUsername() {
        if (this.snapshot == null || !this.snapshot.editable() || this.username == null) {
            return;
        }
        String name = this.username.getValue().trim();
        if (name.isEmpty() || !StringUtil.isValidPlayerName(name)) {
            this.status = Component.translatable("worldgriddeployer.config.access.result.empty_name")
                .withStyle(ChatFormatting.RED);
            this.nameLookupStatus = lookupMessage("invalid", ChatFormatting.RED);
            return;
        }
        boolean duplicate = this.retained.values().stream().anyMatch(entry -> entry.name().equalsIgnoreCase(name))
            || this.retainedPending.containsKey(normalizeName(name))
            || this.pendingAdds.stream().anyMatch(entry -> entry.equalsIgnoreCase(name));
        if (duplicate) {
            this.status = Component.translatable("worldgriddeployer.config.access.result.duplicate", name)
                .withStyle(ChatFormatting.YELLOW);
            return;
        }

        this.pendingAdds.add(name);
        this.inputValue = "";
        this.resetNameLookup();
        this.status = Component.translatable("worldgriddeployer.config.access.result.staged", name)
            .withStyle(ChatFormatting.GREEN);
        this.whitelistScroll = Double.MAX_VALUE;
        this.rebuildWidgets();
    }

    private void save() {
        if (this.snapshot == null || !this.snapshot.editable() || !this.isDirty()) {
            return;
        }
        this.status = Component.translatable("worldgriddeployer.config.access.saving")
            .withStyle(ChatFormatting.YELLOW);
        WorldGridSettingsClient.update(
            this.snapshot.revision(),
            this.policy.serializedName(),
            this.retained.keySet().stream()
                .filter(id -> !this.pendingRemovals.contains(id))
                .toList(),
            this.retainedPending.values().stream()
                .filter(name -> !this.pendingNameRemovals.contains(normalizeName(name)))
                .toList(),
            List.copyOf(this.pendingAdds)
        );
    }

    private List<WhitelistRow> rows() {
        List<WhitelistRow> rows = new ArrayList<>();
        this.retained.values().stream()
            .sorted(Comparator.comparing(PlayerEntry::name, String.CASE_INSENSITIVE_ORDER))
            .forEach(entry -> {
                boolean removing = this.pendingRemovals.contains(entry.id());
                rows.add(new WhitelistRow(
                    Component.translatable(
                        removing
                            ? "worldgriddeployer.config.access.entry.removing"
                            : "worldgriddeployer.config.access.entry",
                        entry.name()
                    ).withStyle(removing ? ChatFormatting.RED : ChatFormatting.RESET),
                    tipCurrent("worldgriddeployer.config.access.entry.tooltip", entry.id()),
                    removing ? RowState.REMOVING : RowState.CURRENT,
                    () -> this.toggleRemoval(entry)
                ));
            });
        this.retainedPending.values().stream()
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .forEach(name -> {
                boolean removing = this.pendingNameRemovals.contains(normalizeName(name));
                rows.add(new WhitelistRow(
                    Component.translatable(
                        removing
                            ? "worldgriddeployer.config.access.entry.removing"
                            : "worldgriddeployer.config.access.entry.awaiting",
                        name
                    ).withStyle(removing ? ChatFormatting.RED : ChatFormatting.YELLOW),
                    tipCurrent("worldgriddeployer.config.access.entry.awaiting.tooltip", name),
                    removing ? RowState.PENDING_REMOVING : RowState.PENDING,
                    () -> this.togglePendingRemoval(name)
                ));
            });
        this.pendingAdds.stream()
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .forEach(name -> rows.add(new WhitelistRow(
                Component.translatable("worldgriddeployer.config.access.entry.pending", name),
                tipCurrent("worldgriddeployer.config.access.entry.pending.tooltip", name),
                RowState.ADDING,
                () -> this.undoPendingAdd(name)
            )));
        return rows;
    }

    private void toggleRemoval(PlayerEntry entry) {
        if (this.pendingRemovals.remove(entry.id())) {
            this.status = Component.translatable("worldgriddeployer.config.access.result.kept", entry.name())
                .withStyle(ChatFormatting.GREEN);
        } else {
            this.pendingRemovals.add(entry.id());
            this.status = Component.translatable("worldgriddeployer.config.access.result.removing", entry.name())
                .withStyle(ChatFormatting.YELLOW);
        }
    }

    private void undoPendingAdd(String name) {
        this.pendingAdds.removeIf(value -> value.equalsIgnoreCase(name));
        this.status = Component.translatable("worldgriddeployer.config.access.result.add_undone", name)
            .withStyle(ChatFormatting.GRAY);
    }

    private void togglePendingRemoval(String name) {
        String normalized = normalizeName(name);
        if (this.pendingNameRemovals.remove(normalized)) {
            this.status = Component.translatable("worldgriddeployer.config.access.result.kept", name)
                .withStyle(ChatFormatting.GREEN);
        } else {
            this.pendingNameRemovals.add(normalized);
            this.status = Component.translatable("worldgriddeployer.config.access.result.removing", name)
                .withStyle(ChatFormatting.YELLOW);
        }
    }

    private boolean isDirty() {
        if (
            this.snapshot == null
                || !this.snapshot.policy().equals(this.policy.serializedName())
                || !this.pendingAdds.isEmpty()
                || !this.pendingRemovals.isEmpty()
                || !this.pendingNameRemovals.isEmpty()
        ) {
            return true;
        }
        return false;
    }

    @Nullable
    private String matchingSuggestion(String input) {
        if (this.snapshot == null || input.isBlank()) {
            return null;
        }
        String normalized = input.toLowerCase(Locale.ROOT);
        return this.snapshot.suggestions().stream()
            .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(normalized))
            .filter(name -> !name.equalsIgnoreCase(input))
            .findFirst()
            .orElse(null);
    }

    private void scheduleNameLookup(String input) {
        String name = input.trim();
        this.nameLookupQuery = name;
        this.nameLookupDelay = -1;
        if (name.isEmpty()) {
            this.nameLookupStatus = lookupMessage("prompt", ChatFormatting.DARK_GRAY);
            return;
        }
        if (!StringUtil.isValidPlayerName(name)) {
            this.nameLookupStatus = lookupMessage("invalid", ChatFormatting.RED);
            return;
        }
        if (this.retained.values().stream().anyMatch(entry -> entry.name().equalsIgnoreCase(name))
            || this.retainedPending.containsKey(normalizeName(name))
            || this.pendingAdds.stream().anyMatch(entry -> entry.equalsIgnoreCase(name))) {
            this.nameLookupStatus = Component.translatable(
                "worldgriddeployer.config.access.lookup.duplicate",
                name
            ).withStyle(ChatFormatting.YELLOW);
            return;
        }
        this.nameLookupStatus = lookupMessage("checking", ChatFormatting.GRAY);
        this.nameLookupDelay = 8;
    }

    private void applyNameLookup(NameLookupResultPayload lookup) {
        NameLookupStatus lookupStatus = NameLookupStatus.byName(lookup.status());
        this.nameLookupStatus = switch (lookupStatus) {
            case FOUND -> Component.translatable(
                "worldgriddeployer.config.access.lookup.found",
                lookup.canonicalName()
            ).withStyle(ChatFormatting.GREEN);
            case PENDING -> Component.translatable(
                "worldgriddeployer.config.access.lookup.pending",
                lookup.canonicalName()
            ).withStyle(ChatFormatting.YELLOW);
            case NOT_FOUND -> lookupMessage("not_found", ChatFormatting.YELLOW);
            case OFFLINE_MODE -> lookupMessage("offline_mode", ChatFormatting.YELLOW);
            case INVALID -> lookupMessage("invalid", ChatFormatting.RED);
            case NO_PERMISSION -> lookupMessage("no_permission", ChatFormatting.RED);
            case ERROR -> lookupMessage("error", ChatFormatting.YELLOW);
        };
    }

    private void resetNameLookup() {
        this.nameLookupQuery = "";
        this.nameLookupDelay = -1;
        this.nameLookupStatus = lookupMessage("prompt", ChatFormatting.DARK_GRAY);
    }

    private static Component lookupMessage(String suffix, ChatFormatting color) {
        return Component.translatable("worldgriddeployer.config.access.lookup." + suffix).withStyle(color);
    }

    private static String normalizeName(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }

    private void rebuildPreservingInput() {
        if (this.username != null) {
            this.inputValue = this.username.getValue();
        }
        if (this.whitelist != null) {
            this.whitelistScroll = this.whitelist.getScrollAmount();
        }
        this.rebuildWidgets();
    }

    private MutableComponent policyLabel() {
        return Component.translatable(
            "worldgriddeployer.config.access.policy.value",
            Component.translatable("worldgriddeployer.config.access.policy." + this.policy.serializedName())
        );
    }

    private Component lockedTip() {
        return tip(this.snapshot == null
            ? "worldgriddeployer.config.access.loading.tooltip"
            : "worldgriddeployer.config.access.locked.tooltip");
    }

    private static WorldGridDebugAccess.Policy nextPolicy(WorldGridDebugAccess.Policy current) {
        WorldGridDebugAccess.Policy[] policies = WorldGridDebugAccess.Policy.values();
        return policies[(current.ordinal() + 1) % policies.length];
    }

    private static Component resultMessage(SettingsSnapshotPayload snapshot) {
        if (!snapshot.editable() && "none".equals(snapshot.result())) {
            return Component.translatable("worldgriddeployer.config.access.result.read_only")
                .withStyle(ChatFormatting.YELLOW);
        }
        String key = "worldgriddeployer.config.access.result." + snapshot.result();
        ChatFormatting color = switch (snapshot.result()) {
            case "saved" -> ChatFormatting.GREEN;
            case "stale", "unknown_player", "invalid", "no_permission" -> ChatFormatting.RED;
            default -> snapshot.editable() ? ChatFormatting.GRAY : ChatFormatting.YELLOW;
        };
        Component message = snapshot.detail().isEmpty()
            ? Component.translatable(key)
            : Component.translatable(key, snapshot.detail());
        return message.copy().withStyle(color);
    }

    @Override
    protected void renderWindow(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        int top = Math.max(0, this.height / 2 - 118);
        boolean integratedServer = this.minecraft != null && this.minecraft.getSingleplayerServer() != null;
        boolean readOnly = this.snapshot != null && !this.snapshot.editable();
        String contextKey = integratedServer
            ? readOnly
                ? "worldgriddeployer.config.access.context.lan_locked"
                : "worldgriddeployer.config.access.context.lan"
            : readOnly
                ? "worldgriddeployer.config.access.context.multiplayer_locked"
                : "worldgriddeployer.config.access.context.multiplayer";
        graphics.drawCenteredString(this.font, Component.translatable(contextKey), this.width / 2, top + 33, 0xffe0d8c8);
        graphics.drawCenteredString(this.font, this.nameLookupStatus, this.width / 2, top + 106, 0xffffffff);

        if (this.rows().isEmpty()) {
            graphics.drawCenteredString(
                this.font,
                Component.translatable("worldgriddeployer.config.access.empty"),
                this.width / 2,
                top + 154,
                0xffb8b1a5
            );
        }
        graphics.drawCenteredString(this.font, this.status, this.width / 2, top + 204, 0xffffffff);
    }

    @Override
    protected void renderWindowForeground(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
        if (this.username != null && this.username.isHovered()) {
            graphics.renderComponentTooltip(
                this.font,
                wrappedTooltip(
                    tipTitle("worldgriddeployer.config.access.username"),
                    tip("worldgriddeployer.config.access.username.tooltip"),
                    tip("worldgriddeployer.config.access.autocomplete.tooltip")
                ),
                mouseX,
                mouseY
            );
        }
    }

    private static final class WhitelistList extends ConfigScreenList {
        private WhitelistList(Minecraft minecraft, int width, int height, int top, int elementHeight) {
            super(minecraft, width, height, top, elementHeight);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int mouseButton) {
            if (this.isMouseOver(mouseX, mouseY)) {
                for (Entry entry : this.children()) {
                    if (entry instanceof WhitelistEntry whitelistEntry
                        && whitelistEntry.clickIfHovered(mouseX, mouseY, mouseButton)) {
                        return true;
                    }
                }
            }
            return super.mouseClicked(mouseX, mouseY, mouseButton);
        }
    }

    private static final class WhitelistEntry extends ConfigScreenList.Entry {
        private final ScrollRowBoxWidget button;

        private WhitelistEntry(
            Component label,
            Runnable callback,
            boolean active,
            Component... tooltip
        ) {
            TextStencilElement text = new TextStencilElement(Minecraft.getInstance().font, label.copy())
                .centered(true, true);
            this.button = new ScrollRowBoxWidget(0, 0, 300, 19).showingElement(text);
            text.withElementRenderer(BoxWidget.gradientFactory.apply(this.button));
            this.button.withCallback(callback);
            this.button.setActive(active);
            this.button.updateGradientFromState();
            this.button.getToolTip().addAll(wrappedTooltip(tooltip));
            this.listeners.add(this.button);
        }

        private boolean clickIfHovered(double mouseX, double mouseY, int mouseButton) {
            if (mouseButton != GLFW.GLFW_MOUSE_BUTTON_LEFT || !this.button.isMouseOver(mouseX, mouseY)) {
                return false;
            }
            this.button.onClick(mouseX, mouseY);
            return true;
        }

        @Override
        public void tick() {
            this.button.tick();
        }

        @Override
        public void render(
            GuiGraphics graphics,
            int index,
            int y,
            int x,
            int width,
            int height,
            int mouseX,
            int mouseY,
            boolean hovered,
            float partialTicks
        ) {
            this.button.setX(x + 2);
            this.button.setY(y + 2);
            this.button.setWidth(width - 4);
            this.button.setHeight(height - 4);
            this.button.render(graphics, mouseX, mouseY, partialTicks);
            if (this.button.isHovered()) {
                RenderSystem.disableScissor();
                this.button.renderTooltipAboveList(graphics, mouseX, mouseY, partialTicks);
                graphics.flush();
                GlStateManager._enableScissorTest();
            }
        }

        @Override
        public Component getNarration() {
            return CommonComponents.EMPTY;
        }
    }

    /** Lets a row stay clipped while its tooltip renders above the list's scissor. */
    private static final class ScrollRowBoxWidget extends BoxWidget {
        private ScrollRowBoxWidget(int x, int y, int width, int height) {
            super(x, y, width, height);
        }

        private void renderTooltipAboveList(
            GuiGraphics graphics,
            int mouseX,
            int mouseY,
            float partialTicks
        ) {
            this.renderTooltip(graphics, mouseX, mouseY, partialTicks);
        }
    }

    private static final class PlaceholderEditBox extends EditBox {
        private final Font font;
        private final Component placeholder;

        private PlaceholderEditBox(
            Font font,
            int x,
            int y,
            int width,
            int height,
            Component narration,
            Component placeholder
        ) {
            super(font, x, y, width, height, narration);
            this.font = font;
            this.placeholder = placeholder.copy().withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC);
        }

        @Override
        public void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTicks) {
            super.renderWidget(graphics, mouseX, mouseY, partialTicks);
            if (this.getValue().isEmpty() && !this.isFocused()) {
                graphics.drawString(
                    this.font,
                    this.placeholder,
                    this.getX() + 4,
                    this.getY() + (this.getHeight() - 8) / 2,
                    0xff808080,
                    false
                );
            }
        }
    }

    private enum RowState {
        CURRENT,
        ADDING,
        REMOVING,
        PENDING,
        PENDING_REMOVING
    }

    private record WhitelistRow(Component label, Component tooltip, RowState state, Runnable action) {}
}
