package dev.otectus.mcacrime.api;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.frisk.InventoryProvider;
import dev.otectus.mcacrime.frisk.InventoryProviders;
import dev.otectus.mcacrime.restraint.RestraintDefinition;
import dev.otectus.mcacrime.restraint.RestraintDefinitions;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.Optional;

/**
 * How another mod adds a restraint, a rig or a place to search (0.7.5 M6.4).
 *
 * <p>An explicit front door, because the alternative is what companion mods do when there is none:
 * reflect into {@code RestraintDefinitions}, mutate the map, and break on the release that changes
 * its shape. Four rules make this safe to keep working:
 *
 * <ol>
 *   <li><b>Unique, foreign ids.</b> A registration must carry its own namespace. Nothing may claim an
 *       id in {@code mcacrime}, and nothing may replace a definition that already exists — including
 *       another mod's. A silently replaced definition is a prisoner whose cuffs change behaviour when
 *       an unrelated mod updates.</li>
 *   <li><b>A closed window.</b> Registrations are accepted during mod setup and refused afterwards.
 *       A definition that arrived after a world loaded would be absent from every row already written
 *       and present in every row written next, which is the same save file meaning two things.</li>
 *   <li><b>Bounded.</b> A cap on how many third-party definitions exist at all, because every one of
 *       them is a row in a client snapshot and a branch in a restriction composition.</li>
 *   <li><b>No registry sniffing.</b> Nothing here scans another mod's registries or guesses at its
 *       items. A mod that wants its rope to be a restraint says so.</li>
 * </ol>
 *
 * <p>Every method reports what it did rather than throwing: a companion that fails to register
 * should log one line and keep working, not take the game down during setup.
 */
public final class RestraintRegistrationApi {

    /** How many third-party definitions one installation may add. */
    public static final int MAX_THIRD_PARTY_DEFINITIONS = 64;

    /** What a registration attempt did. */
    public enum Result {
        /** Registered. */
        ACCEPTED,
        /** The window has closed: this had to happen during mod setup. */
        TOO_LATE,
        /** The id is missing, malformed, or in this mod's own namespace. */
        BAD_ID,
        /** Something is already registered under that id. */
        DUPLICATE,
        /** The third-party cap is full. */
        FULL,
        /** The definition restricts nothing and cannot be escaped: it is not a restraint. */
        INVALID;

        public boolean accepted() {
            return this == ACCEPTED;
        }
    }

    private RestraintRegistrationApi() {
    }

    /** Whether registrations are still being accepted. True only during mod setup. */
    public static boolean open() {
        return RestraintDefinitions.registrationOpen();
    }

    /**
     * Adds a restraint definition.
     *
     * <p>The definition is the caller's own value: its restrictions, durability, key family, rig
     * predicate and statistics are theirs and are not merged with anything of ours.
     */
    public static Result registerDefinition(@Nullable RestraintDefinition definition) {
        if (definition == null || definition.id() == null) {
            return Result.BAD_ID;
        }
        ResourceLocation id = definition.id();
        if (McaCrime.MOD_ID.equals(id.getNamespace())) {
            return Result.BAD_ID;
        }
        if (definition.restrictions().unrestrictedPolicy() && definition.escape().durability() <= 0) {
            // It restricts nothing and cannot be struggled out of, so it is not a restraint: it would
            // occupy a slot, block the real restraint that belongs there, and do nothing at all.
            return Result.INVALID;
        }
        return RestraintDefinitions.registerThirdParty(definition);
    }

    /**
     * Adds a place a search looks.
     *
     * <p>Curios and Cosmetic Armor are reached this way by MCA: Crime's own optional adapters, which
     * is the point: the mechanism a companion mod uses is the mechanism this mod uses, so it cannot
     * quietly stop working.
     */
    public static Result registerInventoryProvider(@Nullable InventoryProvider provider) {
        if (provider == null) {
            return Result.BAD_ID;
        }
        String id = provider.id();
        if (id == null || id.isBlank() || !id.equals(id.toLowerCase(Locale.ROOT))) {
            return Result.BAD_ID;
        }
        return InventoryProviders.registerThirdParty(provider);
    }

    /** One definition by id, whoever registered it. */
    public static Optional<RestraintDefinition> definition(@Nullable ResourceLocation id) {
        return RestraintDefinitions.get(id);
    }

    /** How many third-party definitions are registered right now. */
    public static int thirdPartyDefinitionCount() {
        return RestraintDefinitions.thirdPartyCount();
    }
}
