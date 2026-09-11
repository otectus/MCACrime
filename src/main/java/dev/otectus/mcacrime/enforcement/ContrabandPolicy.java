package dev.otectus.mcacrime.enforcement;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.item.contraband.ContrabandInventoryScanner.Options;
import dev.otectus.mcacrime.item.contraband.ContrabandRules;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraftforge.event.TagsUpdatedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Locale;

/**
 * The active contraband list, and the two moments it has to be rebuilt (0.7.0).
 *
 * <p>Compiling the list is cheap but not free, and it is read once per guard per search pass, so the
 * compiled rules are held here and dropped when the config reloads. The second rebuild is the one that
 * matters: item tags do not exist while a config file is read, so a {@code #namespace:path} entry
 * cannot be checked at load time at all. {@link TagsUpdatedEvent} is the first moment they do exist,
 * and a tag that is still unknown then is dropped from the active list with one line in the log naming
 * it — the alternative is an entry that matches nothing, forever, in silence.
 *
 * <p>Nothing here throws. A broken list makes contraband inert, never a failed load.
 */
@Mod.EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class ContrabandPolicy {

    /** When a search may happen. Parsed from a string so an unknown value degrades instead of failing. */
    public enum DiscoveryMode {
        GUARD_PATROL,
        ARREST_ONLY,
        BOTH;

        public boolean allowsPatrol() {
            return this != ARREST_ONLY;
        }

        public boolean allowsArrest() {
            return this != GUARD_PATROL;
        }
    }

    private static volatile ContrabandRules active;
    /** Whether {@link #active} has been through a tag pass; before that, tag entries are provisional. */
    private static volatile boolean tagsBound;

    private ContrabandPolicy() {
    }

    /** The active rule set, compiled on first use and after every reload. */
    public static ContrabandRules rules() {
        ContrabandRules current = active;
        if (current == null) {
            current = compile();
            active = current;
        }
        return current;
    }

    /** Drops the compiled list so the next read rebuilds it. Called from the config reload. */
    public static void invalidate() {
        active = null;
        tagsBound = false;
    }

    /** Whether contraband is on and there is something on the list to find. */
    public static boolean enabled() {
        return McaCrimeConfig.COMMON.enableContraband.get() && !rules().isEmpty();
    }

    public static DiscoveryMode mode() {
        return parseMode(McaCrimeConfig.COMMON.contrabandDiscoveryMode.get());
    }

    /** {@code BOTH} for anything unrecognised: an operator typo must not disable discovery silently. */
    public static DiscoveryMode parseMode(String raw) {
        if (raw == null) {
            return DiscoveryMode.BOTH;
        }
        try {
            return DiscoveryMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return DiscoveryMode.BOTH;
        }
    }

    /** Which parts of an inventory a search may look at, from config. */
    public static Options options() {
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        return new Options(c.contrabandIncludeEquipped.get(), c.contrabandIncludeOffhand.get(),
                c.contrabandSearchNestedContainers.get());
    }

    @SubscribeEvent
    public static void onTagsUpdated(TagsUpdatedEvent event) {
        try {
            ContrabandRules compiled = compile();
            ContrabandRules filtered = compiled.withTagsFiltered(ContrabandPolicy::isKnownTag);
            for (String problem : filtered.problems()) {
                McaCrime.LOGGER.warn("MCA: Crime contraband list: {} — the entry is being ignored.", problem);
            }
            active = filtered;
            tagsBound = true;
        } catch (Throwable t) {
            // A tag manager that cannot be asked leaves the provisional list in place rather than
            // emptying it: a list that matches too much is visible, and one that matches nothing is not.
            McaCrime.LOGGER.debug("MCA: Crime contraband tag pass skipped", t);
        }
    }

    /** Whether the tag pass has run, so callers can tell a provisional list from a checked one. */
    public static boolean tagsBound() {
        return tagsBound;
    }

    private static boolean isKnownTag(ResourceLocation tag) {
        try {
            return ForgeRegistries.ITEMS.tags() != null
                    && ForgeRegistries.ITEMS.tags().isKnownTagName(TagKey.create(Registries.ITEM, tag));
        } catch (Throwable t) {
            return true; // unanswerable is not the same as unknown; keep the entry
        }
    }

    private static ContrabandRules compile() {
        try {
            return ContrabandRules.compile(McaCrimeConfig.COMMON.illegalItems.get());
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA: Crime contraband list unreadable; treating it as empty", t);
            return ContrabandRules.empty();
        }
    }
}
