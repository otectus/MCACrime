package dev.otectus.mcacrime.compat.townstead;

import dev.otectus.mcacrime.compat.TownsteadBuildingView;
import dev.otectus.mcacrime.compat.TownsteadCalendarView;
import dev.otectus.mcacrime.compat.TownsteadCapability;
import dev.otectus.mcacrime.compat.TownsteadLifeStageView;
import dev.otectus.mcacrime.compat.TownsteadNeedsView;
import dev.otectus.mcacrime.compat.TownsteadQueryResult;
import dev.otectus.mcacrime.compat.TownsteadScheduleView;
import dev.otectus.mcacrime.compat.TownsteadSpiritView;
import dev.otectus.mcacrime.compat.TownsteadVillagerView;
import dev.otectus.mcacrime.compat.mca.McaHandles;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.lang.invoke.MethodHandle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The typed facade over {@link TownsteadBinding}'s resolved handles: Townstead objects go in, MCA:
 * Crime's own view records come out, and <b>no Townstead value ever escapes this class</b>.
 *
 * <p>Handles are {@code private static final} so HotSpot can constant-fold them at the call site; a
 * per-call map lookup would forfeit that. Every read swallows {@link Throwable} and returns the
 * unavailable result, because an unbound member is a stub whose invocation is legal but meaningless,
 * and because a read is never worth a crash inside a guard scan.
 *
 * <p>Enums are converted with {@link Enum#name()} lowercased rather than returned as-is, so a
 * Townstead enum constant cannot leak into a view record and become linkage.
 *
 * @see TownsteadBinding for the manifest these handles come from
 */
final class TownsteadHandles {

    private static final TownsteadBinding.Resolution R = resolveQuietly();

    private static TownsteadBinding.Resolution resolveQuietly() {
        try {
            return TownsteadBinding.resolveAgainst(TownsteadHandles.class.getClassLoader());
        } catch (Throwable t) {
            return TownsteadBinding.absent();
        }
    }

    /** The live resolution, for the diagnostics command and the one line logged at server start. */
    static TownsteadBinding.Resolution resolution() {
        return R;
    }

    // --- handles ---------------------------------------------------------------------------------

    private static final MethodHandle H_ENTITY = R.handle(TownsteadBinding.API_ENTITY);
    private static final MethodHandle H_CALENDAR = R.handle(TownsteadBinding.API_CALENDAR);
    private static final MethodHandle H_BUILDING_AT = R.handle(TownsteadBinding.API_BUILDING_AT);
    private static final MethodHandle H_ORIGIN = R.handle(TownsteadBinding.API_ORIGIN);

    private static final MethodHandle H_V_UUID = R.handle(TownsteadBinding.V_UUID);
    private static final MethodHandle H_V_NAME = R.handle(TownsteadBinding.V_NAME);
    private static final MethodHandle H_V_ENTITY_TYPE = R.handle(TownsteadBinding.V_ENTITY_TYPE);
    private static final MethodHandle H_V_ROOT_ID = R.handle(TownsteadBinding.V_ROOT_ID);
    private static final MethodHandle H_V_LIFE_STAGE = R.handle(TownsteadBinding.V_LIFE_STAGE);
    private static final MethodHandle H_V_AGE_DAYS = R.handle(TownsteadBinding.V_AGE_DAYS);
    private static final MethodHandle H_V_AGE_YEARS = R.handle(TownsteadBinding.V_AGE_YEARS);
    private static final MethodHandle H_V_IMMORTAL = R.handle(TownsteadBinding.V_IMMORTAL);
    private static final MethodHandle H_V_AGELESS = R.handle(TownsteadBinding.V_AGELESS);
    private static final MethodHandle H_V_SENIOR = R.handle(TownsteadBinding.V_SENIOR);
    private static final MethodHandle H_V_PERSONALITY = R.handle(TownsteadBinding.V_PERSONALITY);
    private static final MethodHandle H_V_FERTILITY = R.handle(TownsteadBinding.V_FERTILITY);
    private static final MethodHandle H_V_PROFESSION_ID = R.handle(TownsteadBinding.V_PROFESSION_ID);
    private static final MethodHandle H_V_PROFESSION_LEVEL = R.handle(TownsteadBinding.V_PROFESSION_LEVEL);
    private static final MethodHandle H_V_PROFESSION_XP = R.handle(TownsteadBinding.V_PROFESSION_XP);
    private static final MethodHandle H_V_NEEDS = R.handle(TownsteadBinding.V_NEEDS);
    private static final MethodHandle H_V_SCHEDULE = R.handle(TownsteadBinding.V_SCHEDULE);

    private static final MethodHandle H_N_HUNGER = R.handle(TownsteadBinding.N_HUNGER);
    private static final MethodHandle H_N_SATURATION = R.handle(TownsteadBinding.N_SATURATION);
    private static final MethodHandle H_N_HUNGER_EXH = R.handle(TownsteadBinding.N_HUNGER_EXHAUSTION);
    private static final MethodHandle H_N_THIRST = R.handle(TownsteadBinding.N_THIRST);
    private static final MethodHandle H_N_QUENCHED = R.handle(TownsteadBinding.N_QUENCHED);
    private static final MethodHandle H_N_THIRST_EXH = R.handle(TownsteadBinding.N_THIRST_EXHAUSTION);
    private static final MethodHandle H_N_FATIGUE = R.handle(TownsteadBinding.N_FATIGUE);
    private static final MethodHandle H_N_COLLAPSED = R.handle(TownsteadBinding.N_COLLAPSED);
    private static final MethodHandle H_N_GATED = R.handle(TownsteadBinding.N_GATED);

    private static final MethodHandle H_S_MODE = R.handle(TownsteadBinding.S_MODE);
    private static final MethodHandle H_S_TEMPLATE_ID = R.handle(TownsteadBinding.S_TEMPLATE_ID);
    private static final MethodHandle H_S_CUSTOM_SHIFTS = R.handle(TownsteadBinding.S_CUSTOM_SHIFTS);
    private static final MethodHandle H_S_NON_DEFAULT = R.handle(TownsteadBinding.S_NON_DEFAULT_SHIFTS);
    private static final MethodHandle H_S_TICK_HOUR = R.handle(TownsteadBinding.S_TICK_HOUR);
    private static final MethodHandle H_S_DISPLAY_HOUR = R.handle(TownsteadBinding.S_DISPLAY_HOUR);
    private static final MethodHandle H_S_SHIFT_ORDINAL = R.handle(TownsteadBinding.S_SHIFT_ORDINAL);
    private static final MethodHandle H_S_CURRENT_ACTIVITY = R.handle(TownsteadBinding.S_CURRENT_ACTIVITY);
    private static final MethodHandle H_S_PLANNED_ACTIVITY = R.handle(TownsteadBinding.S_PLANNED_ACTIVITY);
    private static final MethodHandle H_S_CURRENT_TEMPLATE = R.handle(TownsteadBinding.S_CURRENT_TEMPLATE);
    private static final MethodHandle H_S_SHIFTS = R.handle(TownsteadBinding.S_SHIFTS);
    private static final MethodHandle H_S_WEEKDAYS = R.handle(TownsteadBinding.S_WEEKDAY_TEMPLATES);

    private static final MethodHandle H_K_PROFILE_ID = R.handle(TownsteadBinding.K_PROFILE_ID);
    private static final MethodHandle H_K_WORLD_DAY = R.handle(TownsteadBinding.K_WORLD_DAY);
    private static final MethodHandle H_K_EPOCH_OFFSET = R.handle(TownsteadBinding.K_EPOCH_OFFSET);
    private static final MethodHandle H_K_TIME_MODE = R.handle(TownsteadBinding.K_TIME_MODE);
    private static final MethodHandle H_K_YEAR = R.handle(TownsteadBinding.K_YEAR);
    private static final MethodHandle H_K_MONTH = R.handle(TownsteadBinding.K_MONTH);
    private static final MethodHandle H_K_DAY = R.handle(TownsteadBinding.K_DAY);
    private static final MethodHandle H_K_DAY_OF_YEAR = R.handle(TownsteadBinding.K_DAY_OF_YEAR);
    private static final MethodHandle H_K_DAY_OF_WEEK = R.handle(TownsteadBinding.K_DAY_OF_WEEK);
    private static final MethodHandle H_K_SEASON = R.handle(TownsteadBinding.K_SEASON);

    private static final MethodHandle H_B_ID = R.handle(TownsteadBinding.B_ID);
    private static final MethodHandle H_B_VILLAGE_ID = R.handle(TownsteadBinding.B_VILLAGE_ID);
    private static final MethodHandle H_B_TYPE = R.handle(TownsteadBinding.B_TYPE);
    private static final MethodHandle H_B_SIZE = R.handle(TownsteadBinding.B_SIZE);
    private static final MethodHandle H_B_CENTER_X = R.handle(TownsteadBinding.B_CENTER_X);
    private static final MethodHandle H_B_CENTER_Y = R.handle(TownsteadBinding.B_CENTER_Y);
    private static final MethodHandle H_B_CENTER_Z = R.handle(TownsteadBinding.B_CENTER_Z);
    private static final MethodHandle H_B_MIN_X = R.handle(TownsteadBinding.B_MIN_X);
    private static final MethodHandle H_B_MIN_Y = R.handle(TownsteadBinding.B_MIN_Y);
    private static final MethodHandle H_B_MIN_Z = R.handle(TownsteadBinding.B_MIN_Z);
    private static final MethodHandle H_B_MAX_X = R.handle(TownsteadBinding.B_MAX_X);
    private static final MethodHandle H_B_MAX_Y = R.handle(TownsteadBinding.B_MAX_Y);
    private static final MethodHandle H_B_MAX_Z = R.handle(TownsteadBinding.B_MAX_Z);

    private static final MethodHandle H_R_LIFE_STAGES = R.handle(TownsteadBinding.R_LIFE_STAGES);
    private static final MethodHandle H_LS_ID = R.handle(TownsteadBinding.LS_ID);
    private static final MethodHandle H_LS_LABEL = R.handle(TownsteadBinding.LS_LABEL);
    private static final MethodHandle H_LS_DAYS = R.handle(TownsteadBinding.LS_DAYS);
    private static final MethodHandle H_LS_SCALE = R.handle(TownsteadBinding.LS_SCALE);
    private static final MethodHandle H_LS_PRESENTS_AS = R.handle(TownsteadBinding.LS_PRESENTS_AS);
    private static final MethodHandle H_LS_NARRATIVE_START = R.handle(TownsteadBinding.LS_NARRATIVE_START);
    private static final MethodHandle H_LS_NARRATIVE_END = R.handle(TownsteadBinding.LS_NARRATIVE_END);

    private static final MethodHandle H_ROOT_LIFE_CYCLE = R.handle(TownsteadBinding.ROOT_LIFE_CYCLE);
    private static final MethodHandle H_CYCLE_FIND_BY_ID = R.handle(TownsteadBinding.CYCLE_FIND_BY_ID);
    private static final MethodHandle H_STAGE_MOBILE = R.handle(TownsteadBinding.STAGE_MOBILE);
    private static final MethodHandle H_STAGE_NEEDS = R.handle(TownsteadBinding.STAGE_NEEDS);
    private static final MethodHandle H_STAGE_TALKABLE = R.handle(TownsteadBinding.STAGE_TALKABLE);
    private static final MethodHandle H_STAGE_RIG = R.handle(TownsteadBinding.STAGE_RIG);

    private static final MethodHandle H_SPIRIT_TOTALS_FOR = R.handle(TownsteadBinding.SPIRIT_TOTALS_FOR);
    private static final MethodHandle H_SPIRIT_READOUT_FOR = R.handle(TownsteadBinding.SPIRIT_READOUT_FOR);
    private static final MethodHandle H_ST_PER_SPIRIT = R.handle(TownsteadBinding.ST_PER_SPIRIT);
    private static final MethodHandle H_ST_TOTAL = R.handle(TownsteadBinding.ST_TOTAL);
    private static final MethodHandle H_ST_CONTRIBUTING = R.handle(TownsteadBinding.ST_CONTRIBUTING);
    private static final MethodHandle H_SR_CLASSIFICATION = R.handle(TownsteadBinding.SR_CLASSIFICATION);
    private static final MethodHandle H_SR_TIER_INDEX = R.handle(TownsteadBinding.SR_TIER_INDEX);
    private static final MethodHandle H_SR_PRIMARY = R.handle(TownsteadBinding.SR_PRIMARY);
    private static final MethodHandle H_SR_SECONDARY = R.handle(TownsteadBinding.SR_SECONDARY);

    private static final MethodHandle H_REACTION_ON_TASK = R.handle(TownsteadBinding.REACTION_ON_TASK);

    private static final MethodHandle H_VD_GET = R.handle(TownsteadBinding.VD_GET);
    private static final MethodHandle H_VD_GET_RECORD = R.handle(TownsteadBinding.VD_GET_RECORD);
    private static final MethodHandle H_VR_REVISION = R.handle(TownsteadBinding.VR_REVISION);
    private static final MethodHandle H_VR_BUILDINGS = R.handle(TownsteadBinding.VR_BUILDINGS);
    private static final MethodHandle H_BO_KIND = R.handle(TownsteadBinding.BO_KIND);
    private static final MethodHandle H_BO_TYPE = R.handle(TownsteadBinding.BO_TYPE);
    private static final MethodHandle H_BO_BOUNDS = R.handle(TownsteadBinding.BO_BOUNDS);

    private static final MethodHandle H_VC_START = R.handle(TownsteadBinding.VC_START);
    private static final MethodHandle H_VC_IS_CONSUMING = R.handle(TownsteadBinding.VC_IS_CONSUMING);

    // --- reads -----------------------------------------------------------------------------------

    /**
     * Townstead's snapshot of any entity, normalised.
     *
     * <p>Unavailable for a non-villager, for a villager Townstead has no state for, and when
     * {@code READ_VILLAGER} did not bind. An unparseable UUID is unavailable rather than a view with a
     * synthetic identity: everything downstream keys villagers by this value, and a made-up one would
     * silently bind the wrong villager.
     */
    static TownsteadQueryResult<TownsteadVillagerView> villager(@Nullable Entity entity) {
        if (!R.has(TownsteadCapability.READ_VILLAGER)) {
            return TownsteadQueryResult.missing(TownsteadCapability.READ_VILLAGER);
        }
        if (entity == null) {
            return TownsteadQueryResult.unavailable("no entity");
        }
        Object snapshot = statik(H_ENTITY, entity);
        if (snapshot == null) {
            return TownsteadQueryResult.unavailable("Townstead has no state for this entity");
        }
        UUID uuid = uuid(str(H_V_UUID, snapshot));
        if (uuid == null) {
            return TownsteadQueryResult.failed("Townstead returned an unreadable villager uuid");
        }
        String rootId = str(H_V_ROOT_ID, snapshot);
        String stageId = str(H_V_LIFE_STAGE, snapshot);
        return TownsteadQueryResult.available(new TownsteadVillagerView(
                uuid,
                str(H_V_NAME, snapshot),
                str(H_V_ENTITY_TYPE, snapshot),
                rootId,
                stageId,
                lng(H_V_AGE_DAYS, snapshot),
                integer(H_V_AGE_YEARS, snapshot),
                bool(H_V_IMMORTAL, snapshot),
                bool(H_V_AGELESS, snapshot),
                bool(H_V_SENIOR, snapshot),
                str(H_V_PERSONALITY, snapshot),
                str(H_V_PROFESSION_ID, snapshot),
                integer(H_V_PROFESSION_LEVEL, snapshot),
                integer(H_V_PROFESSION_XP, snapshot),
                flt(H_V_FERTILITY, snapshot),
                schedule(ref(H_V_SCHEDULE, snapshot)),
                needs(ref(H_V_NEEDS, snapshot), rootId, stageId)));
    }

    static TownsteadQueryResult<TownsteadNeedsView> needs(@Nullable Entity entity) {
        if (!R.has(TownsteadCapability.READ_NEEDS)) {
            return TownsteadQueryResult.missing(TownsteadCapability.READ_NEEDS);
        }
        return villager(entity).map(TownsteadVillagerView::needs);
    }

    static TownsteadQueryResult<TownsteadScheduleView> schedule(@Nullable Entity entity) {
        if (!R.has(TownsteadCapability.READ_SCHEDULE)) {
            return TownsteadQueryResult.missing(TownsteadCapability.READ_SCHEDULE);
        }
        return villager(entity).map(TownsteadVillagerView::schedule);
    }

    /**
     * The life stage this villager is in, with Townstead's own stage behaviour flags when they bind.
     *
     * <p>Two capabilities, deliberately: the stage's shape comes from the public root catalogue
     * ({@code READ_ROOT}), while {@code mobile}/{@code needs}/{@code talkable} live on Townstead's
     * internal stage record ({@code STAGE_CAPABILITIES}). Missing the second yields a view whose flags
     * are all true and whose {@code flagsKnown} is false, which is the safe direction: a villager MCA:
     * Crime cannot confirm is frozen must be treated as an ordinary participant.
     */
    static TownsteadQueryResult<TownsteadLifeStageView> lifeStage(@Nullable Entity entity) {
        if (!R.has(TownsteadCapability.READ_ROOT)) {
            return TownsteadQueryResult.missing(TownsteadCapability.READ_ROOT);
        }
        TownsteadQueryResult<TownsteadVillagerView> snapshot = villager(entity);
        TownsteadVillagerView view = snapshot.orElse(null);
        if (view == null) {
            // Carry the villager query's own answer across rather than inventing a new one: "Townstead
            // has no state for this entity" and "the read failed" must not collapse into one reason.
            return snapshot.isFailed()
                    ? TownsteadQueryResult.failed(snapshot.describe())
                    : TownsteadQueryResult.unavailable(snapshot.describe());
        }
        if (view.rootId().isEmpty() || view.lifeStageId().isEmpty()) {
            return TownsteadQueryResult.unavailable("this entity has no Townstead life stage");
        }
        Object stage = stageSnapshot(view.rootId(), view.lifeStageId());
        if (stage == null) {
            return TownsteadQueryResult.unavailable("no life stage '" + view.lifeStageId()
                    + "' in root '" + view.rootId() + "'");
        }
        StageFlags flags = stageFlags(view.rootId(), view.lifeStageId());
        return TownsteadQueryResult.available(new TownsteadLifeStageView(
                str(H_LS_ID, stage),
                str(H_LS_LABEL, stage),
                integer(H_LS_DAYS, stage),
                flt(H_LS_SCALE, stage),
                str(H_LS_PRESENTS_AS, stage),
                flt(H_LS_NARRATIVE_START, stage),
                flt(H_LS_NARRATIVE_END, stage),
                flags.known(), flags.mobile(), flags.needs(), flags.talkable(), flags.rig()));
    }

    static TownsteadQueryResult<TownsteadCalendarView> calendar(@Nullable MinecraftServer server) {
        if (!R.has(TownsteadCapability.READ_CALENDAR)) {
            return TownsteadQueryResult.missing(TownsteadCapability.READ_CALENDAR);
        }
        if (server == null) {
            return TownsteadQueryResult.unavailable("no server");
        }
        Object snapshot = statik(H_CALENDAR, server);
        if (snapshot == null) {
            return TownsteadQueryResult.unavailable("Townstead has no calendar for this server");
        }
        return TownsteadQueryResult.available(new TownsteadCalendarView(
                str(H_K_PROFILE_ID, snapshot),
                lng(H_K_WORLD_DAY, snapshot),
                integer(H_K_EPOCH_OFFSET, snapshot),
                str(H_K_TIME_MODE, snapshot),
                integer(H_K_YEAR, snapshot),
                integer(H_K_MONTH, snapshot),
                integer(H_K_DAY, snapshot),
                integer(H_K_DAY_OF_YEAR, snapshot),
                integer(H_K_DAY_OF_WEEK, snapshot),
                str(H_K_SEASON, snapshot)));
    }

    static TownsteadQueryResult<TownsteadBuildingView> buildingAt(@Nullable ServerLevel level,
                                                                  @Nullable BlockPos pos) {
        if (!R.has(TownsteadCapability.READ_BUILDING)) {
            return TownsteadQueryResult.missing(TownsteadCapability.READ_BUILDING);
        }
        if (level == null || pos == null) {
            return TownsteadQueryResult.unavailable("no position");
        }
        Object snapshot = statik(H_BUILDING_AT, level, pos);
        if (snapshot == null) {
            return TownsteadQueryResult.unavailable("no registered building at this position");
        }
        return TownsteadQueryResult.available(new TownsteadBuildingView(
                integer(H_B_ID, snapshot),
                integer(H_B_VILLAGE_ID, snapshot),
                str(H_B_TYPE, snapshot),
                integer(H_B_SIZE, snapshot),
                integer(H_B_CENTER_X, snapshot),
                integer(H_B_CENTER_Y, snapshot),
                integer(H_B_CENTER_Z, snapshot),
                integer(H_B_MIN_X, snapshot),
                integer(H_B_MIN_Y, snapshot),
                integer(H_B_MIN_Z, snapshot),
                integer(H_B_MAX_X, snapshot),
                integer(H_B_MAX_Y, snapshot),
                integer(H_B_MAX_Z, snapshot),
                // The facade knows neither of these. Empty and UNKNOWN_REVISION are the honest values:
                // a building read this way cannot be compared against a stored revision at all.
                "",
                TownsteadBuildingView.UNKNOWN_REVISION));
    }

    /**
     * Every recognised building overlapping {@code pos}, across every village in the level.
     *
     * <p>The facade's {@code buildingAt} answers with the first match in the <em>nearest</em> village
     * and carries no revision, which is enough for a tooltip and not enough for a facility decision:
     * a village boundary, an overlapping dock and an enclosure round the same yard all produce more
     * than one answer, and a stored reference cannot be revalidated without a revision to compare.
     *
     * <p>Every village in the level is asked rather than only the nearest, because a position on a
     * boundary genuinely belongs to more than one and picking one by distance is the guess this method
     * exists to avoid. The cost is one map lookup per village plus its overlay set; callers are
     * expected to ask once per decision, not once per block.
     */
    static TownsteadQueryResult<List<TownsteadBuildingView>> buildingsAt(@Nullable ServerLevel level,
                                                                         @Nullable BlockPos pos) {
        if (!R.has(TownsteadCapability.BUILDING_ENUMERATION)) {
            return TownsteadQueryResult.missing(TownsteadCapability.BUILDING_ENUMERATION);
        }
        if (level == null || pos == null) {
            return TownsteadQueryResult.unavailable("no position");
        }
        Object data = savedData(level);
        if (data == null) {
            return TownsteadQueryResult.unavailable("Townstead has no village data for this server");
        }
        List<TownsteadBuildingView> overlapping = new ArrayList<>();
        for (Object village : McaHandles.villagesIn(level)) {
            int villageId = McaHandles.villageIdOf(village);
            if (villageId < 0) {
                continue;
            }
            for (TownsteadBuildingView building : buildingsOf(data, level, villageId)) {
                if (building.contains(pos.getX(), pos.getY(), pos.getZ())) {
                    overlapping.add(building);
                }
            }
        }
        // An empty list is a real answer -- "nothing recognised here" -- and is deliberately AVAILABLE
        // rather than unavailable, because a caller has to be able to tell it apart from "could not ask".
        return TownsteadQueryResult.available(List.copyOf(overlapping));
    }

    /**
     * The revision of one village's overlay record, which a stored building reference is compared
     * against.
     *
     * <p>Unavailable, never zero, for a village Townstead has never written a record for: revision 0 is
     * a real value (a record that has been touched but never had a building stored), and a stored
     * reference must not be declared fresh because the village it named has vanished.
     */
    static TownsteadQueryResult<Integer> villageRevision(@Nullable ServerLevel level, int villageId) {
        if (!R.has(TownsteadCapability.BUILDING_ENUMERATION)) {
            return TownsteadQueryResult.missing(TownsteadCapability.BUILDING_ENUMERATION);
        }
        if (level == null || villageId < 0) {
            return TownsteadQueryResult.unavailable("no village");
        }
        Object data = savedData(level);
        Object record = data == null ? null : ref(H_VD_GET_RECORD, data, level, villageId);
        if (record == null) {
            return TownsteadQueryResult.unavailable("Townstead has no record for village " + villageId);
        }
        return TownsteadQueryResult.available(integer(H_VR_REVISION, record));
    }

    /**
     * Lets a villager MCA: Crime is holding eat or drink an item that was handed to them in the cell.
     *
     * <p>Townstead's own consumption flow rather than an ad-hoc heal: it is what applies hunger,
     * thirst, purity, food effects and the container return, and a prisoner fed by any other route
     * would drift from a villager who ate the same item at a table. {@code source} is the container the
     * item came out of, so the empty bowl goes back where it belongs instead of into the prisoner's
     * pockets; null is allowed and means "no origin".
     *
     * <p>{@code AVAILABLE(false)} is an ordinary answer: the item is not edible, or this villager is
     * already eating. Only an unbound capability or a throw is anything else.
     */
    static TownsteadQueryResult<Boolean> feedInCustody(@Nullable LivingEntity prisoner,
                                                       @Nullable ItemStack food,
                                                       @Nullable BlockPos source) {
        if (!R.has(TownsteadCapability.CONSUMPTION_IN_CUSTODY)) {
            return TownsteadQueryResult.missing(TownsteadCapability.CONSUMPTION_IN_CUSTODY);
        }
        if (prisoner == null || food == null || food.isEmpty()) {
            return TownsteadQueryResult.unavailable("nothing to feed");
        }
        if (!McaHandles.isVillager(prisoner)) {
            // Townstead's consumption manager takes MCA's villager type. Handing it anything else would
            // be a ClassCastException inside the handle rather than an answer.
            return TownsteadQueryResult.unavailable("not an MCA villager");
        }
        try {
            if ((boolean) H_VC_IS_CONSUMING.invoke(prisoner)) {
                return TownsteadQueryResult.available(Boolean.FALSE);
            }
            return TownsteadQueryResult.available((boolean) H_VC_START.invoke(prisoner, food, source));
        } catch (Throwable t) {
            return TownsteadQueryResult.failed("consumption threw " + t.getClass().getSimpleName());
        }
    }

    @Nullable
    private static Object savedData(ServerLevel level) {
        MinecraftServer server = level.getServer();
        return server == null ? null : statik(H_VD_GET, server);
    }

    /**
     * One village's overlay set as MCA: Crime's own view records.
     *
     * <p>{@code buildings()} is a fastutil {@code Int2ObjectMap}, which is a {@link Map} with
     * {@link Integer} keys, so it is read as one rather than through a fastutil type this mod would
     * then have to name. Bounds are {@code [x0,y0,z0,x1,y1,z1]} and are normalised here: Townstead
     * writes dock and enclosure boxes already ordered, but the migration path writes a building's two
     * corner positions, which are not guaranteed to be.
     */
    private static List<TownsteadBuildingView> buildingsOf(Object data, ServerLevel level, int villageId) {
        Object record = ref(H_VD_GET_RECORD, data, level, villageId);
        if (record == null) {
            return List.of();
        }
        int revision = integer(H_VR_REVISION, record);
        Object buildings = ref(H_VR_BUILDINGS, record);
        if (!(buildings instanceof Map<?, ?> overlays)) {
            return List.of();
        }
        List<TownsteadBuildingView> out = new ArrayList<>(overlays.size());
        for (Map.Entry<?, ?> entry : overlays.entrySet()) {
            if (!(entry.getKey() instanceof Number id) || entry.getValue() == null) {
                continue;
            }
            Object overlay = entry.getValue();
            if (!(ref(H_BO_BOUNDS, overlay) instanceof int[] bounds) || bounds.length < 6) {
                continue; // an overlay with no geometry cannot answer any question asked of it
            }
            int minX = Math.min(bounds[0], bounds[3]);
            int minY = Math.min(bounds[1], bounds[4]);
            int minZ = Math.min(bounds[2], bounds[5]);
            int maxX = Math.max(bounds[0], bounds[3]);
            int maxY = Math.max(bounds[1], bounds[4]);
            int maxZ = Math.max(bounds[2], bounds[5]);
            out.add(new TownsteadBuildingView(id.intValue(), villageId, str(H_BO_TYPE, overlay),
                    0, // an overlay carries no size of its own; zero rather than a derived guess
                    (minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2,
                    minX, minY, minZ, maxX, maxY, maxZ,
                    str(H_BO_KIND, overlay), revision));
        }
        return List.copyOf(out);
    }

    /**
     * Village spirit. The one read that reaches past Townstead's public facade, so it is also the one
     * that needs MCA: the {@code Village} object comes from {@link McaHandles} as a plain
     * {@link Object} and crosses into Townstead through an erased handle, so neither side's type is
     * ever named here.
     */
    static TownsteadQueryResult<TownsteadSpiritView> spirit(@Nullable ServerLevel level, int villageId) {
        if (!R.has(TownsteadCapability.READ_SPIRIT)) {
            return TownsteadQueryResult.missing(TownsteadCapability.READ_SPIRIT);
        }
        if (level == null) {
            return TownsteadQueryResult.unavailable("no level");
        }
        Object village = null;
        for (Object candidate : McaHandles.villagesIn(level)) {
            if (McaHandles.villageIdOf(candidate) == villageId) {
                village = candidate;
                break;
            }
        }
        if (village == null) {
            return TownsteadQueryResult.unavailable("MCA has no village " + villageId + " in this level");
        }
        Object totals = statik(H_SPIRIT_TOTALS_FOR, village);
        if (totals == null) {
            return TownsteadQueryResult.failed("Townstead returned no spirit totals for village " + villageId);
        }
        Object readout = statik(H_SPIRIT_READOUT_FOR, totals);
        return TownsteadQueryResult.available(new TownsteadSpiritView(
                villageId,
                intMap(H_ST_PER_SPIRIT, totals),
                integer(H_ST_TOTAL, totals),
                integer(H_ST_CONTRIBUTING, totals),
                integer(H_SR_TIER_INDEX, readout),
                enumName(ref(H_SR_CLASSIFICATION, readout)),
                str(H_SR_PRIMARY, readout),
                str(H_SR_SECONDARY, readout)));
    }

    /**
     * Plays a Townstead reaction for a public consequence, returning how many actually played.
     *
     * <p>A count of zero is {@code AVAILABLE(0)}, not a failure: Townstead deciding this villager has
     * nothing to say about the deed is a real answer, and treating it as an error would put a warning
     * in the log every time a quiet villager watched an arrest.
     */
    static TownsteadQueryResult<Integer> dispatchReaction(@Nullable ServerLevel level,
                                                          @Nullable LivingEntity villager,
                                                          @Nullable ResourceLocation taskId,
                                                          String phase) {
        if (!R.has(TownsteadCapability.DISPATCH_REACTION)) {
            return TownsteadQueryResult.missing(TownsteadCapability.DISPATCH_REACTION);
        }
        if (level == null || villager == null || taskId == null) {
            return TownsteadQueryResult.unavailable("no target");
        }
        try {
            int played = (int) H_REACTION_ON_TASK.invoke(level, villager, taskId, phase == null ? "" : phase);
            return TownsteadQueryResult.available(played);
        } catch (Throwable t) {
            return TownsteadQueryResult.failed("reaction dispatch threw " + t.getClass().getSimpleName());
        }
    }

    // --- mapping helpers -------------------------------------------------------------------------

    /**
     * The life-stage entry for {@code stageId} in the public root catalogue, or null when the root or
     * the stage is unknown.
     */
    @Nullable
    private static Object stageSnapshot(String rootId, String stageId) {
        ResourceLocation root = ResourceLocation.tryParse(rootId);
        if (root == null) {
            return null;
        }
        Object rootSnapshot = statik(H_ORIGIN, root);
        if (rootSnapshot == null) {
            return null;
        }
        for (Object stage : list(H_R_LIFE_STAGES, rootSnapshot)) {
            if (stageId.equals(str(H_LS_ID, stage))) {
                return stage;
            }
        }
        return null;
    }

    /**
     * Whether this stage can move, has needs and can be talked to, and what it renders as.
     *
     * <p>Unknown reads as all three capable and no rig override, which is the reading that treats an
     * unreadable villager as an ordinary one on every axis.
     */
    private record StageFlags(boolean known, boolean mobile, boolean needs, boolean talkable, String rig) {
        static StageFlags unknown() {
            return new StageFlags(false, true, true, true, "");
        }
    }

    private static StageFlags stageFlags(String rootId, String stageId) {
        if (!R.has(TownsteadCapability.STAGE_CAPABILITIES)) {
            return StageFlags.unknown();
        }
        ResourceLocation root = ResourceLocation.tryParse(rootId);
        if (root == null || stageId.isEmpty()) {
            return StageFlags.unknown();
        }
        Object cycle = statik(H_ROOT_LIFE_CYCLE, root);
        if (cycle == null) {
            return StageFlags.unknown();
        }
        Object stage = unwrap(ref(H_CYCLE_FIND_BY_ID, cycle, stageId));
        if (stage == null) {
            return StageFlags.unknown();
        }
        return new StageFlags(true,
                bool(H_STAGE_MOBILE, stage),
                bool(H_STAGE_NEEDS, stage),
                bool(H_STAGE_TALKABLE, stage),
                str(H_STAGE_RIG, stage));
    }

    /**
     * Needs, with the one judgement this layer owes every caller: is the reading real?
     *
     * <p>A stage whose {@code needs} flag is false has hunger and thirst pinned by Townstead, so its
     * zeroes mean "not applicable", not "starving". Untracked needs are therefore returned whole
     * rather than with a flag the caller might forget to check — see {@link TownsteadNeedsView}.
     */
    private static TownsteadNeedsView needs(@Nullable Object snapshot, String rootId, String stageId) {
        if (snapshot == null || !R.has(TownsteadCapability.READ_NEEDS)) {
            return TownsteadNeedsView.untracked();
        }
        if (!stageFlags(rootId, stageId).needs()) {
            return TownsteadNeedsView.untracked();
        }
        return new TownsteadNeedsView(
                true,
                integer(H_N_HUNGER, snapshot),
                flt(H_N_SATURATION, snapshot),
                flt(H_N_HUNGER_EXH, snapshot),
                integer(H_N_THIRST, snapshot),
                integer(H_N_QUENCHED, snapshot),
                flt(H_N_THIRST_EXH, snapshot),
                integer(H_N_FATIGUE, snapshot),
                bool(H_N_COLLAPSED, snapshot),
                bool(H_N_GATED, snapshot));
    }

    private static TownsteadScheduleView schedule(@Nullable Object snapshot) {
        if (snapshot == null || !R.has(TownsteadCapability.READ_SCHEDULE)) {
            return TownsteadScheduleView.unknown();
        }
        return new TownsteadScheduleView(
                true,
                str(H_S_MODE, snapshot),
                str(H_S_TEMPLATE_ID, snapshot),
                bool(H_S_CUSTOM_SHIFTS, snapshot),
                bool(H_S_NON_DEFAULT, snapshot),
                integer(H_S_TICK_HOUR, snapshot),
                integer(H_S_DISPLAY_HOUR, snapshot),
                integer(H_S_SHIFT_ORDINAL, snapshot),
                str(H_S_CURRENT_ACTIVITY, snapshot),
                str(H_S_PLANNED_ACTIVITY, snapshot),
                str(H_S_CURRENT_TEMPLATE, snapshot),
                intList(H_S_SHIFTS, snapshot),
                stringList(H_S_WEEKDAYS, snapshot));
    }

    @Nullable
    private static UUID uuid(String raw) {
        try {
            return raw.isEmpty() ? null : UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Unwraps a JDK {@link Optional} a Townstead lookup returned; anything else passes through. */
    @Nullable
    private static Object unwrap(@Nullable Object value) {
        return value instanceof Optional<?> optional ? optional.orElse(null) : value;
    }

    // --- invocation ------------------------------------------------------------------------------

    @Nullable
    private static Object statik(MethodHandle handle, Object a) {
        try {
            return handle.invoke(a);
        } catch (Throwable t) {
            return null;
        }
    }

    @Nullable
    private static Object statik(MethodHandle handle, Object a, Object b) {
        try {
            return handle.invoke(a, b);
        } catch (Throwable t) {
            return null;
        }
    }

    @Nullable
    private static Object ref(MethodHandle handle, @Nullable Object receiver) {
        if (receiver == null) {
            return null;
        }
        try {
            return handle.invoke(receiver);
        } catch (Throwable t) {
            return null;
        }
    }

    @Nullable
    private static Object ref(MethodHandle handle, @Nullable Object receiver, Object a) {
        if (receiver == null) {
            return null;
        }
        try {
            return handle.invoke(receiver, a);
        } catch (Throwable t) {
            return null;
        }
    }

    @Nullable
    private static Object ref(MethodHandle handle, @Nullable Object receiver, Object a, Object b) {
        if (receiver == null) {
            return null;
        }
        try {
            return handle.invoke(receiver, a, b);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String str(MethodHandle handle, @Nullable Object receiver) {
        Object value = ref(handle, receiver);
        return value instanceof String s ? s : "";
    }

    /** Lowercased {@link Enum#name()}, so no Townstead enum constant ever reaches a view record. */
    private static String enumName(@Nullable Object value) {
        return value instanceof Enum<?> e ? e.name().toLowerCase(Locale.ROOT) : "";
    }

    private static int integer(MethodHandle handle, @Nullable Object receiver) {
        if (receiver == null) {
            return 0;
        }
        try {
            return (int) handle.invoke(receiver);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static long lng(MethodHandle handle, @Nullable Object receiver) {
        if (receiver == null) {
            return 0L;
        }
        try {
            return (long) handle.invoke(receiver);
        } catch (Throwable t) {
            return 0L;
        }
    }

    private static float flt(MethodHandle handle, @Nullable Object receiver) {
        if (receiver == null) {
            return 0f;
        }
        try {
            return (float) handle.invoke(receiver);
        } catch (Throwable t) {
            return 0f;
        }
    }

    private static boolean bool(MethodHandle handle, @Nullable Object receiver) {
        if (receiver == null) {
            return false;
        }
        try {
            return (boolean) handle.invoke(receiver);
        } catch (Throwable t) {
            return false;
        }
    }

    private static List<Object> list(MethodHandle handle, @Nullable Object receiver) {
        Object value = ref(handle, receiver);
        if (!(value instanceof List<?> raw)) {
            return List.of();
        }
        List<Object> out = new ArrayList<>(raw.size());
        for (Object element : raw) {
            if (element != null) {
                out.add(element);
            }
        }
        return out;
    }

    private static List<String> stringList(MethodHandle handle, @Nullable Object receiver) {
        List<String> out = new ArrayList<>();
        for (Object element : list(handle, receiver)) {
            if (element instanceof String s) {
                out.add(s);
            }
        }
        return List.copyOf(out);
    }

    private static List<Integer> intList(MethodHandle handle, @Nullable Object receiver) {
        List<Integer> out = new ArrayList<>();
        for (Object element : list(handle, receiver)) {
            if (element instanceof Integer i) {
                out.add(i);
            }
        }
        return List.copyOf(out);
    }

    private static Map<String, Integer> intMap(MethodHandle handle, @Nullable Object receiver) {
        Object value = ref(handle, receiver);
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() instanceof String key && entry.getValue() instanceof Number number) {
                out.put(key, number.intValue());
            }
        }
        return Map.copyOf(out);
    }

    private TownsteadHandles() {
    }
}
