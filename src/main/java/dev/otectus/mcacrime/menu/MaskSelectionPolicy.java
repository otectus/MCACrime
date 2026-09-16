package dev.otectus.mcacrime.menu;

import dev.otectus.mcacrime.recipe.MaskStationCatalog;
import net.minecraft.resources.ResourceLocation;

import org.jetbrains.annotations.Nullable;

/**
 * Whether the server will act on a client's choice of style (0.7.2 §8.1, §8.4).
 *
 * <p>Pure, and separate from the packet, because this is the whole security argument for letting a
 * client influence a server-built item at all: the client names a recipe id and the session it thinks
 * it is in, and the server independently decides whether that id is one of the outputs <em>it</em>
 * currently offers for the inputs <em>it</em> holds. A client can never supply the item, the count,
 * the data components or the price, so the worst a forged packet achieves is selecting a style the
 * sender could have selected anyway (invariant 6).
 */
public final class MaskSelectionPolicy {

    private MaskSelectionPolicy() {
    }

    public static MaskSelectionOutcome evaluate(int openContainerId, int requestedContainerId,
                                                MaskStationCatalog catalog,
                                                @Nullable ResourceLocation requested,
                                                int requestedGeneration) {
        if (openContainerId != requestedContainerId) {
            return MaskSelectionOutcome.WRONG_CONTAINER;
        }
        if (catalog == null || requestedGeneration != catalog.generation()) {
            return MaskSelectionOutcome.STALE_GENERATION;
        }
        if (requested == null || !catalog.contains(requested)) {
            return MaskSelectionOutcome.UNKNOWN_RECIPE;
        }
        return MaskSelectionOutcome.ACCEPTED;
    }
}
