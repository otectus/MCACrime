package dev.otectus.mcacrime.client.render.restraint;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.restraint.RestraintDefinitions;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The eight worn restraint models, and which definition wears which (0.7.5 M2.10).
 *
 * <h2>Why the parts are named after the body</h2>
 * Every layer definition's root children are named {@code head}, {@code body}, {@code right_arm},
 * {@code left_arm}, {@code right_leg} and {@code left_leg}, and each carries its cubes in that body
 * part's own local space. {@link RestraintSlotLayer} then copies the parent model's transform into
 * the matching child and renders it. That is what makes one layer able to draw all eight without
 * knowing anything about any of them, and what makes a worn restraint follow whatever pose the body
 * is in — including a pose this mod did not write.
 *
 * <h2>Two provenances, in one file</h2>
 * The cuff, shackle, leg-cuff and leg-shackle geometry is MCA: Crime's own, authored against
 * the UV layout that {@code tools/art/generate_restraint_art.py} draws: a 5 x 2 x 5 band at
 * {@code texOffs(0, 0)} and a flat chain strip at {@code texOffs(0, 10)}, on a 32 x 32 sheet. The
 * tape and hood geometry is adapted from Cuffed (LazrProductions, GPL-3.0, see
 * {@code docs/0.7.5/PROVENANCE.md}) because the textures for those two are adapted from Cuffed as
 * well, and geometry authored against a different texture would sample the wrong pixels.
 *
 * <h2>What is deliberately not here</h2>
 * No {@code HumanoidModel} subclass. Upstream's models each extend {@code HumanoidModel} and
 * declare seven empty parts to satisfy its constructor, which is a lot of ceremony for "draw two
 * bands" and drags a second {@code setupAnim} into the render path. These are plain layer
 * definitions.
 */
public final class RestraintModels {

    // --- the body parts a worn model may attach to ------------------------------------------------

    public static final String HEAD = "head";
    public static final String BODY = "body";
    public static final String RIGHT_ARM = "right_arm";
    public static final String LEFT_ARM = "left_arm";
    public static final String RIGHT_LEG = "right_leg";
    public static final String LEFT_LEG = "left_leg";

    /** The band: 5 x 2 x 5 at texOffs(0, 0). Twenty across, seven down. */
    private static final int BAND_U = 0;
    private static final int BAND_V = 0;

    /** The chain strip: 5 x 2 x 0 at texOffs(0, 10). */
    private static final int CHAIN_U = 0;
    private static final int CHAIN_V = 10;

    /** A vanilla arm is four blocks long from the shoulder pivot, so the wrist sits at y = 7. */
    private static final float WRIST_Y = 7.0F;

    /** A leg is twelve units from the hip pivot; the ankle band sits just above the foot. */
    private static final float ANKLE_Y = 9.0F;

    private RestraintModels() {
    }

    /**
     * One worn model: where its geometry lives, what it is drawn with, and which parts it uses.
     *
     * @param layer       the baked layer location
     * @param texture     the entity texture
     * @param attachments which root children exist, so the layer copies only those transforms
     */
    public record Worn(ModelLayerLocation layer, ResourceLocation texture, List<String> attachments) {

        public Worn {
            attachments = List.copyOf(attachments);
        }
    }

    private static ModelLayerLocation layer(String path) {
        return new ModelLayerLocation(McaCrime.id("restraint/" + path), "main");
    }

    private static ResourceLocation texture(String path) {
        return McaCrime.id("textures/entity/restraint/" + path + ".png");
    }

    private static final Map<ResourceLocation, Worn> WORN = build();

