package dev.otectus.mcacrime.restraint;

import dev.otectus.mcacrime.McaCrime;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The nine restraint definitions, in code (§3.1).
 *
 * <p>In code rather than in a datapack because slot semantics are an authorisation decision:
 * whether arm handcuffs take the hands away is not a server's cosmetic choice, and a datapack that
 * could introduce a new definition id could introduce one nothing validates. §3.13 keeps the tuning
 * numbers — durability, pick difficulty, supported rigs — data-driven on top of this table; the
 * table itself is the closed set.
 *
 * <p>Every accessor returns the immutable definition record. Nothing here is per-subject state, and
 * nothing hands out something a caller could mutate: that is the upstream defect this class is
 * shaped to avoid.
 *
 * <h2>The §7.1 matrix</h2>
 * The restriction column of each entry below <em>is</em> the specification's authoritative matrix.
 * Two rows are deliberate departures, both recorded in §3.4:
 * <ul>
 *   <li>leg shackles allow walking and block only jump and sprint, resolving the source's own
 *       client/server disagreement in favour of walking (which is also what its code does);</li>
 *   <li>the head slot does not gag text chat. {@link RestrictionPolicy#voiceGag()} is about a voice
 *       mod; typing is a {@link ProtectedAction}.</li>
 * </ul>
 */
public final class RestraintDefinitions {

    // --- definition ids (§3.1: a separate namespace from item ids) ---
    public static final ResourceLocation HANDCUFFS_ARMS = id("handcuffs_arms");
    public static final ResourceLocation HANDCUFFS_LEGS = id("handcuffs_legs");
    public static final ResourceLocation SHACKLES_ARMS = id("shackles_arms");
    public static final ResourceLocation SHACKLES_LEGS = id("shackles_legs");
    public static final ResourceLocation DUCK_TAPE_ARMS = id("duck_tape_arms");
    public static final ResourceLocation DUCK_TAPE_LEGS = id("duck_tape_legs");
    public static final ResourceLocation DUCK_TAPE_HEAD = id("duck_tape_head");
    public static final ResourceLocation BUNDLE = id("bundle");
    public static final ResourceLocation PILLORY = id("pillory");

    // --- the items definitions name (§3.1: the two protected ids keep their paths) ---
    /** The handcuff family's item: MCA: Crime's protected locked-cuff icon. */
    public static final ResourceLocation ITEM_LOCKED_CUFFS = id("restraint_locked_cuffs");
    /** The shackle family's item: MCA: Crime's protected regular-cuff icon. */
    public static final ResourceLocation ITEM_CUFFS = id("restraint_cuffs");
    public static final ResourceLocation ITEM_DUCK_TAPE = id("duck_tape");
    public static final ResourceLocation ITEM_PILLORY = id("pillory");
    public static final ResourceLocation ITEM_BUNDLE = new ResourceLocation("minecraft", "bundle");

    // --- keys: a key opens a family, not a definition (§3.7) ---
    public static final ResourceLocation ITEM_HANDCUFFS_KEY = id("handcuffs_key");
    public static final ResourceLocation ITEM_SHACKLES_KEY = id("shackles_key");

    // --- the two named application sounds (Appendix A.2). Tape, hood and device sounds are M2's. ---
    private static final ResourceLocation SOUND_APPLY_HANDCUFFS = id("restraint.apply_handcuffs");
    private static final ResourceLocation SOUND_APPLY_SHACKLES = id("restraint.apply_shackles");
    private static final ResourceLocation SOUND_PILLORY = id("block.pillory.use");

    /** Source-configured durability, not the placeholder 999 in upstream item registration. */
    private static final int DURABILITY_HANDCUFFS = 40;
    private static final int DURABILITY_SHACKLES = 15;
    /** Tape is five work units, and arm and leg tape are configured independently. */
    private static final int DURABILITY_TAPE = 5;
    private static final int DURABILITY_HOOD = 5;

    /** The pre-Unbreaking struggle roll, §3.5. */
    private static final double BASE_BREAK_CHANCE = 0.5D;

    private static final Map<ResourceLocation, RestraintDefinition> DEFINITIONS = build();

    /**
     * Definitions another mod added through {@code api/RestraintRegistrationApi} (0.7.5 M6.4).
     *
     * <p>A second map rather than a mutable first one, and that is the whole safety argument: the ten
     * shipped definitions are still an immutable {@code Map.copyOf} that nothing can replace an entry
     * in, and a third-party registration can only ever <em>add</em> an id nobody else holds.
     */
    private static final Map<ResourceLocation, RestraintDefinition> THIRD_PARTY =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Closed once mod setup has finished; see the API class for why the window exists. */
    private static volatile boolean registrationOpen = true;

    private RestraintDefinitions() {
    }

    /** Whether third-party registrations are still being accepted. */
    public static boolean registrationOpen() {
        return registrationOpen;
    }

    /** Closes the window. Called once, at the end of common setup. */
    public static void closeRegistration() {
        registrationOpen = false;
    }

    /** Reopens it. Test-only: a test that registered a definition must be able to undo that. */
    public static void resetThirdParty() {
        THIRD_PARTY.clear();
        registrationOpen = true;
    }

    /** How many third-party definitions exist. */
    public static int thirdPartyCount() {
        return THIRD_PARTY.size();
    }

    /** The registration itself. Every refusal is a value; nothing here throws. */
    public static dev.otectus.mcacrime.api.RestraintRegistrationApi.Result registerThirdParty(
            RestraintDefinition definition) {
        if (!registrationOpen) {
            return dev.otectus.mcacrime.api.RestraintRegistrationApi.Result.TOO_LATE;
        }
        ResourceLocation id = definition.id();
        if (DEFINITIONS.containsKey(id) || THIRD_PARTY.containsKey(id)) {
            return dev.otectus.mcacrime.api.RestraintRegistrationApi.Result.DUPLICATE;
        }
        if (THIRD_PARTY.size() >= dev.otectus.mcacrime.api.RestraintRegistrationApi
                .MAX_THIRD_PARTY_DEFINITIONS) {
            return dev.otectus.mcacrime.api.RestraintRegistrationApi.Result.FULL;
        }
        THIRD_PARTY.put(id, definition);
        return dev.otectus.mcacrime.api.RestraintRegistrationApi.Result.ACCEPTED;
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation(McaCrime.MOD_ID, path);
    }

    private static Map<ResourceLocation, RestraintDefinition> build() {
        Map<ResourceLocation, RestraintDefinition> map = new LinkedHashMap<>();

        // Arm handcuffs: no mining, no hand actions, walking and jumping intact.
        put(map, new RestraintDefinition(HANDCUFFS_ARMS, Optional.of(RestraintSlot.ARMS),
                Optional.of(RestraintFamily.HANDCUFFS), Optional.of(ITEM_LOCKED_CUFFS),
                Optional.of(ITEM_HANDCUFFS_KEY),
                RestrictionPolicy.deny().mining().handActions().build(),
                new RestraintDefinition.EscapeProfile(DURABILITY_HANDCUFFS, true, BASE_BREAK_CHANCE, false),
                RestraintDefinition.PickProfile.of(6, 12),
                new RestraintDefinition.RenderProfile(RestraintDefinition.RestraintPose.ARMS_BOUND, false),
                RigProfile::arms, Set.of(),
                RestraintDefinition.Statistics.forPrefix(McaCrime.MOD_ID, "handcuffs"),
                Optional.of(SOUND_APPLY_HANDCUFFS), 1));

        // Leg handcuffs: mining and hands intact, no voluntary movement, no jump, no vehicle control.
        put(map, new RestraintDefinition(HANDCUFFS_LEGS, Optional.of(RestraintSlot.LEGS),
                Optional.of(RestraintFamily.HANDCUFFS), Optional.of(ITEM_LOCKED_CUFFS),
                Optional.of(ITEM_HANDCUFFS_KEY),
                RestrictionPolicy.deny().walking().jumpingAndSprinting().vehicleControl().build(),
                new RestraintDefinition.EscapeProfile(DURABILITY_HANDCUFFS, true, BASE_BREAK_CHANCE, false),
                RestraintDefinition.PickProfile.of(6, 12),
                new RestraintDefinition.RenderProfile(RestraintDefinition.RestraintPose.LEGS_BOUND, false),
                RigProfile::legs, Set.of(),
                RestraintDefinition.Statistics.forPrefix(McaCrime.MOD_ID, "legcuffs"),
                Optional.of(SOUND_APPLY_HANDCUFFS), 1));

        // Arm shackles: weaker. No mining, but the hands stay useful.
        put(map, new RestraintDefinition(SHACKLES_ARMS, Optional.of(RestraintSlot.ARMS),
                Optional.of(RestraintFamily.SHACKLES), Optional.of(ITEM_CUFFS),
                Optional.of(ITEM_SHACKLES_KEY),
                RestrictionPolicy.deny().mining().build(),
                new RestraintDefinition.EscapeProfile(DURABILITY_SHACKLES, true, BASE_BREAK_CHANCE, false),
                RestraintDefinition.PickProfile.of(8, 10),
                new RestraintDefinition.RenderProfile(RestraintDefinition.RestraintPose.ARMS_LOOSE, false),
                RigProfile::arms, Set.of(),
                RestraintDefinition.Statistics.forPrefix(McaCrime.MOD_ID, "shackles"),
                Optional.of(SOUND_APPLY_SHACKLES), 1));

        // Leg shackles: walking allowed (§3.4), jump and sprint blocked.
        put(map, new RestraintDefinition(SHACKLES_LEGS, Optional.of(RestraintSlot.LEGS),
                Optional.of(RestraintFamily.SHACKLES), Optional.of(ITEM_CUFFS),
                Optional.of(ITEM_SHACKLES_KEY),
                RestrictionPolicy.deny().jumpingAndSprinting().build(),
                new RestraintDefinition.EscapeProfile(DURABILITY_SHACKLES, true, BASE_BREAK_CHANCE, false),
                RestraintDefinition.PickProfile.of(8, 10),
                new RestraintDefinition.RenderProfile(RestraintDefinition.RestraintPose.LEGS_BOUND, false),
                RigProfile::legs, Set.of(),
                RestraintDefinition.Statistics.forPrefix(McaCrime.MOD_ID, "leg_shackles"),
                Optional.of(SOUND_APPLY_SHACKLES), 1));

        // Arm tape: as handcuffs, but weak and no metal key — a blade ends it.
        put(map, new RestraintDefinition(DUCK_TAPE_ARMS, Optional.of(RestraintSlot.ARMS),
                Optional.of(RestraintFamily.TAPE), Optional.of(ITEM_DUCK_TAPE), Optional.empty(),
                RestrictionPolicy.deny().mining().handActions().build(),
                new RestraintDefinition.EscapeProfile(DURABILITY_TAPE, true, BASE_BREAK_CHANCE, true),
                RestraintDefinition.PickProfile.unpickable(),
                new RestraintDefinition.RenderProfile(RestraintDefinition.RestraintPose.ARMS_BOUND, false),
                RigProfile::arms, Set.of(), RestraintDefinition.Statistics.none(), Optional.empty(), 1));

        // Leg tape: weak leg bind. Its own settings, never the arm ones (the upstream defect §2.2.22).
        put(map, new RestraintDefinition(DUCK_TAPE_LEGS, Optional.of(RestraintSlot.LEGS),
                Optional.of(RestraintFamily.TAPE), Optional.of(ITEM_DUCK_TAPE), Optional.empty(),
                RestrictionPolicy.deny().walking().jumpingAndSprinting().vehicleControl().build(),
                new RestraintDefinition.EscapeProfile(DURABILITY_TAPE, true, BASE_BREAK_CHANCE, true),
                RestraintDefinition.PickProfile.unpickable(),
                new RestraintDefinition.RenderProfile(RestraintDefinition.RestraintPose.LEGS_BOUND, false),
                RigProfile::legs, Set.of(), RestraintDefinition.Statistics.none(), Optional.empty(), 1));

        // Head tape: restricts nothing by itself. Voice gag only, and only with a voice mod present.
        put(map, new RestraintDefinition(DUCK_TAPE_HEAD, Optional.of(RestraintSlot.HEAD),
                Optional.of(RestraintFamily.TAPE), Optional.of(ITEM_DUCK_TAPE), Optional.empty(),
                RestrictionPolicy.deny().voiceGag().build(),
                new RestraintDefinition.EscapeProfile(DURABILITY_TAPE, true, BASE_BREAK_CHANCE, true),
                RestraintDefinition.PickProfile.unpickable(),
                new RestraintDefinition.RenderProfile(RestraintDefinition.RestraintPose.NONE, false),
                RigProfile::head, Set.of(), RestraintDefinition.Statistics.none(), Optional.empty(), 1));

        // Bundle hood: obscured vision, nothing else. Text chat is explicitly not gagged.
        put(map, new RestraintDefinition(BUNDLE, Optional.of(RestraintSlot.HEAD),
                Optional.of(RestraintFamily.HOOD), Optional.of(ITEM_BUNDLE), Optional.empty(),
                RestrictionPolicy.deny().obscureVision().build(),
                new RestraintDefinition.EscapeProfile(DURABILITY_HOOD, true, BASE_BREAK_CHANCE, false),
                RestraintDefinition.PickProfile.unpickable(),
                new RestraintDefinition.RenderProfile(RestraintDefinition.RestraintPose.HOODED, true),
                RigProfile::head, Set.of(), RestraintDefinition.Statistics.none(), Optional.empty(), 1));

        // Pillory: a device, not gear. Occupies no slot; its restrictions compose over worn gear and
        // its escape work lives on the detention record.
        put(map, new RestraintDefinition(PILLORY, Optional.empty(), Optional.empty(),
                Optional.of(ITEM_PILLORY), Optional.empty(),
                RestrictionPolicy.deny().mining().handActions().walking().jumpingAndSprinting()
                        .vehicleControl().build(),
                RestraintDefinition.EscapeProfile.none(),
                RestraintDefinition.PickProfile.unpickable(),
                new RestraintDefinition.RenderProfile(RestraintDefinition.RestraintPose.DETAINED, false),
                rig -> true, Set.of(), RestraintDefinition.Statistics.none(),
                Optional.of(SOUND_PILLORY), 1));

        return Map.copyOf(map);
    }

    private static void put(Map<ResourceLocation, RestraintDefinition> map, RestraintDefinition definition) {
        map.put(definition.id(), definition);
    }

    /**
     * Every definition, ours first in declaration order and third-party ones after them.
     *
     * <p>Effective definitions: a loaded {@code restraint_profiles} datapack has already been applied
     * (§3.13). {@link #base} is the code table, for the loader that validates against it.
     */
    public static List<RestraintDefinition> all() {
        List<RestraintDefinition> out = new java.util.ArrayList<>(DEFINITIONS.size() + THIRD_PARTY.size());
        for (RestraintDefinition definition : DEFINITIONS.values()) {
            out.add(RestraintProfileOverrides.effective(definition));
        }
        for (RestraintDefinition definition : THIRD_PARTY.values()) {
            out.add(RestraintProfileOverrides.effective(definition));
        }
        return List.copyOf(out);
    }

    /**
     * One definition <em>as written in code</em>, with no datapack layer over it.
     *
     * <p>Exists for exactly two callers: the profile loader, which validates a file against the
     * definition it claims to override, and {@link RestraintProfileOverrides}, which derives the
     * effective record from it. Gameplay uses {@link #get}.
     */
    public static Optional<RestraintDefinition> base(@Nullable ResourceLocation id) {
        if (id == null) {
            return Optional.empty();
        }
        RestraintDefinition ours = DEFINITIONS.get(id);
        return Optional.ofNullable(ours != null ? ours : THIRD_PARTY.get(id));
    }

    /** The key item a family's key is, or empty for a keyless family (tape, hoods). */
    public static Optional<ResourceLocation> keyItemFor(@Nullable RestraintFamily family) {
        if (family == null) {
            return Optional.empty();
        }
        return switch (family) {
            case HANDCUFFS -> Optional.of(ITEM_HANDCUFFS_KEY);
            case SHACKLES -> Optional.of(ITEM_SHACKLES_KEY);
            case TAPE, HOOD, LEGACY_ROPE -> Optional.empty();
        };
    }

    /** Every definition that is worn on a body slot; the device definitions are excluded. */
    public static List<RestraintDefinition> wearable() {
        return all().stream().filter(definition -> !definition.device()).toList();
    }

    /** Every definition worn on {@code slot}. */
    public static List<RestraintDefinition> forSlot(@Nullable RestraintSlot slot) {
        return all().stream()
                .filter(definition -> slot != null && definition.slot().filter(slot::equals).isPresent())
                .toList();
    }

    /**
     * One definition by id, or empty when nothing is registered under it.
     *
     * <p>The <em>effective</em> definition: durability, restrictions, pick numbers, key family and
     * the rig predicate are whatever a loaded {@code restraint_profiles} file says they are (§3.13).
     * Every gameplay reader goes through here, so there is one place the datapack layer applies
     * rather than one per value.
     */
    public static Optional<RestraintDefinition> get(@Nullable ResourceLocation id) {
        return base(id).map(RestraintProfileOverrides::effective);
    }

    /** True when {@code id} names a definition: one of the ten, or a registered third-party one. */
    public static boolean exists(@Nullable ResourceLocation id) {
        return id != null && (DEFINITIONS.containsKey(id) || THIRD_PARTY.containsKey(id));
    }

    /** Every definition a single key item opens (§3.7: keys open families). */
    public static List<RestraintDefinition> openedBy(@Nullable ResourceLocation keyItemId) {
        return all().stream().filter(definition -> definition.openedBy(keyItemId)).toList();
    }
}
