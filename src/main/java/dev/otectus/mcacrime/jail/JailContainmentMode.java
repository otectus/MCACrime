package dev.otectus.mcacrime.jail;

/**
 * How a jail region resists a prisoner mining out (spec §7.3).
 *
 * <ul>
 *   <li>{@link #CONTAINMENT} — anti-grief default: prisoners can't break jail-region blocks; a strayed
 *       prisoner is teleported back (soft-confine).</li>
 *   <li>{@link #PHYSICAL} — hardcore/roleplay: walls are breakable and a genuine breakout is legitimate,
 *       flagging a {@code jailbreak} crime (Heat + escaped Legal Target); the sentence continues.</li>
 *   <li>{@link #REINFORCED} — raised block resistance. In v0.1.0 this behaves like {@link #CONTAINMENT}
 *       (the resistance raise is deferred).</li>
 * </ul>
 *
 * Lives in the {@code jail} package (not nested in the config class) so the pure jail logic and its unit
 * tests don't pull in {@code ForgeConfigSpec} static initialization.
 */
public enum JailContainmentMode {
    CONTAINMENT,
    PHYSICAL,
    REINFORCED;

    public static JailContainmentMode parse(String name) {
        try {
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            return CONTAINMENT;
        }
    }
}