    private static Map<ResourceLocation, Worn> build() {
        Map<ResourceLocation, Worn> map = new LinkedHashMap<>();
        map.put(RestraintDefinitions.HANDCUFFS_ARMS, new Worn(layer("handcuffs_arms"),
                texture("handcuffs"), List.of(RIGHT_ARM, LEFT_ARM)));
        map.put(RestraintDefinitions.HANDCUFFS_LEGS, new Worn(layer("handcuffs_legs"),
                texture("handcuffs"), List.of(RIGHT_LEG, LEFT_LEG)));
        map.put(RestraintDefinitions.SHACKLES_ARMS, new Worn(layer("shackles_arms"),
                texture("shackles"), List.of(RIGHT_ARM, LEFT_ARM)));
        map.put(RestraintDefinitions.SHACKLES_LEGS, new Worn(layer("shackles_legs"),
                texture("shackles"), List.of(RIGHT_LEG, LEFT_LEG)));
        map.put(RestraintDefinitions.DUCK_TAPE_ARMS, new Worn(layer("duck_tape_arms"),
                texture("duck_tape"), List.of(BODY, RIGHT_ARM, LEFT_ARM)));
        map.put(RestraintDefinitions.DUCK_TAPE_LEGS, new Worn(layer("duck_tape_legs"),
                texture("duck_tape"), List.of(RIGHT_LEG, LEFT_LEG)));
        map.put(RestraintDefinitions.DUCK_TAPE_HEAD, new Worn(layer("duck_tape_head"),
                texture("duck_tape"), List.of(HEAD)));
        map.put(RestraintDefinitions.BUNDLE, new Worn(layer("bundle"),
                texture("bundle"), List.of(HEAD)));
        return Map.copyOf(map);
    }

    /** Every worn model, for registration. */
    public static Map<ResourceLocation, Worn> all() {
        return WORN;
    }

    /** The worn model for one definition, empty for a definition nothing is drawn for. */
    public static Optional<Worn> forDefinition(@Nullable ResourceLocation definitionId) {
        return definitionId == null ? Optional.empty() : Optional.ofNullable(WORN.get(definitionId));
    }

    /** The layer definition for one definition id, or null when it has no worn model. */
    @Nullable
    public static LayerDefinition create(ResourceLocation definitionId) {
        if (RestraintDefinitions.HANDCUFFS_ARMS.equals(definitionId)
                || RestraintDefinitions.SHACKLES_ARMS.equals(definitionId)) {
            return wristBands();
        }
        if (RestraintDefinitions.HANDCUFFS_LEGS.equals(definitionId)
                || RestraintDefinitions.SHACKLES_LEGS.equals(definitionId)) {
            return ankleBands();
        }
        if (RestraintDefinitions.DUCK_TAPE_ARMS.equals(definitionId)) {
            return tapeArms();
        }
        if (RestraintDefinitions.DUCK_TAPE_LEGS.equals(definitionId)) {
            return tapeLegs();
        }
        if (RestraintDefinitions.DUCK_TAPE_HEAD.equals(definitionId)) {
            return tapeHead();
        }
        if (RestraintDefinitions.BUNDLE.equals(definitionId)) {
            return hood();
        }
        return null;
    }

    // --- MCA: Crime's own geometry -----------------------------------------------------------------

