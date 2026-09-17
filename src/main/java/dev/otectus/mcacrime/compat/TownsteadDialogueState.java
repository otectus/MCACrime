package dev.otectus.mcacrime.compat;

import java.lang.ref.WeakReference;

/**
 * The one bit of state that says "MCA: Crime took this screen away; do not put it back".
 *
 * <h2>Why an explicit reason is needed at all</h2>
 *
 * <p>Townstead's dialogue screen does not treat being removed as being finished. Its {@code removed()}
 * queues the screen to reopen when a line is still typing out or a late response is still expected, and
 * only the branch it reaches after an explicit user close restores the HUD, snaps the camera back and
 * tells its own server side that the conversation ended. That is right for a player pressing escape and
 * wrong for every way MCA: Crime replaces the screen: a guard challenge opening over a conversation, or
 * the player asking for the law menu from inside it. Without a reason, Townstead would queue the
 * conversation back on top of a timed legal demand, and the camera and HUD would be restored twice or
 * not at all.
 *
 * <p>So the screen is marked before it is replaced, and the marker is <em>consumed</em> by the mixin
 * that sees it: exactly one cleanup, no queued reopen, and a second removal of the same screen is an
 * ordinary removal again.
 *
 * <h2>Why it lives here, typed as {@code Object}</h2>
 *
 * <p>Two callers with nothing else in common need it: {@code CrimeClientHandlers}, which is ordinary
 * client code and must not name anything under {@code compat/townstead/}, and the Townstead client
 * mixin, which may name neither client screens nor Townstead types in its own signatures. A weak
 * reference to an {@code Object} is the only shape both can hold. Weak because the whole point is that
 * the screen is being thrown away, and a marker that outlived its screen would be a leak with a
 * user-visible tail: the next dialogue would inherit somebody else's close reason.
 */
public final class TownsteadDialogueState {

    /** The screen MCA: Crime is replacing, or a reference to nothing. */
    private static volatile WeakReference<Object> externalClose = new WeakReference<>(null);

    private TownsteadDialogueState() {
    }

    /**
     * Records that MCA: Crime is about to replace {@code screen}.
     *
     * <p>Called unconditionally, whatever screen is up and whether or not Townstead is installed: the
     * cheap, always-true version of the question. Nothing reads the marker except a mixin that only
     * exists when Townstead is there, and it only matches when the screen it is merged into is the very
     * object recorded here, so marking a vanilla inventory screen costs one field write and is never
     * looked at again.
     */
    public static void markExternalClose(Object screen) {
        externalClose = new WeakReference<>(screen);
    }

    /**
     * Whether {@code screen} is the one MCA: Crime is replacing, clearing the mark if so.
     *
     * <p>Consuming rather than peeking, because the cleanup it authorises — camera, HUD, the server-side
     * dialogue token — must happen exactly once. Identity, not equality: two dialogue screens for the
     * same villager are two conversations, and only the one that was actually taken away was closed by
     * this mod.
     */
    public static boolean consumeExternalClose(Object screen) {
        if (screen == null) {
            return false;
        }
        WeakReference<Object> current = externalClose;
        if (current.get() != screen) {
            return false;
        }
        externalClose = new WeakReference<>(null);
        return true;
    }

    /** Drops the mark. Called on disconnect, beside the other client caches, and by tests. */
    public static void clear() {
        externalClose = new WeakReference<>(null);
    }

    /** Whether anything is marked at all. Diagnostics and tests only. */
    public static boolean marked() {
        return externalClose.get() != null;
    }
}
