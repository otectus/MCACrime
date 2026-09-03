package dev.otectus.mcacrime.economy.account;

import net.minecraft.nbt.CompoundTag;

/** A finite, lazily-refilled virtual purse owned by one villager. */
public final class VillagerPurse {
    private int balance;
    private int capacity;
    private int dailyIncome;
    private long lastRefillDay;
    private long revision;
    private boolean nonZeroSeedApplied;

    public VillagerPurse(int balance, int capacity, int dailyIncome, long day) {
        this.balance = clamp(balance, 0, Math.max(0, capacity));
        this.capacity = Math.max(0, capacity);
        this.dailyIncome = Math.max(0, dailyIncome);
        this.lastRefillDay = Math.max(0L, day);
        this.nonZeroSeedApplied = true;
    }

    public int balance() { return balance; }
    public int capacity() { return capacity; }
    public int dailyIncome() { return dailyIncome; }
    public long lastRefillDay() { return lastRefillDay; }
    public long revision() { return revision; }

    /** Repairs 0.3.0 profiles whose deterministic initial roll allowed an empty first purse. */
    public boolean ensureNonZeroInitialSeed(int minimum) {
        if (!nonZeroSeedApplied) {
            balance = Math.min(capacity, Math.max(balance, Math.max(0, minimum)));
            nonZeroSeedApplied = true;
            revision++;
            return true;
        }
        return false;
    }

    /** At most one income increment is applied, no matter how many unloaded days elapsed. */
    public void refill(long day) {
        if (day > lastRefillDay) {
            balance = Math.min(capacity, balance + dailyIncome);
            lastRefillDay = day;
            revision++;
        }
    }

    public int withdraw(int requested) {
        int debit = Math.min(balance, Math.max(0, requested));
        if (debit > 0) {
            balance -= debit;
            revision++;
        }
        return debit;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("balance", balance);
        tag.putInt("capacity", capacity);
        tag.putInt("dailyIncome", dailyIncome);
        tag.putLong("lastRefillDay", lastRefillDay);
        tag.putLong("revision", revision);
        tag.putBoolean("nonZeroSeedApplied", nonZeroSeedApplied);
        return tag;
    }

    public static VillagerPurse load(CompoundTag tag) {
        VillagerPurse purse = new VillagerPurse(tag.getInt("balance"), tag.getInt("capacity"),
                tag.getInt("dailyIncome"), tag.getLong("lastRefillDay"));
        purse.revision = Math.max(0L, tag.getLong("revision"));
        purse.nonZeroSeedApplied = tag.contains("nonZeroSeedApplied") && tag.getBoolean("nonZeroSeedApplied");
        return purse;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
