package dev.otectus.mcacrime.enforcement;

import java.util.List;

/**
 * What one village's guard-recruitment pass saw, and why it did or did not convert anybody.
 *
 * <h2>Why the roster and the available count are two numbers</h2>
 *
 * <p>The old report had one: how many villagers the pass could convert. That is fine until something
 * declines to convert them, and then it reads as a village with nobody in it — which is both alarming
 * and wrong. Once Townstead is in the world the ordinary reason a village stays under its guard target
 * is that its villagers are all employed, and an operator who cannot see that will go looking for a
 * bug in the guard pass.
 *
 * <p>So {@link #roster()} is every villager the MCA-side rules would accept, and {@link #available()}
 * is how many of those survived the settlement-side rules. The difference is accounted for by
 * {@link #protectedWorkers()} and {@link #unknownRoles()}, and {@link #halted()} says whether the pass
 * refused to convert anybody at all. A shortfall with a full roster and every one of them protected is
 * a village working as designed; the same shortfall with an empty roster is a village with nobody
 * eligible; the same shortfall with {@code halted} is MCA: Crime declining to guess.
 *
 * @param village          the dimension-and-village key, as it appears in operator output
 * @param population       MCA's own population count for the village, loaded or not
 * @param loadedResidents  how many of them are loaded right now
 * @param guards           law seen among the loaded residents, by the union of both guard tests
 * @param target           how many guards the configured share asks for
 * @param shortfall        how many conversions this pass would perform, before the roster is consulted
 * @param roster           villagers the MCA-side eligibility rules accept
 * @param available        those of them a settlement companion also allows to be drafted
 * @param protectedWorkers roster members Townstead says are working or employed
 * @param unknownRoles     roster members Townstead could not describe at all
 * @param halted           whether automatic conversion was stopped for this village
 * @param reasons          one line per distinct cause, for {@code /crime debug guards}
 */
public record RecruitmentReport(String village, int population, int loadedResidents, int guards,
                                int target, int shortfall, int roster, int available,
                                int protectedWorkers, int unknownRoles, boolean halted,
                                List<String> reasons) {

    public RecruitmentReport {
        village = village == null ? "" : village;
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    /** How many conversions this pass will actually attempt. */
    public int conversions() {
        return halted ? 0 : Math.min(Math.max(0, shortfall), Math.max(0, available));
    }

    /** One line for {@code /crime debug guards}, with the explanation only when there is one. */
    public String describe() {
        StringBuilder out = new StringBuilder(String.format(
                "%s: population %d, loaded %d, guards %d, target %d, needed %d, roster %d, available %d",
                village, population, loadedResidents, guards, target, shortfall, roster, available));
        if (protectedWorkers > 0) {
            out.append(", ").append(protectedWorkers).append(" protected by Townstead");
        }
        if (unknownRoles > 0) {
            out.append(", ").append(unknownRoles).append(" with unreadable roles");
        }
        if (halted) {
            out.append(", automatic conversion halted");
        }
        for (String reason : reasons) {
            out.append("\n    ").append(reason);
        }
        return out.toString();
    }
}
