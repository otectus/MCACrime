package dev.otectus.mcacrime.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.client.ClientRestraintData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.Map;
import java.util.UUID;

/**
 * Draws the lead between an escorting guard and their prisoner.
 *
 * <p><b>Why this is a world renderer and not a layer on the guard.</b> Reaching the guard's renderer
 * would mean looking up MCA's entity type by id, which would be this mod's first client-side coupling
 * to MCA and would silently draw nothing for a responder added through {@code responderEntities} — the
 * case the mod goes out of its way to support everywhere else. It would also have to undo the three
 * transforms {@code LivingEntityRenderer} applies before a layer runs, because vanilla draws its own
 * leashes on a fresh pose stack outside the model transform. A world-stage handler needs none of that,
 * works for any responder, and costs one pass over a map that is almost always empty.
 *
 * <p>{@code Mob.setLeashedTo} cannot target a player, so this rope is drawn from mod state rather than
 * from a real leash. That makes it purely cosmetic: it cannot desync, and switching it off changes
 * nothing about the escort.
 */
@Mod.EventBusSubscriber(modid = McaCrime.MOD_ID, value = Dist.CLIENT)
public final class EscortRopeRenderer {

    /** Segments along the rope. The same count vanilla uses for a lead, so the sag matches. */
    private static final int SEGMENTS = 24;

    private EscortRopeRenderer() {
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES
                || !McaCrimeConfig.CLIENT.renderEscortRope.get()) {
            return;
        }
        Map<UUID, ClientRestraintData.ClientRestraintEntry> restrained = ClientRestraintData.all();
        if (restrained.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        MultiBufferSource buffers = minecraft.renderBuffers().bufferSource();
        if (level == null) {
            return;
        }

        Vec3 camera = event.getCamera().getPosition();
        float partialTick = event.getPartialTick();
        PoseStack pose = event.getPoseStack();

        for (Map.Entry<UUID, ClientRestraintData.ClientRestraintEntry> entry : restrained.entrySet()) {
            int guardId = entry.getValue() == null ? -1 : entry.getValue().guardEntityId();
            if (guardId < 0) {
                continue; // nobody is escorting them, or the captive is on a real vanilla leash
            }
            Player prisoner = level.getPlayerByUUID(entry.getKey());
            Entity guard = level.getEntity(guardId);
            // Entity ids are per level, so a prisoner or guard in another dimension simply does not
            // resolve. Requiring both is what stops a stale id pairing the rope with the wrong entity.
            if (prisoner == null || guard == null || prisoner.isInvisible()) {
                continue;
            }
            render(pose, buffers, camera, guard, prisoner, partialTick);
        }
        minecraft.renderBuffers().bufferSource().endBatch(RenderType.leash());
    }

    /**
     * One rope, drawn the way vanilla draws a lead.
     *
     * <p>Two strips offset perpendicular to the run so the rope has thickness from any angle, with the
     * light sampled at both ends and interpolated along it — the same construction as
     * {@code MobRenderer#renderLeash}, so a mod lead and a vanilla lead do not look like two different
     * games.
     */
    private static void render(PoseStack pose, MultiBufferSource buffers, Vec3 camera,
                               Entity guard, Player prisoner, float partialTick) {
        Vec3 from = guard.getPosition(partialTick).add(0.0, guard.getBbHeight() * 0.6, 0.0);
        Vec3 to = prisoner.getPosition(partialTick).add(0.0, prisoner.getBbHeight() * 0.55, 0.0);

        pose.pushPose();
        pose.translate(from.x - camera.x, from.y - camera.y, from.z - camera.z);

        float dx = (float) (to.x - from.x);
        float dy = (float) (to.y - from.y);
        float dz = (float) (to.z - from.z);
        float horizontal = Mth.sqrt(dx * dx + dz * dz);
        // A thin ribbon turned to face along the run, so the rope reads as a rope rather than a
        // flat strip that vanishes edge-on.
        float offsetX = horizontal < 1.0E-4F ? 0.0F : dz / horizontal * 0.025F;
        float offsetZ = horizontal < 1.0E-4F ? 0.05F : -dx / horizontal * 0.025F;

        VertexConsumer buffer = buffers.getBuffer(RenderType.leash());
        var matrix = pose.last().pose();
        int lightFrom = lightAt(guard);
        int lightTo = lightAt(prisoner);

        for (int i = 0; i <= SEGMENTS; i++) {
            addSegment(buffer, matrix, dx, dy, dz, lightFrom, lightTo, 0.025F, 0.025F,
                    offsetX, offsetZ, i, false);
        }
        for (int i = SEGMENTS; i >= 0; i--) {
            addSegment(buffer, matrix, dx, dy, dz, lightFrom, lightTo, 0.025F, 0.0F,
                    offsetX, offsetZ, i, true);
        }
        pose.popPose();
    }

    private static void addSegment(VertexConsumer buffer, org.joml.Matrix4f matrix,
                                   float dx, float dy, float dz, int lightFrom, int lightTo,
                                   float width, float yOffset, float offsetX, float offsetZ,
                                   int index, boolean back) {
        float progress = index / (float) SEGMENTS;
        int light = lightFrom == lightTo
                ? lightFrom
                : blendLight(lightFrom, lightTo, progress);
        // A lead sags. Without the parabolic term the rope is a taut straight line, which reads as a
        // laser rather than as rope.
        float sag = (progress - progress * progress) * -1.2F;
        float shade = index % 2 == (back ? 1 : 0) ? 0.7F : 1.0F;

        float x = dx * progress;
        float y = dy * progress + sag;
        float z = dz * progress;
        buffer.vertex(matrix, x + offsetX, y + yOffset, z + offsetZ)
                .color(0.35F * shade, 0.28F * shade, 0.22F * shade, 1.0F).uv2(light).endVertex();
        buffer.vertex(matrix, x - offsetX, y + width - yOffset, z - offsetZ)
                .color(0.35F * shade, 0.28F * shade, 0.22F * shade, 1.0F).uv2(light).endVertex();
    }

    /** Blends the packed block/sky light of the two ends, each channel separately. */
    private static int blendLight(int from, int to, float progress) {
        int blockFrom = from & 0xFFFF;
        int skyFrom = from >> 16 & 0xFFFF;
        int blockTo = to & 0xFFFF;
        int skyTo = to >> 16 & 0xFFFF;
        int block = (int) Mth.lerp(progress, blockFrom, blockTo);
        int sky = (int) Mth.lerp(progress, skyFrom, skyTo);
        return block | sky << 16;
    }

    private static int lightAt(Entity entity) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return 15728880;
        }
        BlockPos pos = BlockPos.containing(entity.getEyePosition());
        return net.minecraft.client.renderer.LightTexture.pack(
                level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, pos),
                level.getBrightness(net.minecraft.world.level.LightLayer.SKY, pos));
    }
}
