package dev.otectus.mcacrime.detect;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;

/** Retains final event values until the enclosing damage/death call has returned. */
public final class DamageFinality {
    public enum Outcome { NONE, HARM, KILL }
    private final BooleanSupplier damageCanceled;
    private final DoubleSupplier damageAmount;
    private final List<BooleanSupplier> deathCancellations = new ArrayList<>();

    public DamageFinality(BooleanSupplier damageCanceled, DoubleSupplier damageAmount) {
        this.damageCanceled = damageCanceled;
        this.damageAmount = damageAmount;
    }

    public void death(BooleanSupplier canceled) { deathCancellations.add(canceled); }

    public Outcome resolve(boolean deadOrDying) {
        // Player/ServerPlayer can post more than one death event for the same terminal hit.
        if (deadOrDying && !deathCancellations.isEmpty()
                && deathCancellations.stream().noneMatch(BooleanSupplier::getAsBoolean)) return Outcome.KILL;
        double amount = damageAmount.getAsDouble();
        return !damageCanceled.getAsBoolean() && Double.isFinite(amount) && amount > 0
                ? Outcome.HARM : Outcome.NONE;
    }
}
