package dev.otectus.mcacrime.frisk;

/**
 * One searchable slot, named the way the server names it (M5.2).
 *
 * <p>The provider id and the provider's own index, never a raw inventory number. A client never
 * sees one of these: the transfer packet carries a position in the session's ordered view, and the
 * server looks the reference up. That is what makes "forge a slot index" meaningless — the worst a
 * forged index can name is another slot of the same subject in the same open session, which the
 * revision check then rejects.
 *
 * @param providerId which inventory provider owns this slot
 * @param kind       what part of the subject it is
 * @param index      the provider's own index for it
 */
public record FriskSlotRef(String providerId, FriskSlotKind kind, int index) {

    public FriskSlotRef {
        providerId = providerId == null ? "" : providerId;
        kind = kind == null ? FriskSlotKind.MAIN : kind;
        index = Math.max(0, index);
    }
}
