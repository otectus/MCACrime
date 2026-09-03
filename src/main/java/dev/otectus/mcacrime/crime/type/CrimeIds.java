package dev.otectus.mcacrime.crime.type;

import dev.otectus.mcacrime.McaCrime;
import net.minecraft.resources.ResourceLocation;

/** The built-in crime-type ids — one source of truth for the classifier, builtins, and shipped JSON. */
public final class CrimeIds {

    public static final ResourceLocation HARM_VILLAGER = ResourceLocation.fromNamespaceAndPath(McaCrime.MOD_ID, "harm_villager");
    public static final ResourceLocation KILL_VILLAGER = ResourceLocation.fromNamespaceAndPath(McaCrime.MOD_ID, "kill_villager");
    public static final ResourceLocation ASSAULT_GUARD = ResourceLocation.fromNamespaceAndPath(McaCrime.MOD_ID, "assault_guard");
    public static final ResourceLocation JAILBREAK = ResourceLocation.fromNamespaceAndPath(McaCrime.MOD_ID, "jailbreak");
    /** Phase 4: taking an entity into unlawful captivity (spec §8). */
    public static final ResourceLocation KIDNAP = ResourceLocation.fromNamespaceAndPath(McaCrime.MOD_ID, "kidnap");
    /** Phase 4: a successful mugging/robbery of a villager (spec §8.6). */
    public static final ResourceLocation THEFT = ResourceLocation.fromNamespaceAndPath(McaCrime.MOD_ID, "theft");
    /** Phase 4: a mugging that turned lethal — heavier than a plain kill to favor robbery over murder (§8.6). */
    public static final ResourceLocation MUGGING_MURDER = ResourceLocation.fromNamespaceAndPath(McaCrime.MOD_ID, "mugging_murder");
    /** A ransom demand/settlement linked to an existing kidnapping, not a duplicate kidnapping. */
    public static final ResourceLocation EXTORTION = ResourceLocation.fromNamespaceAndPath(McaCrime.MOD_ID, "extortion");
    /** Harming another player, recorded only when {@code pvpCountsAsCrime} is enabled. */
    public static final ResourceLocation ASSAULT_PLAYER = ResourceLocation.fromNamespaceAndPath(McaCrime.MOD_ID, "assault_player");
    /** Killing another player, recorded only when {@code pvpCountsAsCrime} is enabled. */
    public static final ResourceLocation MURDER_PLAYER = ResourceLocation.fromNamespaceAndPath(McaCrime.MOD_ID, "murder_player");

    private CrimeIds() {
    }
}
