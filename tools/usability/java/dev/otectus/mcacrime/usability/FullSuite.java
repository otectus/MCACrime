package dev.otectus.mcacrime.usability;

/**
 * The 0.8.0 assertions. Phase 0 runs every workstream and leaves what a restart has to prove; phase 1
 * (the same world after a save and restart) runs only the checks that read what phase 0 left behind.
 */
final class FullSuite {

    private FullSuite() {
    }

    static void run(Fixtures fixtures, UsabilityRuntimeChecks out) {
        if (UsabilityRuntimeChecks.phase() == 0) {
            // The probe records the same cases it recorded against 0.7.9, for the before/after record.
            new TargetingBaselineProbe(fixtures, out).run();
            new TargetingChecks(fixtures, out).run();
            new KeyChecks(fixtures, out).run();
            new MaskChecks(fixtures, out).run();
        }
        if (UsabilityRuntimeChecks.phase() == 1) {
            new KeyChecks(fixtures, out).run();
            new MaskChecks(fixtures, out).run();
        }
        new GuillotineChecks(fixtures, out).run();
    }
}
