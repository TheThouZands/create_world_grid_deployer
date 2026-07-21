package dev.thouzands.worldgriddeployer.client;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.thouzands.worldgriddeployer.CreateWorldGridDeployer;
import dev.thouzands.worldgriddeployer.WorldGridDebugHistory;
import dev.thouzands.worldgriddeployer.WorldGridDebugHistory.DeployerKey;
import dev.thouzands.worldgriddeployer.WorldGridDebugHistory.LiveTarget;
import dev.thouzands.worldgriddeployer.WorldGridDebugHistory.TimedBlock;
import dev.thouzands.worldgriddeployer.WorldGridDebugHistory.TimedPoint;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

/** Client-only command, collection, and rendering entry point. */
@EventBusSubscriber(modid = CreateWorldGridDeployer.MOD_ID, value = Dist.CLIENT)
public final class WorldGridDebugClient {
    private static final WorldGridDebugHistory HISTORY = new WorldGridDebugHistory();

    private static final RenderType OVERLAY_LINES = RenderType.create(
        "worldgriddeployer_debug_lines",
        DefaultVertexFormat.POSITION_COLOR_NORMAL,
        VertexFormat.Mode.LINES,
        1536,
        false,
        false,
        RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.RENDERTYPE_LINES_SHADER)
            .setLineState(new RenderStateShard.LineStateShard(OptionalDouble.of(2.0)))
            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
            .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
            .setCullState(RenderStateShard.NO_CULL)
            .createCompositeState(false)
    );

    private static final RenderType OVERLAY_FILL = RenderType.create(
        "worldgriddeployer_debug_fill",
        DefaultVertexFormat.POSITION_COLOR,
        VertexFormat.Mode.TRIANGLE_STRIP,
        1536,
        false,
        true,
        RenderType.CompositeState.builder()
            .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
            .setDepthTestState(RenderStateShard.NO_DEPTH_TEST)
            .setWriteMaskState(RenderStateShard.COLOR_WRITE)
            .setCullState(RenderStateShard.NO_CULL)
            .createCompositeState(false)
    );

    private WorldGridDebugClient() {}

    public static boolean isCapturing() {
        return HISTORY.isCapturing();
    }

    public static void capture(DeployerKey key, Vec3 target, boolean powered) {
        HISTORY.capture(key, target, powered);
    }

    @SubscribeEvent
    public static void registerCommands(RegisterClientCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        LiteralArgumentBuilder<CommandSourceStack> root = literal("worldgriddeployer");
        root.then(literal("debug")
            .then(literal("targets")
                .then(literal("on").executes(context -> setTargets(context, true)))
                .then(literal("off").executes(context -> setTargets(context, false))))
            .then(historyCommand("point_path", true))
            .then(historyCommand("block_trail", false))
            .then(literal("all")
                .then(literal("on").executes(WorldGridDebugClient::enableAll))
                .then(literal("off").executes(WorldGridDebugClient::disableAll)))
            .then(literal("clear").executes(WorldGridDebugClient::clearAll))
            .then(literal("status").executes(WorldGridDebugClient::status)));

        dispatcher.register(root);
    }

    @SubscribeEvent
    public static void clientTick(ClientTickEvent.Post event) {
        if (Minecraft.getInstance().level != null) {
            HISTORY.advanceTick();
        }
    }

    @SubscribeEvent
    public static void loggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        HISTORY.clearData();
    }

    @SubscribeEvent
    public static void renderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL || !HISTORY.isCapturing()) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return;
        }

        PoseStack poseStack = event.getPoseStack();
        Vec3 camera = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(OVERLAY_LINES);
        VertexConsumer fill = buffers.getBuffer(OVERLAY_FILL);

        poseStack.pushPose();
        // AFTER_LEVEL is dispatched with a null PoseStack, so NeoForge gives
        // listeners a fresh identity stack. Restore GameRenderer's camera
        // rotation before applying the world-to-camera translation.
        poseStack.mulPose(event.getModelViewMatrix());
        poseStack.translate(-camera.x, -camera.y, -camera.z);

        if (HISTORY.blockTrailEnabled()) {
            renderBlockTrail(poseStack, lines);
        }
        if (HISTORY.pointPathEnabled()) {
            renderPointPaths(poseStack, lines);
        }
        if (HISTORY.targetsEnabled()) {
            renderLiveTargets(poseStack, lines, fill);
        }

        poseStack.popPose();
        buffers.endBatch(OVERLAY_LINES);
        buffers.endBatch(OVERLAY_FILL);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> historyCommand(String name, boolean points) {
        return literal(name)
            .then(literal("on")
                .executes(context -> startHistory(context, points, currentLifetime(points)))
                .then(argument("duration", IntegerArgumentType.integer(1, WorldGridDebugHistory.MAX_LIFETIME_TICKS))
                    .executes(context -> startHistory(
                        context,
                        points,
                        IntegerArgumentType.getInteger(context, "duration")
                    ))
                    .then(literal("ticks").executes(context -> startHistory(
                        context,
                        points,
                        IntegerArgumentType.getInteger(context, "duration")
                    )))
                    .then(literal("seconds").executes(context -> startHistorySeconds(context, points)))))
            .then(literal("off").executes(context -> stopHistory(context, points)))
            .then(literal("clear").executes(context -> clearHistory(context, points)));
    }

    private static int setTargets(CommandContext<CommandSourceStack> context, boolean enabled) {
        HISTORY.setTargetsEnabled(enabled);
        return success(context, "Live target overlay " + onOff(enabled));
    }

    private static int startHistorySeconds(CommandContext<CommandSourceStack> context, boolean points) {
        int seconds = IntegerArgumentType.getInteger(context, "duration");
        if (seconds > WorldGridDebugHistory.MAX_LIFETIME_TICKS / 20) {
            context.getSource().sendFailure(Component.literal(
                "Maximum history duration is " + (WorldGridDebugHistory.MAX_LIFETIME_TICKS / 20) + " seconds"
            ));
            return 0;
        }
        return startHistory(context, points, seconds * 20);
    }

    private static int startHistory(CommandContext<CommandSourceStack> context, boolean points, int lifetimeTicks) {
        if (points) {
            HISTORY.startPointPath(lifetimeTicks);
        } else {
            HISTORY.startBlockTrail(lifetimeTicks);
        }
        return success(context, historyName(points) + " enabled for " + duration(lifetimeTicks));
    }

    private static int stopHistory(CommandContext<CommandSourceStack> context, boolean points) {
        if (points) {
            HISTORY.stopPointPath();
        } else {
            HISTORY.stopBlockTrail();
        }
        return success(context, historyName(points) + " disabled and cleared");
    }

    private static int clearHistory(CommandContext<CommandSourceStack> context, boolean points) {
        if (points) {
            HISTORY.clearPointPath();
        } else {
            HISTORY.clearBlockTrail();
        }
        return success(context, historyName(points) + " cleared");
    }

    private static int enableAll(CommandContext<CommandSourceStack> context) {
        HISTORY.setTargetsEnabled(true);
        HISTORY.startPointPath(HISTORY.pointLifetimeTicks());
        HISTORY.startBlockTrail(HISTORY.blockLifetimeTicks());
        return success(context, "All deployer debug overlays enabled");
    }

    private static int disableAll(CommandContext<CommandSourceStack> context) {
        HISTORY.setTargetsEnabled(false);
        HISTORY.stopPointPath();
        HISTORY.stopBlockTrail();
        return success(context, "All deployer debug overlays disabled and cleared");
    }

    private static int clearAll(CommandContext<CommandSourceStack> context) {
        HISTORY.clearData();
        return success(context, "All collected deployer debug geometry cleared");
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        return success(context, "targets=" + onOff(HISTORY.targetsEnabled())
            + ", point_path=" + onOff(HISTORY.pointPathEnabled()) + " (" + duration(HISTORY.pointLifetimeTicks()) + ")"
            + ", block_trail=" + onOff(HISTORY.blockTrailEnabled()) + " (" + duration(HISTORY.blockLifetimeTicks()) + ")");
    }

    private static int success(CommandContext<CommandSourceStack> context, String message) {
        context.getSource().sendSuccess(() -> Component.literal("[World-Grid Debug] " + message), false);
        return 1;
    }

    private static int currentLifetime(boolean points) {
        return points ? HISTORY.pointLifetimeTicks() : HISTORY.blockLifetimeTicks();
    }

    private static String historyName(boolean points) {
        return points ? "Exact-point path" : "Candidate-block trail";
    }

    private static String onOff(boolean value) {
        return value ? "on" : "off";
    }

    private static String duration(int ticks) {
        return ticks + " ticks / " + (ticks / 20.0) + " seconds";
    }

    private static void renderLiveTargets(PoseStack poseStack, VertexConsumer lines, VertexConsumer fill) {
        for (LiveTarget target : HISTORY.liveTargets()) {
            float red = target.powered() ? 0.15f : 1.0f;
            float green = target.powered() ? 1.0f : 0.15f;
            float blue = 0.15f;
            BlockPos block = target.block();
            AABB box = new AABB(block).inflate(0.004);

            LevelRenderer.addChainedFilledBoxVertices(
                poseStack,
                fill,
                box.minX,
                box.minY,
                box.minZ,
                box.maxX,
                box.maxY,
                box.maxZ,
                red,
                green,
                blue,
                0.14f
            );
            LevelRenderer.renderLineBox(poseStack, lines, box, red, green, blue, 0.95f);

            Vec3 point = target.point();
            double radius = 0.055;
            LevelRenderer.addChainedFilledBoxVertices(
                poseStack,
                fill,
                point.x - radius,
                point.y - radius,
                point.z - radius,
                point.x + radius,
                point.y + radius,
                point.z + radius,
                1.0f,
                1.0f,
                0.2f,
                0.95f
            );
        }
    }

    private static void renderPointPaths(PoseStack poseStack, VertexConsumer lines) {
        long now = HISTORY.tick();
        int lifetime = HISTORY.pointLifetimeTicks();
        for (Map.Entry<DeployerKey, List<TimedPoint>> path : HISTORY.pointPaths().entrySet()) {
            TimedPoint previous = null;
            for (TimedPoint point : path.getValue()) {
                if (previous != null && !point.breakBefore()) {
                    float alpha = fade(now, Math.max(previous.createdTick(), point.createdTick()), lifetime, 0.18f, 0.95f);
                    addLine(poseStack, lines, previous.position(), point.position(), 0.15f, 0.95f, 1.0f, alpha);
                }
                previous = point;
            }
        }
    }

    private static void renderBlockTrail(PoseStack poseStack, VertexConsumer lines) {
        long now = HISTORY.tick();
        int lifetime = HISTORY.blockLifetimeTicks();
        for (TimedBlock block : HISTORY.blockTrail()) {
            float alpha = fade(now, block.createdTick(), lifetime, 0.10f, 0.72f);
            AABB box = new AABB(block.position()).inflate(0.012);
            LevelRenderer.renderLineBox(poseStack, lines, box, 0.25f, 0.55f, 1.0f, alpha);
        }
    }

    private static float fade(long now, long created, int lifetime, float minimum, float maximum) {
        double remaining = 1.0 - Math.max(0.0, now - created) / lifetime;
        return (float) (minimum + (maximum - minimum) * Math.max(0.0, remaining));
    }

    private static void addLine(
        PoseStack poseStack,
        VertexConsumer consumer,
        Vec3 start,
        Vec3 end,
        float red,
        float green,
        float blue,
        float alpha
    ) {
        Vec3 delta = end.subtract(start);
        double length = delta.length();
        if (length < 1.0e-7) {
            return;
        }

        float normalX = (float) (delta.x / length);
        float normalY = (float) (delta.y / length);
        float normalZ = (float) (delta.z / length);
        PoseStack.Pose pose = poseStack.last();
        consumer.addVertex(pose, (float) start.x, (float) start.y, (float) start.z)
            .setColor(red, green, blue, alpha)
            .setNormal(pose, normalX, normalY, normalZ);
        consumer.addVertex(pose, (float) end.x, (float) end.y, (float) end.z)
            .setColor(red, green, blue, alpha)
            .setNormal(pose, normalX, normalY, normalZ);
    }
}
