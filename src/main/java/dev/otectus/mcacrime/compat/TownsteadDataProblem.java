package dev.otectus.mcacrime.compat;

import java.util.ArrayList;
import java.util.List;

/**
 * What went wrong in one Townstead datapack file, and what was done about it.
 *
 * <h2>Why these three loaders refuse rather than skip</h2>
 *
 * <p>MCA: Crime's other datapack loaders — crime types, dialogue, fence prices — skip a bad row and
 * carry on, and that is right for them: a fence with one mispriced item is a fence with one mispriced
 * item. These three are different in kind. They are <em>mappings</em>, and a mapping with a hole in it
 * does not degrade, it lies: a building-role file that silently dropped the jail line leaves a
 * settlement whose jail is not a jail, with an operator looking at a facility list that agrees with
 * them that it should be. A reaction binding that dropped one event leaves a village that reacts to
 * arrests and not to jailbreaks, which reads as a design decision rather than as a typo.
 *
 * <p>So an unknown id here is a load error with a name in it, the new set is not published, and the
 * last good one stays in force (reference: "unknown ids are a load error with a clear message, never
 * silently ignored"). {@code strictJsonValidation} turns the same condition into a hard failure for a
 * pack author who wants one.
 */
public record TownsteadDataProblem(String file, String message) {

    public TownsteadDataProblem {
        file = file == null ? "?" : file;
        message = message == null ? "" : message;
    }

    /** The line an operator reads in the log and in {@code /crime validate}. */
    public String describe() {
        return file + ": " + message;
    }

    /** Every problem's line, in order. */
    public static List<String> describeAll(List<TownsteadDataProblem> problems) {
        List<String> lines = new ArrayList<>(problems.size());
        problems.forEach(problem -> lines.add(problem.describe()));
        return List.copyOf(lines);
    }
}
