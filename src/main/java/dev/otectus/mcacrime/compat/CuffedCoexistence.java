package dev.otectus.mcacrime.compat;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import net.minecraftforge.fml.ModList;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.Optional;

/**
 * What happens when the mod this release absorbed is installed alongside it (0.7.5 §3.17).
 *
 * <p>Detection is by <b>mod id only</b>. No upstream type is named here or anywhere else in MCA:
 * Crime, no foreign data is read, nothing of theirs is cleared, and no mixin of theirs is disabled:
 * "never silently clear foreign restraint data or disable foreign mixins opportunistically" is the
 * specification's rule and this is the whole of the mechanism that honours it.
 *
 * <p>Two policies, and the difference between them is only whether MCA: Crime will <em>apply</em>
 * restraints:
 *
 * <ul>
 *   <li>{@code WARN} (default) logs one line at startup and proceeds. Two physical systems can both
 *       be installed; an operator who has chosen that is told once, plainly, that neither mod knows
 *       about the other's holds.</li>
 *   <li>{@code REFUSE} additionally refuses new MCA: Crime restraint applications. Removal, recovery,
 *       deserialisation and every administrative path stay enabled — a disabled mechanic must still
 *       permit safe removal of existing equipment (specification §16), and a policy that stranded
 *       everybody already in cuffs would be the worst of both mods.</li>
 * </ul>
 *
 * <p>The donor-world import tool is explicitly out of scope for 0.7.5.
 */
public final class CuffedCoexistence {

    /** The upstream mod id, as its own {@code mods.toml} declares it. */
    public static final String MOD_ID = "cuffed";

    /** What to do about it. */
    public enum Policy {
        /** Say so once and carry on. */
        WARN,
        /** Say so and stop applying new restraints. */
        REFUSE;

        public static Policy parse(@Nullable String raw) {
            if (raw == null) {
                return WARN;
            }
            String name = raw.trim().toUpperCase(Locale.ROOT);
            for (Policy policy : values()) {
                if (policy.name().equals(name)) {
                    return policy;
                }
            }
            return WARN;
        }
    }

    private static volatile boolean reported;

    private CuffedCoexistence() {
    }

    /** Whether the donor mod is installed. Mod id only; no class of theirs is ever resolved. */
    public static boolean installed() {
        ModList list = ModList.get();
        return list != null && list.isLoaded(MOD_ID);
    }

    /** The configured policy, defaulting to the documented {@code WARN}. */
    public static Policy policy() {
        try {
            return Policy.parse(McaCrimeConfig.COMMON.cuffedCoexistence.get());
        } catch (IllegalStateException | NullPointerException notLoaded) {
            return Policy.WARN;
        }
    }

    /**
     * Whether MCA: Crime may apply a new restraint right now.
     *
     * <p>The only behavioural consequence of coexistence. Everything else — removing one, recovering
     * a stuck subject, loading a saved row, an operator command — is unaffected by design.
     */
    public static boolean mayApplyRestraints() {
        return !installed() || policy() != Policy.REFUSE;
    }

    /** The pure rule, so both halves can be asserted with no mod list and no config. */
    public static boolean mayApply(boolean donorInstalled, Policy policy) {
        return !donorInstalled || policy != Policy.REFUSE;
    }

    /** The one startup line, if there is one to say. Said once per server, never per restraint. */
    public static Optional<String> startupNotice() {
        if (!installed()) {
            return Optional.empty();
        }
        Policy policy = policy();
        return Optional.of("MCA: Crime — the Cuffed mod is installed alongside this release, which "
                + "absorbs it. Neither mod knows about the other's restraints, chains or devices. "
                + "Policy: " + policy.name().toLowerCase(Locale.ROOT)
                + (policy == Policy.REFUSE
                        ? " — MCA: Crime will not apply new restraints; removal and recovery still work."
                        : " — both systems are active; consider removing one.")
                + " No Cuffed data is read, cleared or disabled by MCA: Crime.");
    }

    /** Logs the notice once. Called from common setup. */
    public static synchronized void logStartupNotice() {
        if (reported) {
            return;
        }
        reported = true;
        startupNotice().ifPresent(McaCrime.LOGGER::warn);
    }

    /** Test and shutdown hook. */
    public static synchronized void reset() {
        reported = false;
    }
}
