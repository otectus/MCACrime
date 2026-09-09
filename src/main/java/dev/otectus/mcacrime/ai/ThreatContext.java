package dev.otectus.mcacrime.ai;

import dev.otectus.mcacrime.item.weapon.WeaponClass;

/** Server-resolved inputs; no live entity references or random decisions. */
public record ThreatContext(WeaponClass weapon, boolean aimed, boolean coercive, double distance,
                            double healthFraction, double bravery, double combat, double fear,
                            double anger, boolean recentViolence, int allies, boolean guardNearby,
                            boolean armed, boolean protectingFamily) {}
