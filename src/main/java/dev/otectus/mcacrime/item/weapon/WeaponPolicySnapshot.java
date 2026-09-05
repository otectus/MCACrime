package dev.otectus.mcacrime.item.weapon;

import dev.otectus.mcacrime.McaCrimeConfig;
import io.netty.handler.codec.DecoderException;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * The exact inputs {@link WeaponRules#compile} takes, in a form that survives a network hop (0.5.1).
 *
 * <p>The client cannot simply read its own COMMON config file: that file is the <em>client's</em>
 * copy and on a multiplayer server it is not the one the server is gating on. A client that disagreed
 * would grey out the Crime button for a weapon the server accepts, or the reverse — the worst of both,
 * because the disagreement is silent. Shipping the compile inputs rather than a compiled verdict means
 * the two sides build byte-identical rules and stay identical for every item, including items nobody
 * thought to test.
 *
 * <p>{@code requireWeaponForCrimeMenu} travels with the lists for the same reason the lists do: a
 * server that has turned the weapon gate off entirely would otherwise be second-guessed by a client
 * whose own file still has it on, and the button would sit greyed out over a menu the server is
 * perfectly willing to open.
 *
 * <p>Pure and client-safe: no registry, no {@code ItemStack}, nothing that needs a server.
 */
public record WeaponPolicySnapshot(boolean allowOffHand, List<String> whitelist, List<String> blacklist,
                                   boolean autoDetect, double minAttackDamage, List<String> gunKeywords,
                                   List<String> weaponMods, boolean requireWeaponForCrimeMenu) {

    /** Defensive copies: the lists come from a mutable config and go into a long-lived client field. */
    public WeaponPolicySnapshot {
        whitelist = List.copyOf(whitelist);
        blacklist = List.copyOf(blacklist);
        gunKeywords = List.copyOf(gunKeywords);
        weaponMods = List.copyOf(weaponMods);
    }

    /** Reads the live COMMON config. Server side only; the client is told this, never asked to guess. */
    public static WeaponPolicySnapshot fromConfig() {
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        return new WeaponPolicySnapshot(
                safeBoolean(c.weaponTriggerAllowOffHand, true),
                strings(c.weaponWhitelist),
                strings(c.weaponBlacklist),
                safeBoolean(c.weaponAutoDetect, true),
                safeDouble(c.weaponAutoDetectMinAttackDamage, 3.0D),
                strings(c.weaponGunKeywords),
                strings(c.weaponMods),
                safeBoolean(c.requireWeaponForCrimeMenu, true));
    }

    /** The rules this policy compiles to. Identical on both sides by construction. */
    public WeaponRules toRules() {
        return WeaponRules.compile(whitelist, blacklist, autoDetect, minAttackDamage, gunKeywords, weaponMods);
    }

    /**
     * Eight fields, so this is written by hand rather than through {@code StreamCodec.composite},
     * which stops at six. Every count is checked before anything is allocated.
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, WeaponPolicySnapshot> STREAM_CODEC =
            StreamCodec.of(WeaponPolicySnapshot::write, WeaponPolicySnapshot::read);

    private static void write(RegistryFriendlyByteBuf buf, WeaponPolicySnapshot policy) {
        buf.writeBoolean(policy.allowOffHand());
        writeStrings(buf, policy.whitelist());
        writeStrings(buf, policy.blacklist());
        buf.writeBoolean(policy.autoDetect());
        buf.writeDouble(policy.minAttackDamage());
        writeStrings(buf, policy.gunKeywords());
        writeStrings(buf, policy.weaponMods());
        buf.writeBoolean(policy.requireWeaponForCrimeMenu());
    }

    private static WeaponPolicySnapshot read(RegistryFriendlyByteBuf buf) {
        boolean allowOffHand = buf.readBoolean();
        List<String> whitelist = readStrings(buf);
        List<String> blacklist = readStrings(buf);
        boolean autoDetect = buf.readBoolean();
        double minAttackDamage = buf.readDouble();
        List<String> gunKeywords = readStrings(buf);
        List<String> weaponMods = readStrings(buf);
        boolean requireWeaponForCrimeMenu = buf.readBoolean();
        return new WeaponPolicySnapshot(allowOffHand, whitelist, blacklist, autoDetect, minAttackDamage,
                gunKeywords, weaponMods, requireWeaponForCrimeMenu);
    }

    private static void writeStrings(RegistryFriendlyByteBuf buf, List<String> values) {
        List<String> bounded = values.size() > MAX_ENTRIES ? values.subList(0, MAX_ENTRIES) : values;
        buf.writeVarInt(bounded.size());
        bounded.forEach(value -> buf.writeUtf(value, MAX_ENTRY_LENGTH));
    }

    private static List<String> readStrings(RegistryFriendlyByteBuf buf) {
        int size = buf.readVarInt();
        if (size < 0 || size > MAX_ENTRIES) {
            throw new DecoderException("mcacrime: " + size + " weapon policy entries, at most "
                    + MAX_ENTRIES + " allowed");
        }
        List<String> values = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            values.add(buf.readUtf(MAX_ENTRY_LENGTH));
        }
        return values;
    }

    /** Ceilings on a decoded list, so a malformed packet cannot allocate without bound. */
    private static final int MAX_ENTRIES = 1024;
    private static final int MAX_ENTRY_LENGTH = 256;

    private static List<String> strings(ModConfigSpec.ConfigValue<List<? extends String>> value) {
        try {
            List<? extends String> list = value.get();
            return list == null ? List.of() : List.copyOf(list);
        } catch (IllegalStateException e) {
            return List.of();
        }
    }

    private static boolean safeBoolean(ModConfigSpec.BooleanValue value, boolean fallback) {
        try {
            return value.get();
        } catch (IllegalStateException e) {
            return fallback;
        }
    }

    private static double safeDouble(ModConfigSpec.DoubleValue value, double fallback) {
        try {
            return value.get();
        } catch (IllegalStateException e) {
            return fallback;
        }
    }
}