    /**
     * A band round each wrist, plus a length of slack chain between them.
     *
     * <p>The chain hangs off the right cuff and is rotated towards the left one. It is a flat
     * zero-depth quad drawn without back-face culling, which is the standard way to draw a chain in a
     * model this size: two visible faces for the price of none.
     */
    private static LayerDefinition wristBands() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        PartDefinition right = root.addOrReplaceChild(RIGHT_ARM,
                CubeListBuilder.create().texOffs(BAND_U, BAND_V)
                        .addBox(-2.5F, WRIST_Y, -2.5F, 5.0F, 2.0F, 5.0F),
                PartPose.ZERO);
        right.addOrReplaceChild("chain",
                CubeListBuilder.create().texOffs(CHAIN_U, CHAIN_V)
                        .addBox(0.0F, 0.0F, 0.0F, 5.0F, 2.0F, 0.0F),
                PartPose.offsetAndRotation(1.5F, WRIST_Y + 0.5F, 0.0F, 0.0F, 0.0F, 0.35F));
        root.addOrReplaceChild(LEFT_ARM,
                CubeListBuilder.create().texOffs(BAND_U, BAND_V).mirror()
                        .addBox(-2.5F, WRIST_Y, -2.5F, 5.0F, 2.0F, 5.0F).mirror(false),
                PartPose.ZERO);
        return LayerDefinition.create(mesh, 32, 32);
    }

    /** A band round each ankle, with the chain between them. */
    private static LayerDefinition ankleBands() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        PartDefinition right = root.addOrReplaceChild(RIGHT_LEG,
                CubeListBuilder.create().texOffs(BAND_U, BAND_V)
                        .addBox(-2.5F, ANKLE_Y, -2.5F, 5.0F, 2.0F, 5.0F),
                PartPose.ZERO);
        right.addOrReplaceChild("chain",
                CubeListBuilder.create().texOffs(CHAIN_U, CHAIN_V)
                        .addBox(0.0F, 0.0F, 0.0F, 5.0F, 2.0F, 0.0F),
                PartPose.offsetAndRotation(1.5F, ANKLE_Y + 0.5F, 0.0F, 0.0F, 0.0F, 0.2F));
        root.addOrReplaceChild(LEFT_LEG,
                CubeListBuilder.create().texOffs(BAND_U, BAND_V).mirror()
                        .addBox(-2.5F, ANKLE_Y, -2.5F, 5.0F, 2.0F, 5.0F).mirror(false),
                PartPose.ZERO);
        return LayerDefinition.create(mesh, 32, 32);
    }

    // --- geometry adapted from Cuffed, against the adapted tape and hood textures -------------------

    /**
     * Tape round both wrists and a wrap across the front of the body.
     *
     * <p>Adapted from upstream's {@code DuckTapeArmsModel}: the same cube sizes and the same texture
     * offsets, with the part-pose offsets folded into the cube origins because the parts here hang
     * directly off the body part rather than off an intermediate pivot.
     */
    private static LayerDefinition tapeArms() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        PartDefinition body = root.addOrReplaceChild(BODY, CubeListBuilder.create(), PartPose.ZERO);
        body.addOrReplaceChild("wrap_upper",
                CubeListBuilder.create().texOffs(0, 18).addBox(-2.0F, -2.0F, -1.0F, 5.0F, 2.0F, 2.0F),
                PartPose.offsetAndRotation(-0.5F, 9.25F, 2.5F, 0.5672F, 0.0F, 0.0F));
        body.addOrReplaceChild("wrap_lower",
                CubeListBuilder.create().texOffs(0, 16).addBox(-4.0F, -2.0F, -1.0F, 8.0F, 2.0F, 2.0F),
                PartPose.offsetAndRotation(0.0F, 7.0F, 6.75F, 0.5672F, 0.0F, 0.0F));
        root.addOrReplaceChild(RIGHT_ARM,
                CubeListBuilder.create().texOffs(0, 9).addBox(-3.5F, 6.0F, -2.5F, 5.0F, 2.0F, 5.0F),
                PartPose.ZERO);
        root.addOrReplaceChild(LEFT_ARM,
                CubeListBuilder.create().texOffs(0, 9).mirror()
                        .addBox(-1.5F, 6.0F, -2.5F, 5.0F, 2.0F, 5.0F).mirror(false),
                PartPose.ZERO);
        return LayerDefinition.create(mesh, 32, 32);
    }

    /** Tape round both ankles. Adapted from upstream's {@code DuckTapeLegsModel}. */
    private static LayerDefinition tapeLegs() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        root.addOrReplaceChild(RIGHT_LEG,
                CubeListBuilder.create().texOffs(0, 24).addBox(-2.6F, 8.0F, -3.0F, 5.0F, 2.0F, 6.0F),
                PartPose.ZERO);
        root.addOrReplaceChild(LEFT_LEG,
                CubeListBuilder.create().texOffs(0, 24).mirror()
                        .addBox(-2.4F, 8.0F, -3.0F, 5.0F, 2.0F, 6.0F).mirror(false),
                PartPose.ZERO);
        return LayerDefinition.create(mesh, 32, 32);
    }

    /**
     * Tape across the mouth. Adapted from upstream's {@code DuckTapeHeadModel}.
     *
     * <p>Upstream places it with a 24-unit offset and a -28.5 box origin, which cancel out; the box
     * here is written at its resolved position rather than as that pair, because a coordinate that is
     * only correct in combination with an offset elsewhere is a coordinate nobody can check.
     */
    private static LayerDefinition tapeHead() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        root.addOrReplaceChild(HEAD,
                CubeListBuilder.create().texOffs(0, 1).addBox(-4.5F, -4.5F, -4.25F, 9.0F, 5.0F, 3.0F),
                PartPose.ZERO);
        return LayerDefinition.create(mesh, 32, 32);
    }

    /** A sack over the head. Adapted from upstream's {@code BundleModel}; its own 64 x 64 sheet. */
    private static LayerDefinition hood() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        root.addOrReplaceChild(HEAD,
                CubeListBuilder.create().texOffs(0, 0).addBox(-4.5F, -8.5F, -4.5F, 9.0F, 9.0F, 9.0F),
                PartPose.ZERO);
        return LayerDefinition.create(mesh, 64, 64);
    }
}
