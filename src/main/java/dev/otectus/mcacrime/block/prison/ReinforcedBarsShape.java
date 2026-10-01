package dev.otectus.mcacrime.block.prison;

/**
 * Which section of a bar column a piece of reinforced bars is (M5.4).
 *
 * <p>Pure, and separate from the block so the shape rule can be asserted without a registry: the
 * mapping is a decision about how a wall of bars reads, and it should be checkable without loading a
 * block registry.
 *
 * <p>Upstream computes the "is there something above me" term partly from the block <em>below</em>
 * ({@code blocks/ReinforcedBarsBlock.java:41-47} reads {@code pos.below()} in both branches of the
 * gapped-bars test), so a gapped section under a plain one picks the wrong section and the texture
 * seam shows.
 */
public final class ReinforcedBarsShape {

    /** A free-standing or topmost section: it gets a cap. */
    public static final int CAP = 0;
    /** A section with bars above and below: it gets the plain middle. */
    public static final int MIDDLE = 1;
    /** A section with bars below only: it gets the footing. */
    public static final int BOTTOM = 2;

    private ReinforcedBarsShape() {
    }

    /**
     * Whether an arm going {@code toward} joins a flat panel whose face points along
     * {@code panelNormal}: along the panel's plane, yes; into its face, no.
     */
    public static boolean joinsPanel(net.minecraft.core.Direction.Axis panelNormal,
                                     net.minecraft.core.Direction toward) {
        return toward != null && panelNormal != null && toward.getAxis().isHorizontal()
                && panelNormal.isHorizontal() && toward.getAxis() != panelNormal;
    }

    /** The column value for a section with these neighbours. */
    public static int column(boolean above, boolean below) {
        if (above && below) {
            return MIDDLE;
        }
        return below ? BOTTOM : CAP;
    }
}
