package co.thouzands.worldgriddeployer.client;

import co.thouzands.worldgriddeployer.WorldGridDebugAccess;
import co.thouzands.worldgriddeployer.WorldGridDebugNetworking.PlayerEntry;
import co.thouzands.worldgriddeployer.WorldGridDebugNetworking.SettingsSnapshotPayload;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.lwjgl.glfw.GLFW;

final class WorldGridServerAccessScreen extends WorldGridConfigScreenBase {
    private static final int ROWS_PER_PAGE = 3;

    @Nullable
    private SettingsSnapshotPayload snapshot;
    @Nullable
    private EditBox username;
    private final Map<UUID, PlayerEntry> retained = new LinkedHashMap<>();
    private final List<String> pendingAdds = new ArrayList<>();
    private WorldGridDebugAccess.Policy policy = WorldGridDebugAccess.Policy.OPS_ONLY;
    private long receivedGeneration = -1L;
    private boolean requested;
    private int page;
    private String inputValue = "";
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
            center - 154, top + 48, 308, 22,
            policyLabel(),
            () -> {
                this.policy = nextPolicy(this.policy);
                this.rebuildPreservingInput();
            },
            editable,
            tipTitle("worldgriddeployer.config.access.policy"),
            tip("worldgriddeployer.config.access.policy.tooltip"),
            tip("worldgriddeployer.config.access.policy." + this.policy.serializedName() + ".tooltip"),
            editable ? tip("worldgriddeployer.config.access.policy.cycle") : lockedTip()
        );

        this.username = new EditBox(
            this.font,
            center - 154,
            top + 78,
            232,
            20,
            Component.translatable("worldgriddeployer.config.access.username")
        );
        this.username.setMaxLength(64);
        this.username.setValue(this.inputValue);
        this.username.setHint(Component.translatable("worldgriddeployer.config.access.username.hint"));
        this.username.setEditable(editable);
        this.addRenderableWidget(this.username);

        this.addButton(
            center + 86, top + 78, 68, 20,
            Component.translatable("worldgriddeployer.config.access.add"),
            this::stageUsername,
            editable,
            tipTitle("worldgriddeployer.config.access.add"),
            tip("worldgriddeployer.config.access.add.tooltip"),
            editable ? tip("worldgriddeployer.config.access.autocomplete.tooltip") : lockedTip()
        );

        List<WhitelistRow> rows = rows();
        int pageCount = Math.max(1, (rows.size() + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
        this.page = Math.min(this.page, pageCount - 1);
        int from = this.page * ROWS_PER_PAGE;
        int to = Math.min(rows.size(), from + ROWS_PER_PAGE);
        for (int index = from; index < to; index++) {
            WhitelistRow row = rows.get(index);
            int rowY = top + 104 + (index - from) * 23;
            this.addButton(
                center - 154, rowY, 308, 19,
                row.label(),
                () -> {
                    row.remove();
                    this.rebuildPreservingInput();
                },
                editable,
                tipTitle(row.pending()
                    ? "worldgriddeployer.config.access.pending"
                    : "worldgriddeployer.config.access.whitelisted"),
                row.tooltip(),
                editable ? tip("worldgriddeployer.config.access.remove.tooltip") : lockedTip()
            );
        }

        this.addButton(
            center - 154, top + 178, 54, 18,
            Component.literal("<"),
            () -> {
                this.page--;
                this.rebuildPreservingInput();
            },
            this.page > 0,
            tipTitle("worldgriddeployer.config.access.previous"),
            tip("worldgriddeployer.config.access.page.tooltip")
        );
        this.addButton(
            center + 100, top + 178, 54, 18,
            Component.literal(">"),
            () -> {
                this.page++;
                this.rebuildPreservingInput();
            },
            this.page + 1 < pageCount,
            tipTitle("worldgriddeployer.config.access.next"),
            tip("worldgriddeployer.config.access.page.tooltip")
        );

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
            this.inputValue = this.username.getValue();
            String suggestion = matchingSuggestion(this.inputValue);
            this.username.setSuggestion(suggestion == null ? null : suggestion.substring(this.inputValue.length()));
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
        this.pendingAdds.clear();
        this.inputValue = "";
        this.page = 0;
        this.status = resultMessage(received);
    }

    private void stageUsername() {
        if (this.snapshot == null || !this.snapshot.editable() || this.username == null) {
            return;
        }
        String name = this.username.getValue().trim();
        if (name.isEmpty()) {
            this.status = Component.translatable("worldgriddeployer.config.access.result.empty_name")
                .withStyle(ChatFormatting.RED);
            return;
        }
        boolean duplicate = this.retained.values().stream().anyMatch(entry -> entry.name().equalsIgnoreCase(name))
            || this.pendingAdds.stream().anyMatch(entry -> entry.equalsIgnoreCase(name));
        if (duplicate) {
            this.status = Component.translatable("worldgriddeployer.config.access.result.duplicate", name)
                .withStyle(ChatFormatting.YELLOW);
            return;
        }

        this.pendingAdds.add(name);
        this.inputValue = "";
        this.status = Component.translatable("worldgriddeployer.config.access.result.staged", name)
            .withStyle(ChatFormatting.GREEN);
        this.page = Math.max(0, (rows().size() - 1) / ROWS_PER_PAGE);
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
            List.copyOf(this.retained.keySet()),
            List.copyOf(this.pendingAdds)
        );
    }

    private List<WhitelistRow> rows() {
        List<WhitelistRow> rows = new ArrayList<>();
        this.retained.values().stream()
            .sorted(Comparator.comparing(PlayerEntry::name, String.CASE_INSENSITIVE_ORDER))
            .forEach(entry -> rows.add(new WhitelistRow(
                Component.translatable("worldgriddeployer.config.access.entry", entry.name()),
                tip("worldgriddeployer.config.access.entry.tooltip", entry.id()),
                false,
                () -> this.retained.remove(entry.id())
            )));
        this.pendingAdds.stream()
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .forEach(name -> rows.add(new WhitelistRow(
                Component.translatable("worldgriddeployer.config.access.entry.pending", name),
                tip("worldgriddeployer.config.access.entry.pending.tooltip", name),
                true,
                () -> this.pendingAdds.removeIf(value -> value.equalsIgnoreCase(name))
            )));
        return rows;
    }

    private boolean isDirty() {
        if (this.snapshot == null || !this.snapshot.policy().equals(this.policy.serializedName()) || !this.pendingAdds.isEmpty()) {
            return true;
        }
        return !this.retained.keySet().equals(
            this.snapshot.whitelist().stream().map(PlayerEntry::id).collect(java.util.stream.Collectors.toSet())
        );
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

    private void rebuildPreservingInput() {
        if (this.username != null) {
            this.inputValue = this.username.getValue();
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
        String contextKey = this.minecraft != null && this.minecraft.getSingleplayerServer() != null
            ? "worldgriddeployer.config.access.context.lan"
            : "worldgriddeployer.config.access.context.multiplayer";
        graphics.drawCenteredString(this.font, Component.translatable(contextKey), this.width / 2, top + 33, 0xffe0d8c8);

        int entries = this.rows().size();
        int pages = Math.max(1, (entries + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
        Component roster = entries == 0
            ? Component.translatable("worldgriddeployer.config.access.empty")
            : Component.translatable("worldgriddeployer.config.access.page", this.page + 1, pages, entries);
        graphics.drawCenteredString(this.font, roster, this.width / 2, top + 183, 0xffb8b1a5);
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

    private record WhitelistRow(Component label, Component tooltip, boolean pending, Runnable remove) {}
}
