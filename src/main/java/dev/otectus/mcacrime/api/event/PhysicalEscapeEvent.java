package dev.otectus.mcacrime.api.event;

import net.minecraftforge.eventbus.api.Event;

import java.util.UUID;

/**
 * Fired once when a subject has physically got free of everything holding them (0.7.5 M6.4).
 *
 * <p>Physical, and only physical. Whether this was a <em>jailbreak</em> is a legal question that
 * {@code restraint/CustodyTransitionService} answers separately: escaping lawful custody files one,
 * escaping a kidnapper files nothing and costs the victim nothing. A companion reacting to this
 * event as though it were a crime would punish kidnapping victims, which is the exact bug the
 * physical/legal split exists to prevent.
 *
 * @see EntityReleasedFromCaptivityEvent for the legal counterpart
 */
public final class PhysicalEscapeEvent extends Event {

    private final UUID subject;
    private final boolean subjectIsPlayer;
    private final boolean fromLawfulCustody;
    private final boolean filedJailbreak;

    public PhysicalEscapeEvent(UUID subject, boolean subjectIsPlayer, boolean fromLawfulCustody,
                               boolean filedJailbreak) {
        this.subject = subject;
        this.subjectIsPlayer = subjectIsPlayer;
        this.fromLawfulCustody = fromLawfulCustody;
        this.filedJailbreak = filedJailbreak;
    }

    public UUID getSubject() {
        return subject;
    }

    public boolean isSubjectPlayer() {
        return subjectIsPlayer;
    }

    /** Whether the hold they got out of was the law's. */
    public boolean isFromLawfulCustody() {
        return fromLawfulCustody;
    }

    /** Whether MCA: Crime filed a jailbreak case for it. Never true for a kidnapping escape. */
    public boolean isFiledJailbreak() {
        return filedJailbreak;
    }
}
