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
    /**
     * 0.5.1: a thief robbing a player. Distinct from {@link #THEFT}, which is a player stealing from a
     * villager, because the offender is an NPC and the two are read by different subsystems.
     */
    public static final ResourceLocation MUGGING = ResourceLocation.fromNamespaceAndPath(McaCrime.MOD_ID, "mugging");
    /** 0.5.1: a mugging a guard, a drawn weapon or a dead thief stopped before any property moved. */
    public static final ResourceLocation ATTEMPTED_MUGGING =
            ResourceLocation.fromNamespaceAndPath(McaCrime.MOD_ID, "attempted_mugging");
    /** Harming another player, recorded only when {@code pvpCountsAsCrime} is enabled. */
    public static final ResourceLocation ASSAULT_PLAYER = ResourceLocation.fromNamespaceAndPath(McaCrime.MOD_ID, "assault_player");
    /** Killing another player, recorded only when {@code pvpCountsAsCrime} is enabled. */
    public static final ResourceLocation MURDER_PLAYER = ResourceLocation.fromNamespaceAndPath(McaCrime.MOD_ID, "murder_player");
    /** 0.7.0: carrying an item the operator's contraband list forbids, found by a guard's search. */
    public static final ResourceLocation POSSESS_CONTRABAND =
            ResourceLocation.fromNamespaceAndPath(McaCrime.MOD_ID, "possess_contraband");
    /** 0.7.0: a villager who helped a player commit a crime, charged individually for the help. */
    public static final ResourceLocation AIDING_A_CRIMINAL =
            ResourceLocation.fromNamespaceAndPath(McaCrime.MOD_ID, "aiding_a_criminal");

    private CrimeIds() {
    }
}
