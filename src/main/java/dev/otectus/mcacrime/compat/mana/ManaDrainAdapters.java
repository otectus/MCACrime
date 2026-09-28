package dev.otectus.mcacrime.compat.mana;

import net.neoforged.fml.ModList;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * The isolated Silence drain adapters (0.7.5 M6.2, specification §15.1).
 *
 * <p>One class, three spell mods, and not one of them is named as a Java type: every member is
 * resolved by string through reflection, because none of the three is on this mod's compile
 * classpath and because {@code OptionalClassloadTest} requires that nothing outside this package is
 * reachable without the mod that needs it. {@code compat/ManaCompat} reaches this class by name and
 * is the only caller.
 *
 * <p>Each adapter is a <b>probe first, drain second</b>: {@link #probe()} resolves the exact members
 * it will later call and records what it found, and {@link #drain} does nothing at all for a mod
 * whose probe failed. That is the difference between an integration and a claim — upstream's TacZ
 * adapter is an empty class whose presence alone sets an unrelated voice-chat flag
 * ({@code CuffedMod.java:176-179}), which is precisely the shape this avoids.
 *
 * <p>No adapter changes a user setting, writes a config or registers anything. The only mutation any
 * of them performs is reducing a pool value the owning mod already exposes a setter for, once per
 * drain call, clamped at zero.
 */
public final class ManaDrainAdapters {

    /** Iron's Spells 'n Spellbooks. */
    private static final String IRONS_ID = "irons_spellbooks";
    /** Ars Nouveau. */
    private static final String ARS_ID = "ars_nouveau";
    /** Mana and Artifice. */
    private static final String MNA_ID = "mana-and-artifice";

    private static volatile Adapter irons;
    private static volatile Adapter ars;
    private static volatile Adapter mna;
    private static volatile boolean probed;

    private ManaDrainAdapters() {
    }

    /** One bound pool: how to read it, how to read its maximum, how to write it back. */
    private record Adapter(String modId, String version, Method accessor, Method getter, Method setter,
                           Method maximum, boolean viaLazyOptional) {
    }

    /**
     * Binds whichever pools are present and returns a one-line report naming each outcome.
     *
     * <p>The report is what {@code /crime debug} and the startup log show, and it always names every
     * installed mod — supported or not — so "installed but unsupported" can never be mistaken for
     * "absent".
     */
    public static synchronized String probe() {
        List<String> report = new ArrayList<>(3);
        irons = bindIrons(report);
        ars = bindArs(report);
        mna = bindManaAndArtifice(report);
        probed = true;
        return report.isEmpty() ? "no supported spell mod installed" : String.join("; ", report);
    }

    /**
     * Removes {@code fraction} of the player's maximum pool from their current pool.
     *
     * @param playerObject a {@code ServerPlayer}, passed as {@code Object} so the bridge needs no
     *                     shared type with this package
     * @return true when at least one supported pool was reduced
     */
    public static boolean drain(Object playerObject, double fraction) {
        if (playerObject == null || !Double.isFinite(fraction) || fraction <= 0.0D) {
            return false;
        }
        if (!probed) {
            probe();
        }
        boolean drained = false;
        drained |= apply(irons, playerObject, fraction);
        drained |= apply(ars, playerObject, fraction);
        drained |= apply(mna, playerObject, fraction);
        return drained;
    }

    /** What the last probe found, for diagnostics. */
    public static synchronized String status() {
        return probed ? describe() : "not probed";
    }

    private static String describe() {
        List<String> parts = new ArrayList<>(3);
        if (irons != null) {
            parts.add(irons.modId() + " " + irons.version() + " bound");
        }
        if (ars != null) {
            parts.add(ars.modId() + " " + ars.version() + " bound");
        }
        if (mna != null) {
            parts.add(mna.modId() + " " + mna.version() + " bound");
        }
        return parts.isEmpty() ? "no pool bound" : String.join("; ", parts);
    }

    // --- the three probes -----------------------------------------------------------------------------

    /**
     * Iron's Spells 'n Spellbooks: {@code MagicData.getPlayerMagicData(player)}, then {@code getMana}
     * and {@code setMana}, with the maximum read from the player's own max-mana attribute holder.
     */
    private static Adapter bindIrons(List<String> report) {
        if (!loaded(IRONS_ID)) {
            return null;
        }
        try {
            Class<?> magicData = Class.forName("io.redspace.ironsspellbooks.api.magic.MagicData");
            Method accessor = magicData.getMethod("getPlayerMagicData",
                    Class.forName("net.minecraft.world.entity.player.Player"));
            Method getter = magicData.getMethod("getMana");
            Method setter = magicData.getMethod("setMana", float.class);
            Method maximum = magicData.getMethod("getMaxMana");
            report.add(IRONS_ID + " " + version(IRONS_ID) + ": supported");
            return new Adapter(IRONS_ID, version(IRONS_ID), accessor, getter, setter, maximum, false);
        } catch (Throwable t) {
            report.add(IRONS_ID + " " + version(IRONS_ID) + ": unsupported build ("
                    + t.getClass().getSimpleName() + "); Silence drains nothing");
            return null;
        }
    }

    /**
     * Ars Nouveau: {@code CapabilityRegistry.getMana(entity)} returns a {@code LazyOptional}, whose
     * resolved value carries {@code getCurrentMana}, {@code setMana} and {@code getMaxMana}.
     */
    private static Adapter bindArs(List<String> report) {
        if (!loaded(ARS_ID)) {
            return null;
        }
        try {
            Class<?> registry =
                    Class.forName("com.hollingsworth.arsnouveau.setup.registry.CapabilityRegistry");
            Method accessor = registry.getMethod("getMana",
                    Class.forName("net.minecraft.world.entity.LivingEntity"));
            Class<?> manaCap = Class.forName("com.hollingsworth.arsnouveau.api.mana.IManaCap");
            Method getter = manaCap.getMethod("getCurrentMana");
            Method setter = manaCap.getMethod("setMana", double.class);
            Method maximum = manaCap.getMethod("getMaxMana");
            report.add(ARS_ID + " " + version(ARS_ID) + ": supported");
            return new Adapter(ARS_ID, version(ARS_ID), accessor, getter, setter, maximum, true);
        } catch (Throwable t) {
            report.add(ARS_ID + " " + version(ARS_ID) + ": unsupported build ("
                    + t.getClass().getSimpleName() + "); Silence drains nothing");
            return null;
        }
    }

    /**
     * Mana and Artifice: the player's magic capability, reached through its own provider's static
     * capability token. Its pool exposes {@code getCastingResource}, which carries the amount.
     */
    private static Adapter bindManaAndArtifice(List<String> report) {
        if (!loaded(MNA_ID)) {
            return null;
        }
        try {
            Class<?> magic = Class.forName("com.mna.api.capabilities.IPlayerMagic");
            Method getter = magic.getMethod("getCastingResource");
            Class<?> resource = Class.forName("com.mna.api.capabilities.IPlayerMagic")
                    .getMethod("getCastingResource").getReturnType();
            Method amount = resource.getMethod("getAmount");
            Method consume = resource.getMethod("consume", float.class);
            report.add(MNA_ID + " " + version(MNA_ID) + ": supported");
            return new Adapter(MNA_ID, version(MNA_ID), null, getter, consume, amount, false);
        } catch (Throwable t) {
            report.add(MNA_ID + " " + version(MNA_ID) + ": unsupported build ("
                    + t.getClass().getSimpleName() + "); Silence drains nothing");
            return null;
        }
    }

    // --- the drain ------------------------------------------------------------------------------------

    private static boolean apply(Adapter adapter, Object player, double fraction) {
        if (adapter == null) {
            return false;
        }
        try {
            if (MNA_ID.equals(adapter.modId())) {
                return drainManaAndArtifice(adapter, player, fraction);
            }
            Object pool = adapter.accessor().invoke(null, player);
            if (adapter.viaLazyOptional()) {
                pool = resolveLazy(pool);
            }
            if (pool == null) {
                return false;
            }
            double max = ((Number) adapter.maximum().invoke(pool)).doubleValue();
            double current = ((Number) adapter.getter().invoke(pool)).doubleValue();
            if (!Double.isFinite(max) || !Double.isFinite(current) || max <= 0.0D || current <= 0.0D) {
                return false;
            }
            double drained = Math.max(0.0D, current - max * fraction);
            if (adapter.setter().getParameterTypes()[0] == float.class) {
                adapter.setter().invoke(pool, (float) drained);
            } else {
                adapter.setter().invoke(pool, drained);
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Mana and Artifice consumes rather than assigning, so its pool is reduced, never overwritten. */
    private static boolean drainManaAndArtifice(Adapter adapter, Object player, double fraction) {
        try {
            Object magic = capability(player, "com.mna.capabilities.playerdata.magic.PlayerMagicProvider",
                    "MAGIC");
            if (magic == null) {
                return false;
            }
            Object resource = adapter.getter().invoke(magic);
            if (resource == null) {
                return false;
            }
            double amount = ((Number) adapter.maximum().invoke(resource)).doubleValue();
            if (!Double.isFinite(amount) || amount <= 0.0D) {
                return false;
            }
            adapter.setter().invoke(resource, (float) (amount * fraction));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Resolves a Forge {@code LazyOptional} without naming it.
     *
     * <p>{@code resolve()} returns an {@code Optional}; an empty one means the entity has no such
     * capability, which is a normal answer for a player another mod has excluded.
     */
    private static Object resolveLazy(Object lazy) throws Exception {
        if (lazy == null) {
            return null;
        }
        Object optional = lazy.getClass().getMethod("resolve").invoke(lazy);
        if (!(optional instanceof java.util.Optional<?> resolved)) {
            return null;
        }
        return resolved.orElse(null);
    }

    /** Reads a capability off a player through a provider's static token, entirely by name. */
    private static Object capability(Object player, String providerClass, String tokenField)
            throws Exception {
        Object token = Class.forName(providerClass).getField(tokenField).get(null);
        Method getCapability = Class.forName("net.minecraftforge.common.capabilities.ICapabilityProvider")
                .getMethod("getCapability",
                        Class.forName("net.minecraftforge.common.capabilities.Capability"));
        return resolveLazy(getCapability.invoke(player, token));
    }

    private static boolean loaded(String modId) {
        ModList list = ModList.get();
        return list != null && list.isLoaded(modId);
    }

    /** The installed version of one mod, or {@code unknown} when its metadata cannot be read. */
    private static String version(String modId) {
        try {
            ModList list = ModList.get();
            return list == null ? "unknown" : list.getModContainerById(modId)
                    .map(container -> container.getModInfo().getVersion().toString())
                    .orElse("unknown");
        } catch (Throwable t) {
            return "unknown";
        }
    }
}
