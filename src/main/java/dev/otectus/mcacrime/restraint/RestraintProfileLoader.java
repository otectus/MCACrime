package dev.otectus.mcacrime.restraint;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.network.CrimeNetwork;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Datapack reload listener for restraint tuning, {@code data/<ns>/mcacrime/restraint_profiles/*.json}
 * (plan §3.13).
 *
 * <p>The file name <em>is</em> the definition id: {@code mcacrime/restraint_profiles/handcuffs_arms.json}
 * overrides {@code mcacrime:handcuffs_arms}. A file naming an id that does not exist is a validation
 * error and is ignored — it cannot create a definition, because the slot semantics behind a definition
 * are an authorisation decision and nothing validates an id nobody declared (see
 * {@link RestraintDefinitions}).
 *
 * <p>A file is accepted or refused whole. Half of a profile is worse than none of it: a restraint
 * whose durability came from the pack and whose restrictions came from the code is a state neither
 * the author nor the player can reason about.
 *
 * <p>No profile files ship with the mod. The shipped behaviour is the code table, and an installed
 * pack is the only way this class does anything at all.
 */
@Mod.EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class RestraintProfileLoader extends SimpleJsonResourceReloadListener {

    private static final Gson GSON = new GsonBuilder().create();
    /** Under the mod's own folder, like {@code mcacrime/crimes} and {@code mcacrime/fence_prices}. */
    public static final String DIRECTORY = "mcacrime/restraint_profiles";

    public RestraintProfileLoader() {
        super(GSON, DIRECTORY);
    }

    @SubscribeEvent
    public static void onAddReloadListener(AddReloadListenerEvent event) {
        event.addListener(new RestraintProfileLoader());
    }

    /**
     * Tells every player what the pack says, on login and after {@code /reload}.
     *
     * <p>Always sent, including when nothing is overridden: a client that joins a second server has to
     * be told the first server's numbers no longer apply, and "no packet" cannot say that. The client
     * needs them because it composes its own {@link RestrictionPolicy} to predict blocked input.
     */
    @SubscribeEvent
    public static void onDatapackSync(OnDatapackSyncEvent event) {
        List<RestraintProfile> active = RestraintProfileOverrides.all();
        ServerPlayer player = event.getPlayer();
        if (player != null) {
            CrimeNetwork.sendRestraintProfiles(player, active);
            return;
        }
        for (ServerPlayer online : event.getPlayerList().getPlayers()) {
            CrimeNetwork.sendRestraintProfiles(online, active);
        }
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager,
                         ProfilerFiller profiler) {
        List<String> errors = new ArrayList<>();
        List<RestraintProfile> loaded = read(files, errors);
        boolean installed = install(loaded, errors);
        for (String error : errors) {
            McaCrime.LOGGER.error("[MCA: Crime] {}", error);
        }
        if (!installed) {
            return;
        }
        if (!loaded.isEmpty() || !errors.isEmpty()) {
            McaCrime.LOGGER.info("Loaded {} restraint profile(s) with {} error(s).",
                    loaded.size(), errors.size());
        }
    }

    /**
     * Installs {@code loaded} unless the set is larger than
     * {@link RestraintProfileOverrides#MAX_PROFILES}; returns whether it was installed.
     *
     * <p>The whole set is refused rather than trimmed, and the previously loaded overrides stay in
     * force. Refusing whole is the same rule a single file already follows: half of an oversized set
     * is an arbitrary sixty-four of the author's profiles, chosen by map order, and neither the author
     * nor the player could tell which. Keeping the previous overrides matters because the alternative
     * on a live {@code /reload} is silently dropping every number a working pack had already set.
     *
     * <p>The cap is also the sync bound — {@code network/RestraintProfileSyncS2CPacket} writes and
     * reads at most this many — so refusing here is what keeps the server's view and the client's
     * view the same set rather than the first sixty-four of it.
     */
    public static boolean install(@Nullable List<RestraintProfile> loaded, List<String> errors) {
        int count = loaded == null ? 0 : loaded.size();
        if (count > RestraintProfileOverrides.MAX_PROFILES) {
            errors.add("Restraint profile set refused whole: " + count + " profiles exceeds the"
                    + " maximum of " + RestraintProfileOverrides.MAX_PROFILES
                    + "; the previously loaded overrides are kept");
            return false;
        }
        RestraintProfileOverrides.replaceAll(loaded);
        return true;
    }

    /**
     * The pure half: files in, accepted profiles out, every refusal appended to {@code errors}.
     *
     * <p>Separated from {@link #apply} so the validation rules can be tested without a resource
     * manager, a config or a server.
     */
    public static List<RestraintProfile> read(Map<ResourceLocation, JsonElement> files,
                                              List<String> errors) {
        Map<ResourceLocation, RestraintProfile> accepted = new LinkedHashMap<>();
        if (files == null) {
            return List.of();
        }
        for (Map.Entry<ResourceLocation, JsonElement> entry : files.entrySet()) {
            ResourceLocation fileId = entry.getKey();
            if (RestraintDefinitions.base(fileId).isEmpty()) {
                errors.add("Restraint profile '" + fileId + "' names no definition; ignored"
                        + " (a datapack may tune the definitions, never add one)");
                continue;
            }
            RestraintProfile.Parsed parsed = RestraintProfile.parse(fileId, entry.getValue());
            if (!parsed.accepted()) {
                errors.add("Restraint profile '" + fileId + "' rejected: "
                        + String.join("; ", parsed.errors()));
                continue;
            }
            accepted.put(fileId, parsed.profile().orElseThrow());
        }
        return List.copyOf(accepted.values());
    }
}
