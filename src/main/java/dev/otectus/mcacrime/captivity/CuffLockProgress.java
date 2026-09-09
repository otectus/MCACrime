package dev.otectus.mcacrime.captivity;

/** Server-side progress for the native itemless minigame: a miss resets the solved prefix. */
public final class CuffLockProgress {
    private int solved;
    private boolean completed;
    private long nextAttempt;
    private int lastSequence;

    public boolean accept(int pin, int length, int sequence, long now) {
        if (completed || pin < 0 || pin >= length || now < nextAttempt
                || sequence <= lastSequence) return false;
        lastSequence = sequence;
        nextAttempt = now + 2L;
        return true;
    }

    public boolean resolve(boolean correct, int length) {
        if (completed) return false;
        solved = correct ? solved + 1 : 0;
        completed = solved == length;
        return completed;
    }

    public int solved() { return solved; }
    public boolean completed() { return completed; }
    public int lastSequence() { return lastSequence; }

    public static boolean validCombination(byte[] pins) {
        if (pins == null || pins.length < 3 || pins.length > 8) return false;
        int seen = 0;
        for (byte pin : pins) {
            if (pin < 0 || pin >= pins.length || (seen & (1 << pin)) != 0) return false;
            seen |= 1 << pin;
        }
        return true;
    }
}
