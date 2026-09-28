package dev.otectus.mcacrime.restraint;

import dev.otectus.mcacrime.api.event.DetentionOutcomeEvent;
import dev.otectus.mcacrime.api.event.PhysicalEscapeEvent;
import dev.otectus.mcacrime.api.event.RestraintAppliedEvent;
import dev.otectus.mcacrime.api.event.RestraintRemovedEvent;
import dev.otectus.mcacrime.api.event.SubjectSeizedEvent;
import dev.otectus.mcacrime.api.model.RestraintSlotView;
import dev.otectus.mcacrime.incident.IncidentNotifications;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Where the physical engine tells other mods what happened (0.7.5 M6.4).
 *
 * <p>One class rather than a {@code post} at each commit site, for two reasons that are really the
 * same reason. First, every event here is <b>post-commit</b>: it is fired after the state is written
 * and it cannot be cancelled, so a listener can never leave an item spent and a slot empty. Second,
 * routing them through {@link IncidentNotifications} means a listener that throws is logged and the
 * operation it was watching still completes — a companion mod's bug must not roll back an arrest.
 *
 * <p>Nothing here decides anything. If a call to this class were deleted, the mod would behave
 * identically and only other mods would stop hearing about it.
 */
public final class PhysicalApiEvents {

    private PhysicalApiEvents() {
    }

    /** A restraint went on. */
    public static void applied(@Nullable LivingEntity subject, @Nullable Entity applier,
                               @Nullable ResourceLocation definitionId, @Nullable RestraintSlot slot,
                               @Nullable AppliedRestraint worn) {
        if (subject == null || definitionId == null || slot == null || worn == null) {
            return;
        }
        IncidentNotifications.post(new RestraintAppliedEvent(subject.getUUID(),
                subject instanceof Player, applier == null ? null : applier.getUUID(), definitionId,
                slot.id(), worn.context().name().toLowerCase(Locale.ROOT), view(slot, worn)));
    }

    /** A restraint came off. */
    public static void removed(@Nullable LivingEntity subject, @Nullable UUID actor,
                               @Nullable ResourceLocation definitionId, @Nullable RestraintSlot slot,
                               RestraintRemovedEvent.Cause cause) {
        if (subject == null || definitionId == null || slot == null) {
            return;
        }
        IncidentNotifications.post(new RestraintRemovedEvent(subject.getUUID(),
                subject instanceof Player, actor, definitionId, slot.id(), cause));
    }

    /** Somebody got free of everything holding them physically. */
    public static void escaped(@Nullable LivingEntity subject, boolean fromLawfulCustody,
                               boolean filedJailbreak) {
        if (subject == null) {
            return;
        }
        IncidentNotifications.post(new PhysicalEscapeEvent(subject.getUUID(),
                subject instanceof Player, fromLawfulCustody, filedJailbreak));
    }

    /** Somebody was chained, anchored or taken on an escort. */
    public static void seized(@Nullable Entity subject, @Nullable UUID holder,
                              @Nullable dev.otectus.mcacrime.tether.TetherKind kind, boolean lawful) {
        if (subject == null || kind == null) {
            return;
        }
        IncidentNotifications.post(new SubjectSeizedEvent(subject.getUUID(), subject instanceof Player,
                holder, kind.name().toLowerCase(Locale.ROOT), lawful));
    }

    /** A device stopped holding somebody, however that came about. */
    public static void detentionEnded(@Nullable UUID subject, boolean subjectIsPlayer,
                                      @Nullable String kind, DetentionOutcomeEvent.Outcome outcome,
                                      @Nullable UUID actor) {
        if (subject == null) {
            return;
        }
        IncidentNotifications.post(new DetentionOutcomeEvent(subject, subjectIsPlayer,
                kind == null ? "" : kind, outcome, actor));
    }

    /** The public projection of one worn instance. */
    public static RestraintSlotView view(RestraintSlot slot, AppliedRestraint worn) {
        return new RestraintSlotView(slot.id(), worn.definitionId(), worn.durabilityFraction(),
                worn.applier() == null ? Optional.empty() : worn.applier().entityId(),
                Optional.ofNullable(worn.custodyId()),
                worn.provenance() != AppliedRestraint.Provenance.PLAYER_OWNED);
    }
}
