package dev.otectus.mcacrime.action;

import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Set;

/**
 * Everything the action screen needs to draw one row, and nothing it needs to decide anything
 * (spec §8.4). A descriptor is static per action; the per-target parts — whether the row is
 * available, why it is blocked, and how the target looks right now — come from
 * {@link ActionAvailability} at menu-build time and are re-derived on the server when the row is
 * clicked.
 *
 * <p>Descriptors are declared in code rather than data on purpose. A datapack may retune a crime and
 * may add dialogue, but it may not invent an action: every action needs a registered handler, and
 * {@link ActionHandlerRegistry} is the allowlist that enforces it.
 */
public record ActionDescriptor(ResourceLocation id,
                               ActionCategory category,
                               ActionLegality legality,
                               ActionDuration duration,
                               ActionTargetKind targetKind,
                               Set<ActionRequirement> requirements,
                               boolean hostile) {

    public ActionDescriptor {
        requirements = Set.copyOf(requirements);
    }

    /** An action aimed at another entity, for the villager interaction menu. */
    public static ActionDescriptor of(ResourceLocation id, ActionCategory category, ActionLegality legality,
                                      ActionDuration duration, boolean hostile,
                                      ActionRequirement... requirements) {
        return new ActionDescriptor(id, category, legality, duration, ActionTargetKind.ENTITY,
                Set.of(requirements), hostile);
    }

    /** An action aimed at the actor themself, for the captive panel and the player card. */
    public static ActionDescriptor self(ResourceLocation id, ActionCategory category, ActionLegality legality,
                                        ActionDuration duration, boolean hostile,
                                        ActionRequirement... requirements) {
        return new ActionDescriptor(id, category, legality, duration, ActionTargetKind.SELF,
                Set.of(requirements), hostile);
    }

    /** {@code gui.mcacrime.action.<path>} — the row's name. */
    public String labelKey() {
        return "gui.mcacrime.action." + id.getPath();
    }

    /** {@code gui.mcacrime.action.<path>.desc} — the one-line explanation under the name. */
    public String descriptionKey() {
        return "gui.mcacrime.action." + id.getPath() + ".desc";
    }

    /**
     * Requirement marker keys in a stable order, so the same action draws its markers identically
     * every time it is opened rather than in {@link Set} iteration order.
     */
    public List<String> requirementKeys() {
        return requirements.stream()
                .sorted()
                .map(ActionRequirement::labelKey)
                .toList();
    }
}
