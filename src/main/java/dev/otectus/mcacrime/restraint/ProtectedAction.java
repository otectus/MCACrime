package dev.otectus.mcacrime.restraint;

/**
 * The actions no restriction policy may ever cancel (§3.4, specification §7.1).
 *
 * <p>An explicit list rather than an exception buried in each handler, because the bug it prevents
 * is the one that makes a restraint permanent: cancel the interaction that <em>is</em> the escape
 * attempt before the escape handler sees it, and the subject is stuck until an operator intervenes.
 * The same goes for care given by somebody else, and for the screen that tells the subject why they
 * cannot move.
 */
public enum ProtectedAction {

    /** The struggle input itself. */
    STRUGGLE,
    /** A configured self-escape route: a key in hand, a cutting tool, a lockpick. */
    SELF_ESCAPE,
    /** The status/help screens, including the captive panel. */
    STATUS_SCREEN,
    /** Text chat. A head restraint gags voice, not typing (§3.4). */
    CHAT,
    /** Food and treatment given by somebody else. */
    EXTERNAL_CARE
}
