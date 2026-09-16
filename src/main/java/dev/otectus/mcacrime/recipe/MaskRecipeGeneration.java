package dev.otectus.mcacrime.recipe;

import dev.otectus.mcacrime.McaCrime;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A counter that changes whenever the server's recipes might have (0.7.2 §7.5).
 *
 * <p>Open Mask Station menus carry the generation their catalogue was gathered at, and a selection is
 * only honoured against the generation it was made at. That makes "the pack author reloaded while
 * somebody was looking at a preview" a bounded event rather than an exploit: the stale selection is
 * dropped and the catalogue is rebuilt from whatever the server now holds.
 *
 * <p>Two triggers, because neither alone is enough. {@link AddReloadListenerEvent} fires for every
 * datapack reload including the first, which covers {@code /reload} and world load;
 * {@link #observe(Object)} notices a replaced {@code RecipeManager} instance, which covers a reload
 * that happened without this mod's listener being consulted. Both feed one counter, and a
 * <em>same-id replacement</em> is a change like any other: the counter does not compare contents, so
 * a recipe file rewritten under its old id still invalidates every open selection.
 *
 * <p>Deliberately conservative in one direction only. Bumping when nothing changed costs one catalogue
 * rebuild on the next interaction; failing to bump would leave a preview the server no longer backs.
 */
@Mod.EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class MaskRecipeGeneration {

    /** Starts at 1, so {@link MaskStationCatalog#EMPTY}'s generation 0 never matches a live one. */
    private static final AtomicInteger GENERATION = new AtomicInteger(1);

    private static volatile Object lastRecipeManager;

    private MaskRecipeGeneration() {
    }

    public static int current() {
        return GENERATION.get();
    }

    /** Bumps the counter. Package-visible reasons only; nothing client-side may call this. */
    public static int bump() {
        lastRecipeManager = null;
        return GENERATION.incrementAndGet();
    }

    /**
     * Records which {@code RecipeManager} the current generation describes, bumping if it changed.
     *
     * @return the generation callers should tag a catalogue gathered from {@code manager} with
     */
    public static int observe(Object manager) {
        if (manager != null && manager != lastRecipeManager) {
            if (lastRecipeManager != null) {
                GENERATION.incrementAndGet();
            }
            lastRecipeManager = manager;
        }
        return GENERATION.get();
    }

    /** Forgets the observed manager, so the next server starts clean. Tests and shutdown only. */
    public static void reset() {
        lastRecipeManager = null;
    }

    @SubscribeEvent
    public static void onReload(AddReloadListenerEvent event) {
        bump();
    }
}
