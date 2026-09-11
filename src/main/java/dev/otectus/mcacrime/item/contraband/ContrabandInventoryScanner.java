package dev.otectus.mcacrime.item.contraband;

import java.util.ArrayList;
import java.util.List;

/**
 * Filters a set of {@link ContrabandProbe}s down to the listed ones (0.7.0).
 *
 * <p>Pure: the walk over a real inventory lives in {@code ContrabandScanAdapter}, which hands over
 * probes. Keeping the decision here is what makes the slot toggles and the short-circuit testable
 * without a Minecraft bootstrap.
 */
public final class ContrabandInventoryScanner {

    /** Which parts of a player's inventory a search is allowed to look at. */
    public record Options(boolean includeEquipped, boolean includeOffhand, boolean nested) {
    }

    /** One level of nesting, never more, wherever the number is applied. */
    public static final int MAX_NESTED_DEPTH = 1;

    private static final Options ALL = new Options(true, true, true);

    private ContrabandInventoryScanner() {
    }

    /**
     * Every probe the rules list. With {@code shortCircuit} the first hit is enough — a guard who has
     * found one illegal item has found the crime, and per-stack Heat is the only reason to keep looking.
     */
    public static List<ContrabandProbe> listed(List<ContrabandProbe> probes, ContrabandRules rules,
                                               boolean shortCircuit) {
        return listed(probes, rules, ALL, shortCircuit);
    }

    /**
     * The same filter with the configured slot toggles applied, so a probe from a part of the inventory
     * the operator excluded is dropped here as well as never being collected. The depth cap is enforced
     * in both: a probe deeper than {@link #MAX_NESTED_DEPTH} is never listed, whoever produced it.
     */
    public static List<ContrabandProbe> listed(List<ContrabandProbe> probes, ContrabandRules rules,
                                               Options options, boolean shortCircuit) {
        if (probes == null || probes.isEmpty() || rules == null || rules.isEmpty()) {
            return List.of();
        }
        Options opts = options == null ? ALL : options;
        List<ContrabandProbe> found = new ArrayList<>();
        for (ContrabandProbe probe : probes) {
            if (probe == null || probe.depth() > MAX_NESTED_DEPTH || !allowed(probe, opts)
                    || !rules.matches(probe)) {
                continue;
            }
            found.add(probe);
            if (shortCircuit) {
                break;
            }
        }
        return List.copyOf(found);
    }

    private static boolean allowed(ContrabandProbe probe, Options options) {
        return switch (probe.slot()) {
            case ARMOR -> options.includeEquipped();
            case OFFHAND -> options.includeOffhand();
            case NESTED -> options.nested();
            case MAIN -> true;
        };
    }
}
