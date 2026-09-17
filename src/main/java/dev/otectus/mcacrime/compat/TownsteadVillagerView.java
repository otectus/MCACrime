package dev.otectus.mcacrime.compat;

import java.util.UUID;

/**
 * Townstead's snapshot of one villager, reduced to what MCA: Crime has any business knowing.
 *
 * <p>Immutable, vanilla and primitive fields only — the whole point of the seam is that nothing which
 * crosses it carries a Townstead type. Genetics, heritage and carried variants are deliberately not
 * copied: crime has no use for them, and every field copied is a field that has to keep binding.
 *
 * <p>{@code professionId} is Townstead's view of the trade, which is not necessarily MCA: Crime's:
 * a villager MCA: Crime has made a thief still has whatever profession Townstead progresses. Read it
 * as information, never as authority over Crime's own job records.
 */
public record TownsteadVillagerView(
        UUID uuid,
        String name,
        String entityType,
        String rootId,
        String lifeStageId,
        long biologicalAgeDays,
        int apparentAgeYears,
        boolean immortal,
        boolean ageless,
        boolean senior,
        String personalityId,
        String professionId,
        int professionLevel,
        int professionXp,
        float fertility,
        TownsteadScheduleView schedule,
        TownsteadNeedsView needs) {

    public TownsteadVillagerView {
        name = name == null ? "" : name;
        entityType = entityType == null ? "" : entityType;
        rootId = rootId == null ? "" : rootId;
        lifeStageId = lifeStageId == null ? "" : lifeStageId;
        personalityId = personalityId == null ? "" : personalityId;
        professionId = professionId == null ? "" : professionId;
        schedule = schedule == null ? TownsteadScheduleView.unknown() : schedule;
        needs = needs == null ? TownsteadNeedsView.untracked() : needs;
    }

    public String describe() {
        return "villager: " + (name.isEmpty() ? uuid.toString() : name)
                + " root " + (rootId.isEmpty() ? "?" : rootId)
                + ", stage " + (lifeStageId.isEmpty() ? "?" : lifeStageId)
                + ", age " + apparentAgeYears + "y (" + biologicalAgeDays + " life days)"
                + (senior ? ", senior" : "")
                + (immortal ? ", immortal" : "")
                + (ageless ? ", ageless" : "")
                + ", profession " + (professionId.isEmpty() ? "?" : professionId)
                + " tier " + professionLevel + " xp " + professionXp;
    }
}
