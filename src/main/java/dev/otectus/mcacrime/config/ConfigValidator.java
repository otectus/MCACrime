package dev.otectus.mcacrime.config;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.compat.CrimeIncidentMapping;
import dev.otectus.mcacrime.compat.TownsteadBridge;
import dev.otectus.mcacrime.compat.TownsteadCapability;
import dev.otectus.mcacrime.compat.TownsteadDiagnostics;
import dev.otectus.mcacrime.crime.type.CrimeTypeRegistry;
import dev.otectus.mcacrime.relationship.FamilyTier;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.IForgeRegistry;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Config validation (spec §12.3). The core checks are a pure function of plain values, so they are
 * unit-testable without a running game. The headline gate-check is band ordering (Blue threshold must be
 * above Red). Entity-id list entries are parse-checked here; their <em>registry</em> existence is an
 * additional best-effort pass in {@link #validateCurrentConfig()} (registries only exist at runtime).
 *
 * <p>Checks deferred to later phases (no datapack/MCA loaded yet): tag existence, malformed crime JSON,
 * missing jail structures, unknown profession IDs, impossible ransom/punishment tables.
 */
public final class ConfigValidator {

    private ConfigValidator() {
    }

    /**
     * Pure validation of the values that matter today. Returns a (possibly empty) list of
     * human-readable problems; empty means valid.
     */
    public static List<String> validate(long blueThreshold, long redThreshold, double unwitnessedFactor,
                                        int maxCaptivityRealMinutes, List<? extends String> protectedEntities,
                                        List<? extends String> responderEntities) {
        List<String> problems = new ArrayList<>();

        if (blueThreshold <= redThreshold) {
            problems.add("Band thresholds invalid: karmaBlueThreshold (" + blueThreshold
                    + ") must be greater than karmaRedThreshold (" + redThreshold + ").");
        }
        if (unwitnessedFactor < 0.0 || unwitnessedFactor > 1.0) {
            problems.add("unwitnessedKarmaFactor (" + unwitnessedFactor + ") must be between 0.0 and 1.0.");
        }
        if (maxCaptivityRealMinutes <= 0) {
            problems.add("maxCaptivityRealMinutes (" + maxCaptivityRealMinutes + ") must be positive.");
        }

        checkEntityIds("protectedEntities", protectedEntities, problems);
        checkEntityIds("responderEntities", responderEntities, problems);
        return problems;
    }

    private static void checkEntityIds(String listName, List<? extends String> ids, List<String> problems) {
        checkIds(listName, ids, "entity", true, problems);
    }

    /**
     * Parse-checks one id list. {@code noun} names what a plain entry is ("entity", "item"), so the same
     * check can report an item list without calling a mistyped item id an entity. Entity lists accept a
     * wildcard pattern and resolve it at use time; item lists do not, because nothing resolves one.
     */
    private static void checkIds(String listName, List<? extends String> ids, String noun,
                                 boolean allowWildcards, List<String> problems) {
        for (String id : ids) {
            if (id == null || id.isBlank()) {
                problems.add(listName + " contains a blank entry.");
                continue;
            }
            if (id.indexOf('*') >= 0) {
                if (allowWildcards) {
                    continue; // wildcard pattern — accepted, resolved at use time
                }
                problems.add(listName + " uses a wildcard ('" + id + "'), which is not supported here; "
                        + "list each " + noun + " id or use a #tag.");
                continue;
            }
            String toParse = id.startsWith("#") ? id.substring(1) : id;
            if (ResourceLocation.tryParse(toParse) == null) {
                problems.add(listName + " has an invalid " + (id.startsWith("#") ? "tag" : noun)
                        + " id: '" + id + "'.");
            }
        }
    }

    /**
     * The weapon-classification lists, as a pure function of the values.
     *
     * <p>A separate method for the reason {@link #validateJail} is: the existing signatures have tests
     * written against them. Nothing here is fatal — a malformed entry is dropped by the classifier and
     * reported, because one bad line must not take a server owner's whole weapon list down with it.
     */
    public static List<String> validateWeapons(List<? extends String> whitelist, List<? extends String> blacklist,
                                               List<? extends String> gunKeywords, List<? extends String> weaponMods,
                                               double minAttackDamage) {
        List<String> problems = new ArrayList<>();
        checkIds("weapons.whitelist", whitelist, "item", false, problems);
        checkIds("weapons.blacklist", blacklist, "item", false, problems);

        for (String entry : whitelist) {
            if (entry != null && !entry.isBlank() && blacklist.contains(entry)) {
                problems.add("weapons.whitelist and weapons.blacklist both list '" + entry
                        + "'. The blacklist wins, so the whitelist entry does nothing.");
            }
        }
        for (String keyword : gunKeywords) {
            if (keyword == null || keyword.isBlank()) {
                problems.add("weapons.gunKeywords contains a blank entry, which would match every item.");
            }
        }
        for (String namespace : weaponMods) {
            if (namespace == null || namespace.isBlank()) {
                problems.add("weapons.weaponMods contains a blank entry, which names no mod.");
            } else if (ResourceLocation.tryParse(namespace + ":x") == null) {
                problems.add("weapons.weaponMods has an invalid namespace: '" + namespace + "'.");
            }
        }
        if (minAttackDamage < 0.0) {
            problems.add("weapons.autoDetectMinAttackDamage (" + minAttackDamage + ") cannot be negative.");
        }
        return problems;
    }

    /**
     * The contraband list and the search settings, as a pure function of the values.
     *
     * <p>Mirrors {@link #validateWeapons} because it is the same kind of list, and non-fatal for the
     * same reason: one mistyped line must not take a server owner's whole contraband list, or their
     * config load, down with it. Tags are deliberately <em>not</em> checked here — item tags do not
     * exist while the config loads, so a tag entry can only be confirmed once they are bound, which is
     * what {@code ContrabandPolicy} does on {@code TagsUpdatedEvent}.
     */
    public static List<String> validateContraband(boolean enabled, List<? extends String> illegalItems,
                                                  String discoveryMode, double searchChance,
                                                  int searchIntervalTicks, double searchRadius) {
        List<String> problems = new ArrayList<>();
        checkIds("contraband.illegalItems", illegalItems, "item", false, problems);
        if (!isKnownDiscoveryMode(discoveryMode)) {
            problems.add("contraband.discoveryMode must be GUARD_PATROL, ARREST_ONLY or BOTH, not '"
                    + discoveryMode + "'.");
        }
        if (!enabled) {
            return problems;
        }
        if (searchChance <= 0.0D && !"ARREST_ONLY".equalsIgnoreCase(String.valueOf(discoveryMode).trim())) {
            problems.add("contraband.searchChance is 0 with patrol searches enabled, so no guard on patrol "
                    + "will ever search anybody; only an arrest can find contraband.");
        }
        if (searchIntervalTicks < 1) {
            problems.add("contraband.searchIntervalTicks (" + searchIntervalTicks + ") must be at least 1.");
        }
        if (searchRadius <= 0.0D) {
            problems.add("contraband.searchRadius (" + searchRadius + ") must be greater than 0, or no "
                    + "guard is ever near enough to search anybody.");
        }
        return problems;
    }

    /** The three values {@code contraband.discoveryMode} accepts, case-insensitively. */
    public static boolean isKnownDiscoveryMode(String mode) {
        if (mode == null) {
            return false;
        }
        String trimmed = mode.trim();
        return "GUARD_PATROL".equalsIgnoreCase(trimmed) || "ARREST_ONLY".equalsIgnoreCase(trimmed)
                || "BOTH".equalsIgnoreCase(trimmed);
    }

    /**
     * The victim-scoped mugging limits added in 0.7.0.
     *
     * <p>Ranges are enforced by {@code defineInRange}. What is worth reporting is the pair that parses
     * and then gives back exactly the behaviour these keys exist to end: no daily cap at all, and a
     * shared protection window shorter than the thief's own cooldown, which would leave the thief's
     * limit as the binding one again.
     */
    public static List<String> validateMugging(boolean enableNpcMugging, int maxMuggingsPerPlayerPerDay,
                                               int playerMugProtectionTicks, int mugCooldownTicks,
                                               int maxActiveThievesPerJurisdiction) {
        List<String> problems = new ArrayList<>();
        if (!enableNpcMugging) {
            return problems;
        }
        if (maxMuggingsPerPlayerPerDay == 0) {
            problems.add("criminalJobs.thief.maxMuggingsPerPlayerPerDay is 0, so there is no daily limit "
                    + "on how often one player may be robbed. That is a supported setting, only rarely "
                    + "intended alongside enableNpcMugging.");
        }
        if (playerMugProtectionTicks < mugCooldownTicks) {
            problems.add("criminalJobs.thief.playerMugProtectionTicks (" + playerMugProtectionTicks
                    + ") is shorter than mugCooldownTicks (" + mugCooldownTicks + "), so the thief's own "
                    + "cooldown is the binding one again and a second thief may rob the same player "
                    + "immediately.");
        }
        if (maxActiveThievesPerJurisdiction == 0) {
            problems.add("criminalJobs.thief.maxActiveThievesPerJurisdiction is 0, so no village will ever "
                    + "be assigned a thief; only wilderness thieves remain.");
        }
        return problems;
    }

    /**
     * The family-loyalty lists, as a pure function of the values.
     *
     * <p>Separate for the reason {@link #validateWeapons} is, and non-fatal for the same reason: an
     * unknown tier name is dropped from the scope and reported, because one mistyped line must not
     * take a server owner's whole loyalty scope down with it. A personality named in both lists is the
     * one combination that parses cleanly and then means nothing — the bonus and the penalty cancel,
     * so the entry silently does nothing at all.
     */
    public static List<String> validateFamilyLoyalty(boolean enabled, List<? extends String> scope,
                                                     List<? extends String> loyalPersonalities,
                                                     List<? extends String> lawfulPersonalities,
                                                     int threshold, double heartsWeight) {
        List<String> problems = new ArrayList<>();
        if (!enabled) {
            return problems;
        }
        int known = 0;
        for (String tier : scope) {
            if (tier == null || tier.isBlank()) {
                problems.add("relationship.familyLoyalty.familyLoyaltyScope contains a blank entry.");
            } else if (FamilyTier.parse(tier).isEmpty()) {
                problems.add("relationship.familyLoyalty.familyLoyaltyScope has an unknown family tier: '"
                        + tier + "'. Valid tiers are SPOUSE, PARENT, CHILD, SIBLING, EXTENDED, IN_LAW.");
            } else {
                known++;
            }
        }
        if (known == 0) {
            problems.add("enableFamilyLoyalty is on but familyLoyaltyScope names no valid tier, so no "
                    + "relative can ever decline to report a crime and the setting does nothing.");
        }
        for (String personality : loyalPersonalities) {
            if (personality != null && !personality.isBlank() && contains(lawfulPersonalities, personality)) {
                problems.add("relationship.familyLoyalty lists '" + personality + "' in both "
                        + "loyalPersonalities and lawfulPersonalities. The bonus and the penalty cancel, "
                        + "so the entry has no effect either way.");
            }
        }
        if (threshold == 0 && heartsWeight >= 0.0) {
            problems.add("loyaltyThreshold is 0, so every relative in scope keeps quiet about every "
                    + "crime regardless of hearts. That is a supported setting, only rarely intended.");
        }
        return problems;
    }

    /**
     * The accomplice and family-bail settings, as a pure function of the values.
     *
     * <p>Non-fatal for the reason {@link #validateFamilyLoyalty} is: an unknown tier name is dropped
     * from the scope and reported, because one mistyped line must not stop a server owner's relatives
     * helping with anything at all. The bail bounds are checked because a minimum above a maximum is
     * the one combination that parses, clamps cleanly, and then quotes every relative the same price
     * whatever their sentence.
     */
    public static List<String> validateAccomplices(boolean enabled, List<? extends String> scope,
                                                   boolean familyBail, int bailMin, int bailMax,
                                                   int accompliceJailTicks) {
        List<String> problems = new ArrayList<>();
        if (!enabled) {
            return problems;
        }
        int known = 0;
        for (String tier : scope) {
            if (tier == null || tier.isBlank()) {
                problems.add("npccrime.accomplices.accompliceScope contains a blank entry.");
            } else if (FamilyTier.parse(tier).isEmpty()) {
                problems.add("npccrime.accomplices.accompliceScope has an unknown family tier: '" + tier
                        + "'. Valid tiers are SPOUSE, PARENT, CHILD, SIBLING, EXTENDED, IN_LAW.");
            } else {
                known++;
            }
        }
        if (known == 0) {
            problems.add("enableAccomplices is on but accompliceScope names no valid tier, so no relative "
                    + "can ever be recruited and the setting does nothing.");
        }
        if (familyBail && bailMin > bailMax) {
            problems.add("bailMin (" + bailMin + ") is above bailMax (" + bailMax + "), so every bail quote "
                    + "clamps to the same price regardless of how much sentence is left.");
        }
        if (familyBail && accompliceJailTicks == 0) {
            problems.add("enableFamilyBail is on with accompliceJailTicks 0, so an arrested relative is "
                    + "released before anybody can be quoted a price for them.");
        }
        return problems;
    }

    private static boolean contains(List<? extends String> names, String wanted) {
        for (String name : names) {
            if (name != null && name.trim().equalsIgnoreCase(wanted.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Pure validation of the observation, reaction and enforcement blocks added in 0.4.0.
     *
     * <p>Separate again for the same reason {@link #validateIntegrations} is: the existing signature
     * is what the existing tests call. These are the combinations that produce a configuration which
     * parses cleanly and then quietly does nothing — the state the whole §22.3 audit exists to end.
     */
    public static List<String> validateBehaviour(boolean observations, boolean reactions, boolean dialogue,
                                                 int sightRadius, int hearingRadius, int reportRadius,
                                                 boolean bail, int bailCostPerMinute,
                                                 boolean npcCrime, boolean rescue, boolean kidnappingNpc) {
        List<String> problems = new ArrayList<>();

        if (reactions && !observations) {
            problems.add("enableVillagerReactions is on but enableObservations is off. Reactions are started "
                    + "by observations, so no villager will ever react to anything.");
        }
        if (hearingRadius > 0 && hearingRadius < sightRadius) {
            problems.add("hearingWitnessRadius (" + hearingRadius + ") is smaller than witnessRadius ("
                    + sightRadius + "). Anyone close enough to hear it can already see it, so the hearing "
                    + "witness role can never be produced.");
        }
        if (reportRadius < sightRadius) {
            problems.add("reportRadius (" + reportRadius + ") is smaller than witnessRadius (" + sightRadius
                    + "). A witness will often be unable to reach any guard, so reports will rarely be filed.");
        }
        if (bail && bailCostPerMinute <= 0) {
            problems.add("enableBail is on with bailCostPerMinute 0, so every sentence can be ended for free.");
        }
        if (npcCrime) {
            problems.add("enableNpcCrime is on, but NPC-initiated crime is not implemented in this release: "
                    + "no villager decides to commit a crime of its own accord, and the setting is a declared "
                    + "seam that changes nothing. The shipped villager-crime feature is player-initiated -- see "
                    + "npccrime.accomplices.enableAccomplices, which is on by default and independent of this.");
        }
        if (rescue && !kidnappingNpc) {
            problems.add("enableRescue is on but enableKidnappingNpc is off, so there will rarely be an "
                    + "NPC captive to rescue. This is legal, only likely unintended.");
        }
        if (!dialogue) {
            problems.add("enableDialogue is off: villagers will act but never say anything, which reads as "
                    + "missing content rather than as a setting.");
        }
        return problems;
    }

    /**
     * Pure validation of the {@code [integrations]} block.
     *
     * <p>Separate from {@link #validate} rather than folded into it, because that method's signature
     * is what the existing tests call and widening it would make an additive change look like a
     * breaking one. The checks here are the ones that turn a plausible-looking config into a queue
     * that never drains or a status string nothing recognises.
     */
    /**
     * The jail and escort settings, as a pure function of the values.
     *
     * <p>A separate method rather than more parameters on {@link #validateBehaviour}, matching the
     * precedent {@code validateIntegrations} set: the existing signatures have tests written against
     * them, and widening one to add a check is how those tests start being rewritten for reasons that
     * have nothing to do with what they assert.
     */
    public static List<String> validateJail(boolean buildHoldingCell, boolean fallbackEnabled,
                                            double assignedMaxDistance, int escortTimeoutTicks,
                                            double leashBlocks, double tetherBlocks) {
        List<String> problems = new ArrayList<>();
        if (!buildHoldingCell && !fallbackEnabled) {
            problems.add("buildHoldingCell is off and jailFallbackEnabled is off: every arrest will be "
                    + "refused unless an operator has run /crime assignjail nearby. Surrendering will "
                    + "reduce Heat, the guards will stand down, and nobody will ever be jailed.");
        }
        if (leashBlocks >= tetherBlocks) {
            problems.add("escortLeashBlocks (" + leashBlocks + ") is not smaller than escortTetherBlocks ("
                    + tetherBlocks + "): the escort would be abandoned before the lead ever pulled, so an "
                    + "arrested player could simply walk away.");
        }
        if (escortTimeoutTicks <= 0 && assignedMaxDistance > 0.0) {
            problems.add("arrestEscortTimeoutTicks is 0, so every arrest completes instantly by teleport "
                    + "and no guard ever walks a prisoner anywhere. That is a supported setting, but it "
                    + "makes jailAssignedMaxDistance the only thing deciding where they land.");
        }
        return problems;
    }

    /**
     * The guard-population settings.
     *
     * <p>The overlap with MCA is documented rather than flagged: running both is legitimate, and the
     * only genuinely broken configurations are a target that can never act and a cooldown that is not
     * one.
     */
    public static List<String> validateGuardPopulation(boolean enabled, double ratio, int minimum,
                                                       int maxPerPass, int scanInterval, int cooldown) {
        List<String> problems = new ArrayList<>();
        if (!enabled) {
            return problems;
        }
        if (ratio <= 0.0 && minimum <= 0) {
            problems.add("manageGuardPopulation is on but guardPopulationRatio is 0 and "
                    + "guardPopulationMinimum is 0, so the target is always zero and no village will "
                    + "ever gain a guard.");
        }
        if (maxPerPass <= 0) {
            problems.add("guardPopulationMaxPerPass must be at least 1, or no pass can convert anybody.");
        }
        if (cooldown < scanInterval) {
            problems.add("guardPopulationCooldownTicks (" + cooldown + ") is shorter than "
                    + "guardPopulationScanIntervalTicks (" + scanInterval + "), so the per-village "
                    + "cooldown never actually holds a village back and every pass re-examines it.");
        }
        return problems;
    }

    /**
     * The mask block, as a pure function of the values (0.7.0).
     *
     * <p>Every combination here parses and every one of them is legal; what they have in common is
     * that the operator almost certainly did not mean them. Two of the three describe Heat that is
     * banked and can never be collected, and the third describes a mask that makes crime free.
     */
    public static List<String> validateMask(boolean enabled, boolean suppressesHeat, boolean defersHeat,
                                            double removalWitnessRadius, int maskedPursuitTicks,
                                            boolean guardsChallengeMaskWearers) {
        List<String> problems = new ArrayList<>();
        if (!enabled) {
            return problems;
        }
        if (defersHeat && !suppressesHeat) {
            problems.add("mask.maskDefersHeat is on but mask.maskSuppressesHeat is off, so masked crimes "
                    + "apply their Heat immediately and there is never anything to defer.");
        }
        if (defersHeat && removalWitnessRadius <= 0.0) {
            problems.add("mask.maskRemovalWitnessRadius is 0 while mask.maskDefersHeat is on, so an unmask "
                    + "is never witnessed and deferred Heat is only ever collected at jail intake.");
        }
        if (suppressesHeat && maskedPursuitTicks <= 0 && !guardsChallengeMaskWearers) {
            problems.add("mask.maskSuppressesHeat is on with mask.maskedPursuitTicks 0 and "
                    + "mask.guardsChallengeMaskWearers off, so a witnessed masked crime has no consequence "
                    + "at all: no Heat, no pursuit, and no challenge.");
        }
        return problems;
    }

    /**
     * The Sand Bottle block, as a pure function of the values (0.7.2 §13.3).
     *
     * <p>Every range is already enforced by {@code defineInRange}. What is left are the combinations
     * that parse and then describe something the operator cannot have meant: a splash that outlasts a
     * direct hit, a duration under the minimum application so the effect is computed and then thrown
     * away every time, and a radius of zero on a thrown area tool.
     */
    public static List<String> validateSandBottle(boolean enabled, int cooldownTicks, int directDurationTicks,
                                                  int splashDurationTicks, double radius, int recoveryTicks,
                                                  boolean affectsPlayers) {
        List<String> problems = new ArrayList<>();
        if (!enabled) {
            return problems;
        }
        int minimum = dev.otectus.mcacrime.effect.SandExposurePolicy.MIN_APPLICATION_TICKS;
        if (splashDurationTicks > directDurationTicks) {
            problems.add("sandBottle.sandSplashDurationTicks (" + splashDurationTicks
                    + ") is greater than sandBottle.sandDirectDurationTicks (" + directDurationTicks
                    + "), so standing near the impact is worse than being hit by it.");
        }
        if (directDurationTicks < minimum) {
            problems.add("sandBottle.sandDirectDurationTicks (" + directDurationTicks
                    + ") is below the " + minimum + "-tick minimum application, so a direct hit is"
                    + " raised to that minimum rather than being the value configured here.");
        }
        if (splashDurationTicks > 0 && splashDurationTicks < minimum) {
            problems.add("sandBottle.sandSplashDurationTicks (" + splashDurationTicks
                    + ") is below the " + minimum + "-tick minimum application, so no splash victim is"
                    + " ever blinded and only direct hits do anything.");
        }
        if (radius <= 0.0D) {
            problems.add("sandBottle.sandRadius is 0, so a Sand Bottle only ever affects the target it"
                    + " directly strikes.");
        }
        if (recoveryTicks <= 0) {
            problems.add("sandBottle.sandRecoveryTicks (" + recoveryTicks + ") must be positive, or two"
                    + " throwers can alternate bottles and blind a target indefinitely.");
        }
        if (cooldownTicks <= 0) {
            problems.add("sandBottle.sandCooldownTicks is 0, so a full stack can be thrown in one second.");
        }
        if (affectsPlayers && recoveryTicks < minimum) {
            problems.add("sandBottle.sandAffectsPlayers is on with a recovery window under " + minimum
                    + " ticks, which is the configuration that makes player-versus-player sand a stun-lock.");
        }
        return problems;
    }

    /**
     * The guard-intervention block, as a pure function of the values.
     *
     * <p>Ranges are enforced by {@code defineInRange}; what is worth reporting is the pair that parses
     * and then cannot mean what it says — a response radius a guard may never chase across, and an
     * orphan timeout shorter than the escort timeout that would produce it.
     */
    public static List<String> validateGuardIntervention(double responseRadius, int pursuitTimeoutTicks,
                                                         double aggroRadius, boolean returnStolenGoods,
                                                         double stolenGoodsReturnRadius,
                                                         int escortTimeoutTicks, int orphanTicks) {
        List<String> problems = new ArrayList<>();
        if (responseRadius > aggroRadius) {
            problems.add("guardThiefResponseRadius (" + responseRadius + ") is larger than guardAggroRadius ("
                    + aggroRadius + "), so a guard notices muggings it is immediately told to stop chasing.");
        }
        if (pursuitTimeoutTicks < 40) {
            problems.add("guardThiefPursuitTimeoutTicks (" + pursuitTimeoutTicks
                    + ") is too short for a guard to cross guardAggroRadius; no pursuit would ever reach.");
        }
        if (returnStolenGoods && stolenGoodsReturnRadius <= 0.0D) {
            problems.add("returnStolenGoodsOnArrest is on but stolenGoodsReturnRadius is 0, so no victim "
                    + "is ever near enough and nothing is ever returned.");
        }
        if (escortTimeoutTicks > 0 && orphanTicks < escortTimeoutTicks) {
            problems.add("npcEscortOrphanTicks (" + orphanTicks + ") is shorter than arrestEscortTimeoutTicks ("
                    + escortTimeoutTicks + "), so an escort is jailed in place before it has run out of time.");
        }
        return problems;
    }

    /**
     * The currency selection, as a pure function of the value.
     *
     * <p>A separate method for the reason every other block here is: {@code validateIntegrations} has
     * tests written against its signature, and widening it to add a check is how those tests start
     * being rewritten for reasons unrelated to what they assert. Only the <em>shape</em> is checked --
     * whether an id is actually registered depends on which mods loaded, and {@code Currencies} already
     * warns once and falls back rather than failing.
     */
    /**
     * The physical-restraint session limits (0.7.5).
     *
     * <p>Both keys are range-bounded by the spec itself, so what is left to check is the pair of
     * values that are individually legal and jointly useless: a cap so small that a second player
     * cannot start anything, and a timeout so short that no human finishes a session before it is
     * dropped. Neither is an error, so both are reported rather than thrown.
     */
    public static List<String> validateRestraints(int maxConcurrentSessions, int sessionTimeoutTicks) {
        List<String> problems = new ArrayList<>();
        if (maxConcurrentSessions < 1) {
            problems.add("restraints.maxConcurrentSessions (" + maxConcurrentSessions + ") must be at "
                    + "least 1, or no player can ever struggle, pick a lock or search anybody.");
        }
        if (maxConcurrentSessions < 2) {
            problems.add("restraints.maxConcurrentSessions is 1, so only one player at a time on the "
                    + "whole server can have a restraint, lockpicking or frisking session open.");
        }
        if (sessionTimeoutTicks < 20) {
            problems.add("restraints.sessionTimeoutTicks (" + sessionTimeoutTicks + ") is under one "
                    + "second, so a session is dropped before its owner can act on it.");
        }
        return problems;
    }

    /**
     * The per-definition durability settings (0.7.5 M2.2).
     *
     * <p>Every value is range-bounded by the spec, so what is worth reporting is the settings that
     * are legal and still defeat the mechanic: gear so flimsy that one struggle input ends it, and a
     * hierarchy inverted so far that the strong restraint is the weak one. Neither is an error.
     *
     * <p>Arm and leg tape are reported separately on purpose. They are separate keys precisely
     * because the source read one for both, and a validator that folded them back into one message
     * would hide the setting that used to do nothing.
     */
    public static List<String> validateRestraintDurability(int handcuffs, int shackles,
                                                           int tapeArms, int tapeLegs, int tapeHead,
                                                           int hood, boolean headTapeMufflesTextChat) {
        List<String> problems = new ArrayList<>();
        if (headTapeMufflesTextChat) {
            problems.add("restraints.definitions.headTapeMufflesTextChat is true, so a head restraint "
                    + "is configured to take away a restrained player's typed chat. That is a "
                    + "moderation decision rather than a mechanic -- a gagged player cannot ask to be "
                    + "let out -- and it is off by default for that reason (§10.6).");
        }
        problems.addAll(reportFlimsy("durabilityHandcuffs", handcuffs));
        problems.addAll(reportFlimsy("durabilityShackles", shackles));
        problems.addAll(reportFlimsy("durabilityDuckTapeArms", tapeArms));
        problems.addAll(reportFlimsy("durabilityDuckTapeLegs", tapeLegs));
        problems.addAll(reportFlimsy("durabilityDuckTapeHead", tapeHead));
        problems.addAll(reportFlimsy("durabilityBundleHood", hood));
        if (shackles > handcuffs) {
            problems.add("restraints.definitions.durabilityShackles (" + shackles + ") exceeds "
                    + "durabilityHandcuffs (" + handcuffs + "), so the lighter restraint is the harder "
                    + "one to break out of.");
        }
        if (tapeArms > shackles || tapeLegs > shackles) {
            problems.add("restraints.definitions duck tape is tougher than shackles, which makes the "
                    + "craftable disposable restraint the strongest one in the game.");
        }
        return problems;
    }

    private static List<String> reportFlimsy(String key, int durability) {
        if (durability <= 1) {
            return List.of("restraints.definitions." + key + " is " + durability + ", so a single "
                    + "accepted struggle input ends that restraint.");
        }
        return List.of();
    }

    /**
     * The application settings (0.7.5 M2.3, M2.6).
     *
     * <p>{@code vulnerabilityGates} is the only one that can be spelled wrong rather than merely set
     * badly: an unrecognised gate name is silently permissive, which is exactly the wrong direction
     * for a list whose job is to refuse an application.
     */
    public static List<String> validateRestraintApplication(int channelTicks, double maxRangeBlocks,
                                                            boolean requireLineOfSight,
                                                            boolean allowSelfApplication,
                                                            double lowHealthFraction,
                                                            List<? extends String> vulnerabilityGates) {
        List<String> problems = new ArrayList<>();
        boolean lowHealthGate = vulnerabilityGates != null && vulnerabilityGates.stream()
                .anyMatch(gate -> gate != null && "low_health".equalsIgnoreCase(gate.trim()));
        if (lowHealthGate && lowHealthFraction <= 0.0D) {
            problems.add("restraints.application.vulnerabilityGates lists low_health while "
                    + "lowHealthFraction is " + lowHealthFraction + ", so no living subject ever "
                    + "qualifies and no restraint can be applied to anybody.");
        }
        if (lowHealthGate && lowHealthFraction >= 1.0D) {
            problems.add("restraints.application.lowHealthFraction is " + lowHealthFraction
                    + ", so every subject counts as wounded and the low_health gate refuses nothing.");
        }
        if (!lowHealthGate && lowHealthFraction != 0.35D) {
            problems.add("restraints.application.lowHealthFraction is set to " + lowHealthFraction
                    + " but vulnerabilityGates does not list low_health, so nothing reads it.");
        }
        // channelTicks = 0 is deliberately not reported. Immediate application is the shipped parity
        // default (§3.12), and a warning on every default install is noise that hides a real problem.
        if (channelTicks > 200) {
            problems.add("restraints.application.channelTicks (" + channelTicks + ") is over ten "
                    + "seconds of standing still beside a subject who is free to walk away, so almost "
                    + "no application will ever complete.");
        }
        if (maxRangeBlocks > 8.0D) {
            problems.add("restraints.application.maxRangeBlocks (" + maxRangeBlocks
                    + ") is beyond ordinary reach, so a restraint can be applied from further away "
                    + "than the target can see it coming.");
        }
        if (!requireLineOfSight) {
            problems.add("restraints.application.requireLineOfSight is false, so a restraint "
                    + "can be applied through a wall.");
        }
        if (!allowSelfApplication) {
            problems.add("restraints.application.allowSelfApplication is false; the self panel's "
                    + "restraint entries will refuse, which is a supported but unusual setting.");
        }
        if (vulnerabilityGates != null) {
            for (String gate : vulnerabilityGates) {
                if (gate == null || gate.isBlank()) {
                    problems.add("restraints.application.vulnerabilityGates contains a blank entry.");
                } else if (!KNOWN_VULNERABILITY_GATES.contains(gate.trim().toLowerCase(Locale.ROOT))) {
                    problems.add("restraints.application.vulnerabilityGates names an unknown gate '"
                            + gate + "'; it will never refuse anything. Known gates: "
                            + String.join(", ", KNOWN_VULNERABILITY_GATES) + ".");
                }
            }
        }
        return problems;
    }

    /**
     * The lock settings (0.7.5 M3, §3.7 and §3.16).
     *
     * <p>Two of these can be spelled wrong rather than merely set badly, and both fail <em>open</em>
     * when they are: an unrecognised policy name falls back to the safe value, which is the right
     * runtime behaviour and exactly the wrong thing to leave unsaid. The rest are switches whose off
     * position quietly removes a protection somebody thinks they have.
     */
    public static List<String> validateLocks(int maxKeysPerRing, String foreignLockPolicy,
                                             String automationPolicy, boolean protectFromBreaking,
                                             boolean protectFromExplosions, boolean protectFromPistons,
                                             boolean allowReinforcement) {
        List<String> problems = new ArrayList<>();
        if (maxKeysPerRing < 1) {
            problems.add("locks.maxKeysPerRing (" + maxKeysPerRing + ") is under 1, so no key ring can "
                    + "hold anything. Existing rings keep their keys; they simply accept no more.");
        }
        if (!known(foreignLockPolicy, "REFUSE", "IGNORE")) {
            problems.add("locks.foreignLockPolicy '" + foreignLockPolicy + "' is not REFUSE or IGNORE; "
                    + "REFUSE will be used.");
        }
        if (!known(automationPolicy, "BLOCK_ALL", "ALLOW_INSERT", "ALLOW_ALL")) {
            problems.add("locks.automationPolicy '" + automationPolicy + "' is not BLOCK_ALL, "
                    + "ALLOW_INSERT or ALLOW_ALL; BLOCK_ALL will be used.");
        }
        if ("ALLOW_ALL".equalsIgnoreCase(trimmed(automationPolicy))) {
            problems.add("locks.automationPolicy is ALLOW_ALL, so a hopper empties a locked safe. The "
                    + "lock then governs players only.");
        }
        if (!protectFromBreaking) {
            problems.add("locks.protectLockedBlocksFromBreaking is false, so a locked container opens "
                    + "with a pickaxe instead of a key.");
        }
        if (!protectFromExplosions) {
            problems.add("locks.protectLockedBlocksFromExplosions is false, so TNT is a lockpick.");
        }
        if (!protectFromPistons) {
            problems.add("locks.protectLockedBlocksFromPistons is false, so a locked container can be "
                    + "pushed away from the lock that protects it.");
        }
        if (!allowReinforcement) {
            problems.add("locks.allowPadlockReinforcement is false; every padlock keeps the ordinary "
                    + "pick profile however it was built.");
        }
        return problems;
    }

    /**
     * The lockpicking settings (0.7.5 §3.5).
     *
     * <p>The window pair is the one that can be made nonsensical while staying in range: a window of
     * nearly a full turn makes every angle a hit, and a window of almost nothing makes a lock that no
     * human opens. Both are reported, neither is an error, because an operator running an accessibility
     * preset deliberately widens it.
     */
    public static List<String> validateLockpicking(boolean enabled, int drainDivisor,
                                                   int minAttemptIntervalTicks, double windowBelow,
                                                   double windowAbove, double maxRangeBlocks,
                                                   boolean destructiveOutcome) {
        List<String> problems = new ArrayList<>();
        if (!enabled) {
            problems.add("lockpicking.enabled is false; a key is the only way through a lock "
                    + "and a lockpick does nothing at all.");
            return problems;
        }
        if (drainDivisor < 20) {
            problems.add("lockpicking.drainPerTickDivisor (" + drainDivisor + ") drains the meter so "
                    + "fast that no lock can be opened before it empties.");
        }
        if (windowBelow + windowAbove >= 180.0D) {
            problems.add("lockpicking window (" + windowBelow + " below, " + windowAbove + " above) "
                    + "covers half the dial or more, so almost any angle counts as an alignment.");
        }
        if (windowBelow + windowAbove <= 1.0D) {
            problems.add("lockpicking window (" + windowBelow + " below, " + windowAbove + " above) is "
                    + "under a degree wide, which no player hits reliably.");
        }
        if (minAttemptIntervalTicks > 20) {
            problems.add("lockpicking.minAttemptIntervalTicks (" + minAttemptIntervalTicks + ") allows "
                    + "under one attempt a second, so the meter drains faster than it can be raised.");
        }
        if (maxRangeBlocks > 8.0D) {
            problems.add("lockpicking.maxRangeBlocks (" + maxRangeBlocks + ") is beyond ordinary reach, "
                    + "so a lock can be picked from further away than the owner can see the picker.");
        }
        if (destructiveOutcome) {
            problems.add("lockpicking.destructiveOutcome is true: picking a door or a safe destroys it. "
                    + "A safe's contents are moved out first, exactly once, but the block is gone.");
        }
        return problems;
    }

    /** The prison fittings (0.7.5 M3.5, M5.4-M5.7). */
    public static List<String> validatePrison(int safeSlots) {
        List<String> problems = new ArrayList<>();
        if (safeSlots % 9 != 0) {
            problems.add("prison.safeSlots (" + safeSlots + ") is not a multiple of nine and will be "
                    + "rounded down to " + ((safeSlots / 9) * 9) + ". Nothing already stored is lost.");
        }
        if (safeSlots < 27) {
            problems.add("prison.safeSlots (" + safeSlots + ") is smaller than a chest. A safe already "
                    + "holding more than this keeps the surplus and stops showing it.");
        }
        return problems;
    }

    /**
     * The prison construction and furniture settings (0.7.5 M5.4-M5.7).
     *
     * <p>Every message here says what the code does rather than what a prison sounds like it should
     * do. "Unbreakable" is the word the specification forbids and it does not appear, because no
     * setting in this block produces it: {@code HARD_CONTAINMENT} stops a <em>prisoner</em>, and an
     * ordinary player with an iron pickaxe opens the wall either way.
     */
    public static List<String> validatePrisonConstruction(String breakingPolicy, boolean resistsExplosions,
                                                          boolean resistsPistons, boolean authorisedOnly) {
        List<String> problems = new ArrayList<>();
        if (dev.otectus.mcacrime.block.prison.ReinforcedBreakingPolicy.parse(breakingPolicy).isEmpty()) {
            problems.add("prison.reinforcedBreakingPolicy (" + breakingPolicy + ") is not a policy. "
                    + "Use PICKAXE_QUALIFIED or HARD_CONTAINMENT; PICKAXE_QUALIFIED is assumed.");
        }
        if (!authorisedOnly && !resistsExplosions && !resistsPistons
                && dev.otectus.mcacrime.block.prison.ReinforcedBreakingPolicy.parse(breakingPolicy)
                        .orElse(dev.otectus.mcacrime.block.prison.ReinforcedBreakingPolicy.PICKAXE_QUALIFIED)
                == dev.otectus.mcacrime.block.prison.ReinforcedBreakingPolicy.PICKAXE_QUALIFIED) {
            problems.add("prison: reinforced blocks currently resist nothing beyond an iron-pickaxe "
                    + "requirement -- explosions and pistons both move them. That is a valid build set; "
                    + "it is not containment.");
        }
        return problems;
    }

    /** The frisking rules (0.7.5 §3.8, M5.2-M5.3). Every problem here is a warning. */
    public static List<String> validateFrisking(double maxRangeBlocks, int sessionTimeoutTicks,
                                                int transferIntervalTicks, boolean requiresArmRestraint,
                                                boolean lawfulSeizureToEscrow) {
        List<String> problems = new ArrayList<>();
        if (!requiresArmRestraint) {
            problems.add("frisking.requiresArmRestraint is off: anybody within reach may be searched, "
                    + "restrained or not. Authority is still checked; physical helplessness is not.");
        }
        if (!lawfulSeizureToEscrow) {
            problems.add("frisking.lawfulSeizureToEscrow is off: a guard's seizures go into the guard's "
                    + "own inventory and no escrow receipt is written, so nothing returns them when "
                    + "custody ends.");
        }
        if (transferIntervalTicks == 0) {
            problems.add("frisking.transferIntervalTicks is 0: a search empties an inventory as fast as "
                    + "packets arrive. Bounded by the request budget, but no longer by a search delay.");
        }
        if (maxRangeBlocks > 6.0D) {
            problems.add("frisking.maxRangeBlocks (" + maxRangeBlocks + ") is beyond a player's own "
                    + "reach, so the subject can be searched from outside the range they could hit back "
                    + "from.");
        }
        if (sessionTimeoutTicks < 100) {
            problems.add("frisking.sessionTimeoutTicks (" + sessionTimeoutTicks + ") closes a search "
                    + "within five seconds of the last transfer.");
        }
        return problems;
    }

    /**
     * The transport engine's numbers (0.7.5 M4.1-M4.4).
     *
     * <p>The ordering rule is the one that matters: a tether that starts hurting a subject before it
     * starts pulling them has no "held but unharmed" band at all, which turns every escort into an
     * execution. The source ships the two values the right way round and never checks them.
     */
    public static List<String> validateTransport(double maxChainLength, double overextensionLength,
                                                 double suspensionDamagePerTick, boolean guardHarmless,
                                                 int maxTethersPerHolder) {
        List<String> problems = new ArrayList<>();
        if (!(overextensionLength > maxChainLength)) {
            problems.add("transport.overextensionLength (" + overextensionLength + ") is not greater than "
                    + "transport.maxChainLength (" + maxChainLength + "): the first tick of tension would "
                    + "also be the first tick of suspension damage, so every tether would injure whoever "
                    + "it holds the moment it engages.");
        }
        if (suspensionDamagePerTick <= 0.0D) {
            problems.add("transport.suspensionDamagePerTick is 0: an overextended tether pulls but never "
                    + "hurts. That is a supported setting; nothing else changes.");
        } else if (suspensionDamagePerTick >= 10.0D) {
            problems.add("transport.suspensionDamagePerTick (" + suspensionDamagePerTick + ") kills an "
                    + "unarmoured player in under a second past the overextension length.");
        }
        if (!guardHarmless) {
            problems.add("transport.guardTransportHarmless is false: a lawful escort's tether may kill the "
                    + "prisoner it is walking to a cell.");
        }
        if (maxTethersPerHolder > 16) {
            problems.add("transport.maxTethersPerHolder (" + maxTethersPerHolder + ") lets one holder lead "
                    + "a crowd; every one of them is corrected every tick.");
        }
        return problems;
    }

    /** The detention devices (0.7.5 M4.5-M4.7). */
    public static List<String> validateDetention(int pilloryBreakoutTransitions, boolean guillotineEnabled,
                                                 int guillotineDelayTicks) {
        List<String> problems = new ArrayList<>();
        if (pilloryBreakoutTransitions == 0) {
            problems.add("detention.pilloryBreakoutTransitions is 0: nobody breaks out of a pillory from "
                    + "the inside. Stale-occupancy cleanup still runs, so a broken or unloaded device "
                    + "still releases its occupant.");
        }
        if (!guillotineEnabled) {
            problems.add("detention.guillotineEnabled is false: the guillotine detains but never takes a "
                    + "life, whatever the capital sentencing settings say.");
        }
        if (guillotineDelayTicks > 100) {
            problems.add("detention.guillotineActivationDelayTicks (" + guillotineDelayTicks + ") is over "
                    + "five seconds between releasing the blade and the blow landing.");
        }
        return problems;
    }

    /**
     * The capital sentencing keys M4.10 owns (0.7.5 §3.19).
     *
     * <p>{@code requiresExecutionDevice = false} with the feature on is the one combination that is
     * refused outright: a capital sentence that needs no device is a death with no deliberate act
     * behind it, which is exactly what the user ruled out.
     */
    public static List<String> validateCapitalPunishment(boolean enabled, boolean requiresDevice,
                                                         int executionDelayTicks, boolean guardMayExecute) {
        return validateCapitalPunishment(enabled, requiresDevice, executionDelayTicks, guardMayExecute,
                true, false, 2400, 48);
    }

    /**
     * The whole {@code sentencing.capitalPunishment} group (0.7.5 §3.19, M6.6).
     *
     * <p>The four-argument form above is the device half M4.10 shipped; this is the same rules plus
     * the offence, eligibility and escort keys M6.6 adds. Every problem is a warning rather than a
     * refusal except the device one, for the reason stated there.
     */
    public static List<String> validateCapitalPunishment(boolean enabled, boolean requiresDevice,
                                                         int executionDelayTicks, boolean guardMayExecute,
                                                         boolean guardKillingIsCapital,
                                                         boolean npcOffendersEligible,
                                                         int condemnedEscortTimeoutTicks,
                                                         int executionSiteSearchRadius) {
        List<String> problems = new ArrayList<>();
        if (enabled && !requiresDevice) {
            problems.add("sentencing.capitalPunishment.requiresExecutionDevice is false while the feature "
                    + "is enabled. A capital sentence with no device requirement would be carried out by "
                    + "nothing deliberate at all; set enabled = false instead.");
        }
        if (enabled && executionDelayTicks == 0) {
            problems.add("sentencing.capitalPunishment.executionDelayTicks is 0: arming the device and the "
                    + "blade are the same moment, so there is no rescue or pardon window.");
        }
        if (enabled && !guardMayExecute) {
            problems.add("sentencing.capitalPunishment.guardMayExecute is false: only a player can carry "
                    + "out a sentence, and a condemned captive with no player willing to do it stays in "
                    + "custody indefinitely.");
        }
        if (enabled && !guardKillingIsCapital) {
            problems.add("sentencing.capitalPunishment.guardKillingIsCapital is false while the feature is "
                    + "enabled: killing a guard is the only offence that may ever produce a capital "
                    + "sentence, so nothing can qualify and every sentence is custodial.");
        }
        if (enabled && npcOffendersEligible) {
            problems.add("sentencing.capitalPunishment.npcOffendersEligible is true: a villager offender "
                    + "may be capitally sentenced by an NPC arrest with no player in the loop. That is a "
                    + "supported setting; nothing else changes.");
        }
        if (enabled && condemnedEscortTimeoutTicks < executionDelayTicks) {
            problems.add("sentencing.capitalPunishment.condemnedEscortTimeoutTicks ("
                    + condemnedEscortTimeoutTicks + ") is shorter than executionDelayTicks ("
                    + executionDelayTicks + "): the walk to the device gives up before the ceremony it "
                    + "exists to reach could finish. The captive is returned to a cell either way.");
        }
        if (enabled && executionSiteSearchRadius > 96) {
            problems.add("sentencing.capitalPunishment.executionSiteSearchRadius ("
                    + executionSiteSearchRadius + ") sends a guard across most of a loaded world looking "
                    + "for an assigned site.");
        }
        return problems;
    }

    /**
     * The {@code compatibility} group (0.7.5 §3.17, M6.2).
     *
     * <p>Every one of these is a switch whose off position removes something an operator may believe
     * is still in force, so each off position is stated rather than assumed. The coexistence policy is
     * the only value here that can be spelled wrong, and it fails to {@code WARN} when it is -- which
     * is the safe runtime behaviour and exactly the wrong thing to leave unsaid.
     */
    public static List<String> validateCompatibility(String cuffedCoexistence, boolean optionalAdapters,
                                                     boolean reportAdapterVersions, boolean cuffedInstalled) {
        List<String> problems = new ArrayList<>();
        if (!known(cuffedCoexistence, "WARN", "REFUSE")) {
            problems.add("compatibility.cuffedCoexistence must be WARN or REFUSE, not '"
                    + cuffedCoexistence + "'; WARN is used instead.");
        }
        if (cuffedInstalled && known(cuffedCoexistence, "REFUSE")) {
            problems.add("compatibility.cuffedCoexistence is REFUSE with Cuffed installed, so MCA: Crime "
                    + "applies no new restraints. Removing, recovering and loading existing ones stays "
                    + "enabled, so nobody is stranded in equipment this mod will not take off.");
        }
        if (!optionalAdapters) {
            problems.add("compatibility.optionalAdaptersEnabled is false: every optional-mod adapter is "
                    + "off, whatever is installed. Silence drains no pool, foreign inventory slots are "
                    + "not searchable, and a downed player reads as an ordinary one.");
        }
        if (!reportAdapterVersions) {
            problems.add("compatibility.reportAdapterVersions is false, so the startup log will not say "
                    + "which optional mods bound and which did not. An unsupported build then looks "
                    + "identical to a working one until a feature quietly does nothing.");
        }
        return problems;
    }

    /**
     * The preset pair (0.7.5 M7.2).
     *
     * <p>Only the spelling is checkable here: what a preset <em>did</em> is reported by
     * {@code RestraintPresets} at the moment it is applied, key by key, which is the only place that
     * knows what the values were before. A mismatch between the two keys is not a problem -- it is the
     * ordinary state of a config whose preset is about to be applied.
     */
    public static List<String> validatePreset(String preset, String appliedPreset) {
        List<String> problems = new ArrayList<>();
        if (!known(preset, "CUFFED_PARITY", "BALANCED_VILLAGE")) {
            problems.add("restraints.preset must be CUFFED_PARITY or BALANCED_VILLAGE, not '" + preset
                    + "'; the shipped parity tuning is used instead.");
        }
        if (!known(appliedPreset, "CUFFED_PARITY", "BALANCED_VILLAGE")) {
            problems.add("restraints.appliedPreset is '" + appliedPreset + "', which names no preset. It "
                    + "is managed automatically; restraints.preset will be applied once and rewrite it.");
        }
        return problems;
    }

    /**
     * The six restraint enchantments (0.7.5 §3.10, M6.1).
     *
     * <p>The Imbue numbers are the ones worth checking: a per-level share above the cap is simply
     * capped, and a cap of 1.0 with a live enchantment means a captor can take no damage at all while
     * they hold anybody -- which is a supported setting, but not one anybody should reach by accident.
     */
    public static List<String> validateEnchantments(List<? extends String> allowed, double perLevel,
                                                    double maxFraction, int maxRecipients,
                                                    double manaDrainPerTick, int effectDurationTicks,
                                                    int effectAmplifier) {
        List<String> problems = new ArrayList<>();
        java.util.Set<dev.otectus.mcacrime.enchantment.CrimeEnchantKind> parsed =
                dev.otectus.mcacrime.enchantment.EnchantmentApplicability.parseAllowed(allowed);
        if (allowed != null) {
            for (String name : allowed) {
                if (dev.otectus.mcacrime.enchantment.CrimeEnchantKind.parse(name).isEmpty()) {
                    problems.add("enchantments.allowed names '" + name + "', which is not one of this "
                            + "mod's six enchantments; it is ignored.");
                }
            }
        }
        if (parsed.isEmpty()) {
            problems.add("enchantments.allowed is empty: none of the six may be applied, and any already "
                    + "on an item has no effect. Existing items still load and can still be removed.");
        }
        if (parsed.contains(dev.otectus.mcacrime.enchantment.CrimeEnchantKind.IMBUE)) {
            if (maxFraction >= 1.0D) {
                problems.add("enchantments.imbueMaxTransferFraction is 1.0: a captor holding anybody in "
                        + "an Imbue restraint takes no damage at all.");
            }
            if (perLevel > maxFraction) {
                problems.add("enchantments.imbueTransferPerLevel (" + perLevel + ") is above "
                        + "imbueMaxTransferFraction (" + maxFraction + "); the cap wins at every level.");
            }
            if (maxRecipients > 32) {
                problems.add("enchantments.imbueMaxRecipients (" + maxRecipients + ") splits one hit "
                        + "between a crowd; every share is resolved on the damage path.");
            }
        }
        if (parsed.contains(dev.otectus.mcacrime.enchantment.CrimeEnchantKind.SILENCE)
                && manaDrainPerTick <= 0.0D) {
            problems.add("enchantments.manaDrainPerTick is 0: Silence is applicable and drains nothing.");
        }
        if (effectDurationTicks < 40) {
            problems.add("enchantments.effectDurationTicks (" + effectDurationTicks + ") is shorter than "
                    + "the two-second pass that refreshes it, so Famine, Shroud and Exhaust will flicker.");
        }
        if (effectAmplifier > 2) {
            problems.add("enchantments.effectAmplifier (" + effectAmplifier + ") is above the source's "
                    + "own figure of 1; a prisoner will starve or be blinded far harder than intended.");
        }
        return problems;
    }

    private static boolean known(String value, String... allowed) {
        String trimmed = trimmed(value);
        for (String candidate : allowed) {
            if (candidate.equalsIgnoreCase(trimmed)) {
                return true;
            }
        }
        return false;
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    // ------------------------------------------------------------------ retired keys (0.7.5 §5.2)

    /**
     * Every key 0.7.5 retired, in the spelling a server's own {@code .toml} still uses.
     *
     * <p>Values are deliberately <b>not</b> migrated (§10.5): each of these governed a mechanic that no
     * longer exists, and carrying a number across to a setting that means something else is worse than
     * dropping it. What is owed to an operator is being told, by name, which of their settings stopped
     * doing anything -- which is what {@link #retiredKeysIn} finds and {@link #retiredKeyReport} says
     * out loud, once, at startup and without failing it.
     */
    public static final List<String> RETIRED_KEYS = List.of(
            "captureChannelTicks",
            "captureMaxMoveBlocks",
            "captureMaxRangeBlocks",
            "captureRequireLineOfSight",
            "captureLowHealthFraction",
            "villagerCaptureRelaxedVulnerability",
            "captureChannelMultiplierRope",
            "captureChannelMultiplierCuffs",
            "captureChannelMultiplierLockedCuffs",
            "restraintEscapeChanceRope",
            "restraintEscapeChanceCuffs",
            "restraintEscapeChanceLockedCuffs",
            "captiveTetherBlocks",
            "captiveCanEscapeByDistance",
            "escapeWorkTicksRope",
            "escapeWorkTicksCuffs",
            "escapeWorkTicksLockedCuffs",
            "cuffEscapeRequiresLockpick",
            "escapeAttemptCooldownTicks",
            "renderCuffs",
            "renderEscortRope");

    /** What each retired key was replaced by, for the report. */
    private static final Map<String, String> RETIRED_REPLACEMENTS = Map.ofEntries(
            Map.entry("captureChannelTicks", "restraints.application.channelTicks"),
            Map.entry("captureMaxMoveBlocks", "no replacement: an application is decided once, not channelled at a distance"),
            Map.entry("captureMaxRangeBlocks", "restraints.application.maxRangeBlocks"),
            Map.entry("captureRequireLineOfSight", "restraints.application.requireLineOfSight"),
            Map.entry("captureLowHealthFraction", "restraints.application.lowHealthFraction"),
            Map.entry("villagerCaptureRelaxedVulnerability", "restraints.application.vulnerabilityGates (empty gates the same way)"),
            Map.entry("captureChannelMultiplierRope", "restraints.application.channelTicks (one duration for every restraint)"),
            Map.entry("captureChannelMultiplierCuffs", "restraints.application.channelTicks"),
            Map.entry("captureChannelMultiplierLockedCuffs", "restraints.application.channelTicks"),
            Map.entry("restraintEscapeChanceRope", "restraints.definitions.durabilityDuckTapeArms"),
            Map.entry("restraintEscapeChanceCuffs", "restraints.definitions.durabilityShackles"),
            Map.entry("restraintEscapeChanceLockedCuffs", "restraints.definitions.durabilityHandcuffs"),
            Map.entry("captiveTetherBlocks", "no replacement in 0.7.5: the hold is a tether record, not a radius"),
            Map.entry("captiveCanEscapeByDistance", "restraints.escape (struggling out, not walking out)"),
            Map.entry("escapeWorkTicksRope", "restraints.definitions.durabilityDuckTapeArms"),
            Map.entry("escapeWorkTicksCuffs", "restraints.definitions.durabilityShackles"),
            Map.entry("escapeWorkTicksLockedCuffs", "restraints.definitions.durabilityHandcuffs"),
            Map.entry("cuffEscapeRequiresLockpick", "lockpicking.enabled (M3); a key or a cutting tool otherwise"),
            Map.entry("escapeAttemptCooldownTicks", "restraints.escape.minWorkIntervalTicks"),
            Map.entry("renderCuffs", "client.renderWornRestraints (the worn models) and client.hudRestraintPanel (the panel)"),
            Map.entry("renderEscortRope", "client.renderTether"));

    /**
     * Which retired keys a config file still sets, in file order.
     *
     * <p>Pure, and text-based on purpose: the retired keys are no longer in the spec, so
     * {@code ForgeConfigSpec} cannot be asked about them -- it only knows what is declared today.
     * A line counts when it assigns the key at the start of a line, so a key named inside a comment
     * or inside a longer key ({@code captureChannelTicksLegacy}) is not reported.
     */
    public static List<String> retiredKeysIn(@Nullable List<String> lines) {
        List<String> found = new ArrayList<>();
        if (lines == null) {
            return found;
        }
        for (String raw : lines) {
            if (raw == null) {
                continue;
            }
            String line = raw.trim();
            if (line.startsWith("#")) {
                continue;
            }
            for (String key : RETIRED_KEYS) {
                if (found.contains(key)) {
                    continue;
                }
                if (line.startsWith(key)) {
                    String rest = line.substring(key.length()).trim();
                    if (rest.startsWith("=")) {
                        found.add(key);
                    }
                }
            }
        }
        return found;
    }

    /** The report lines for a set of surviving retired keys. Empty when there are none. */
    public static List<String> retiredKeyReport(List<String> keysFound) {
        List<String> report = new ArrayList<>();
        if (keysFound == null || keysFound.isEmpty()) {
            return report;
        }
        report.add("MCA: Crime 0.7.5 retired " + keysFound.size()
                + " setting(s) still present in the config; they are ignored and may be deleted:");
        for (String key : keysFound) {
            report.add("  - " + key + " -> " + RETIRED_REPLACEMENTS.getOrDefault(key, "no replacement"));
        }
        return report;
    }

    /**
     * The same report, read from the running server's own common config file.
     *
     * <p>Best effort by design: an unreadable or absent file is silence, never a startup failure. A
     * config problem an operator cannot act on is noise, and a mod that refuses to start over a key
     * it no longer uses would be worse than the key.
     */
    public static List<String> retiredKeyReport() {
        try {
            Path dir = net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get();
            List<String> lines = new ArrayList<>();
            // Both files, because 0.7.5 retired two presentation keys as well as the physical ones,
            // and an operator whose client still sets renderCuffs is owed the same sentence.
            for (String name : List.of(McaCrime.MOD_ID + "-common.toml", McaCrime.MOD_ID + "-client.toml")) {
                Path file = dir.resolve(name);
                if (Files.isReadable(file)) {
                    lines.addAll(Files.readAllLines(file, StandardCharsets.UTF_8));
                }
            }
            return retiredKeyReport(retiredKeysIn(lines));
        } catch (Throwable t) {
            return List.of();
        }
    }

    /** The gate names {@code restraints.application.vulnerabilityGates} understands. */
    public static final List<String> KNOWN_VULNERABILITY_GATES = List.of(
            "low_health", "sleeping", "unconscious", "already_restrained", "surrendered", "detained");

    /**
     * The struggle-work settings (0.7.5 M2.7).
     *
     * <p>The pair that matters is the interval against the per-second ceiling: a ceiling higher than
     * the interval allows is a number that reads as a tuning knob and cannot take effect, which is
     * the shape of setting that gets blamed for behaviour it never controlled.
     */
    public static List<String> validateRestraintEscape(int minWorkIntervalTicks, int maxInputsPerSecond,
                                                       boolean returnsWornItem, boolean dropItemWhenBroken) {
        List<String> problems = new ArrayList<>();
        if (minWorkIntervalTicks <= 1) {
            problems.add("restraints.escape.minWorkIntervalTicks is " + minWorkIntervalTicks
                    + ", so a macro can struggle every tick and the durability numbers mean nothing.");
        }
        int reachable = 20 / Math.max(1, minWorkIntervalTicks);
        if (maxInputsPerSecond > reachable) {
            problems.add("restraints.escape.maxInputsPerSecond (" + maxInputsPerSecond
                    + ") is above the " + reachable + " that escapeMinWorkIntervalTicks allows, so it "
                    + "never takes effect.");
        }
        if (!returnsWornItem) {
            problems.add("restraints.escape.returnsWornItem is false, so every restraint removed "
                    + "destroys the item somebody supplied.");
        }
        if (dropItemWhenBroken && !returnsWornItem) {
            problems.add("restraints.escape.dropItemWhenBroken is true while returnsWornItem is "
                    + "false, so breaking out of a restraint returns its item and unlocking one does not.");
        }
        return problems;
    }

    public static List<String> validateCurrency(String currencyId, String currencyItem) {
        List<String> problems = new ArrayList<>();
        if (currencyId == null || currencyId.isBlank()) {
            problems.add("integrations.currencyId is blank; it must name a currency, e.g. 'mcacrime:emerald'.");
        } else if (ResourceLocation.tryParse(currencyId.trim()) == null) {
            problems.add("integrations.currencyId is not a valid id: '" + currencyId + "'.");
        }
        // Checked whatever currencyId says, because a broken value here is still a broken value the
        // day somebody switches to 'mcacrime:item' and gets emeralds without remembering why.
        if (currencyItem == null || currencyItem.isBlank()) {
            problems.add("integrations.currencyItem is blank; it must name an item, e.g. 'minecraft:emerald'.");
        } else if (ResourceLocation.tryParse(currencyItem.trim()) == null) {
            problems.add("integrations.currencyItem is not a valid id: '" + currencyItem + "'.");
        } else if ("mcacrime:item".equals(currencyId == null ? null : currencyId.trim())
                && "minecraft:air".equals(currencyItem.trim())) {
            problems.add("integrations.currencyItem is 'minecraft:air' while currencyId is 'mcacrime:item'; "
                    + "air cannot be money, so payments would fall back to emeralds.");
        }
        return problems;
    }

    /**
     * The criminal-job block, as a pure function of the values.
     *
     * <p>The ranges themselves are already enforced by {@code defineInRange}. What is worth reporting
     * is the combination that parses and then means something the operator did not intend: a fence
     * minimum no village will ever reach, and both occupations switched off while the sweep still runs.
     */
    public static List<String> validateCriminalJobs(boolean enableThieves, boolean enableFences,
                                                    double villageThiefChance, double villageFenceChance,
                                                    double wildThiefChance, int minVillagePopulationForFence,
                                                    int cooldownDays, int scanIntervalTicks,
                                                    int staleRecordGraceDays) {
        List<String> problems = new ArrayList<>();
        problems.addAll(chance("villageThiefChance", villageThiefChance));
        problems.addAll(chance("villageFenceChance", villageFenceChance));
        problems.addAll(chance("wildThiefChance", wildThiefChance));
        if (minVillagePopulationForFence < 1) {
            problems.add("criminalJobs.minVillagePopulationForFence (" + minVillagePopulationForFence
                    + ") must be at least 1.");
        }
        if (cooldownDays < 0) {
            problems.add("criminalJobs.criminalAssignmentCooldownDays (" + cooldownDays
                    + ") cannot be negative.");
        }
        if (scanIntervalTicks < 1) {
            problems.add("criminalJobs.assignmentScanIntervalTicks (" + scanIntervalTicks
                    + ") must be at least 1.");
        }
        if (staleRecordGraceDays < 1) {
            problems.add("criminalJobs.staleRecordGraceDays (" + staleRecordGraceDays
                    + ") must be at least 1, or criminal records are dropped the day they are written.");
        }
        if (enableFences && villageFenceChance > 0.0D && minVillagePopulationForFence > 200) {
            problems.add("criminalJobs.minVillagePopulationForFence (" + minVillagePopulationForFence
                    + ") is larger than any MCA village, so no fence can ever be assigned.");
        }
        if (!enableThieves && !enableFences) {
            problems.add("criminalJobs.enableThieves and enableFences are both off, so no villager will "
                    + "ever take a criminal job; the mugging and fencing features are inert.");
        }
        return problems;
    }

    /**
     * The {@code criminalJobs.thief} block and the two NPC-mug intervals beside it.
     *
     * <p>Ranges are already enforced by {@code defineInRange}; what is worth reporting is the pair
     * that parses and then means something nobody intended — a hard-abort radius wider than the
     * radius guards are looked for in at all, a threshold no risk score can reach, and a search
     * radius further than a mugging can be held at.
     */
    public static List<String> validateThief(int mugDurationTicks, int mugCooldownTicks, int scanIntervalTicks,
                                             double targetSearchRadius, double guardAvoidRadius,
                                             double guardHardAbortRadius, double guardRiskAbortThreshold,
                                             int hudUpdateIntervalTicks, int weaponCheckIntervalTicks) {
        List<String> problems = new ArrayList<>();
        if (mugDurationTicks < 1) {
            problems.add("criminalJobs.thief.mugDurationTicks (" + mugDurationTicks
                    + ") must be at least 1, or a mugging would complete before the victim saw it.");
        }
        if (mugCooldownTicks < 0) {
            problems.add("criminalJobs.thief.mugCooldownTicks (" + mugCooldownTicks + ") cannot be negative.");
        }
        if (scanIntervalTicks < 1) {
            problems.add("criminalJobs.thief.scanIntervalTicks (" + scanIntervalTicks + ") must be at least 1.");
        }
        if (guardRiskAbortThreshold < 0.0D || guardRiskAbortThreshold > 1.0D) {
            problems.add("criminalJobs.thief.guardRiskAbortThreshold (" + guardRiskAbortThreshold
                    + ") must be between 0.0 and 1.0.");
        }
        if (guardHardAbortRadius > guardAvoidRadius) {
            problems.add("criminalJobs.thief.guardHardAbortRadius (" + guardHardAbortRadius
                    + ") is larger than guardAvoidRadius (" + guardAvoidRadius
                    + "), so the guards it describes are never looked for and it can never fire.");
        }
        if (guardRiskAbortThreshold <= 0.0D && guardAvoidRadius > 0.0D) {
            problems.add("criminalJobs.thief.guardRiskAbortThreshold is 0, so any guard at all refuses "
                    + "every target and no thief will ever mug anybody.");
        }
        if (targetSearchRadius > 0.0D && targetSearchRadius < 6.0D) {
            problems.add("criminalJobs.thief.targetSearchRadius (" + targetSearchRadius
                    + ") is smaller than the six blocks a mugging can be held at; thieves would only "
                    + "ever pick victims already standing on top of them.");
        }
        if (hudUpdateIntervalTicks < 1) {
            problems.add("npccrime.npcMugHudUpdateIntervalTicks (" + hudUpdateIntervalTicks
                    + ") must be at least 1.");
        }
        if (weaponCheckIntervalTicks < 1) {
            problems.add("npccrime.npcMugWeaponCheckIntervalTicks (" + weaponCheckIntervalTicks
                    + ") must be at least 1, or drawing a weapon would never stop a mugging.");
        }
        return problems;
    }

    /**
     * The {@code criminalJobs.thief} theft block.
     *
     * <p>Ranges are enforced by {@code defineInRange}; what is worth reporting is the pair that
     * parses and then cannot mean what it says — a minimum above the maximum, and the combination
     * that leaves a thief able to take nothing at all.
     */
    public static List<String> validateTheft(int minCurrencySteal, int maxCurrencySteal,
                                             boolean stealAllIfBelowMinimum, boolean protectHotbar,
                                             boolean protectArmor, boolean protectOffhand,
                                             int stolenGoodsPersistenceDays) {
        List<String> problems = new ArrayList<>();
        if (minCurrencySteal < 0) {
            problems.add("criminalJobs.thief.minCurrencySteal (" + minCurrencySteal + ") cannot be negative.");
        }
        if (maxCurrencySteal < 0) {
            problems.add("criminalJobs.thief.maxCurrencySteal (" + maxCurrencySteal + ") cannot be negative.");
        }
        if (minCurrencySteal > maxCurrencySteal) {
            problems.add("criminalJobs.thief.minCurrencySteal (" + minCurrencySteal
                    + ") is larger than maxCurrencySteal (" + maxCurrencySteal
                    + "); the minimum is clamped down to the maximum and never applies.");
        }
        if (maxCurrencySteal == 0 && protectHotbar && protectArmor && protectOffhand) {
            // Slots 9-35 are still eligible, so this is not fatal -- but a pack that also empties the
            // main inventory of every player has built a thief who can only ever fail.
            problems.add("criminalJobs.thief.maxCurrencySteal is 0, so muggings can only ever take an "
                    + "ordinary inventory item; a victim carrying nothing in slots 9-35 loses nothing.");
        }
        if (!stealAllIfBelowMinimum && minCurrencySteal > maxCurrencySteal) {
            problems.add("criminalJobs.thief.stealAllIfBelowMinimum is off and the minimum exceeds the "
                    + "maximum, so currency theft can never happen.");
        }
        if (stolenGoodsPersistenceDays < 0 || stolenGoodsPersistenceDays > 365) {
            problems.add("criminalJobs.thief.stolenGoodsPersistenceDays (" + stolenGoodsPersistenceDays
                    + ") must be between 0 and 365.");
        }
        return problems;
    }

    /**
     * The {@code criminalJobs.fence} block.
     *
     * <p>Ranges are enforced by {@code defineInRange}; what is worth reporting is the pair that
     * parses and then cannot mean what it says — a floor above the ceiling, which would clamp every
     * price to a single value, and a buy ratio of 1 or more, which would let a player sell an item
     * back for what they paid and print money out of one fence.
     */
    public static List<String> validateFence(double maxKarmaDiscount, double maxHeatMarkup,
                                             double wantedMarkup, double minimumPriceMultiplier,
                                             double maximumPriceMultiplier, double buyPriceRatio,
                                             int defaultBasePrice, int offerCount, int offerMaxUses,
                                             int restockIntervalDays) {
        List<String> problems = new ArrayList<>();
        problems.addAll(fraction("fence.maxKarmaDiscount", maxKarmaDiscount));
        problems.addAll(fraction("fence.maxHeatMarkup", maxHeatMarkup));
        problems.addAll(fraction("fence.wantedMarkup", wantedMarkup));
        if (minimumPriceMultiplier <= 0.0D) {
            problems.add("criminalJobs.fence.minimumPriceMultiplier (" + minimumPriceMultiplier
                    + ") must be greater than 0, or a fence would give its stock away.");
        }
        if (minimumPriceMultiplier >= maximumPriceMultiplier) {
            problems.add("criminalJobs.fence.minimumPriceMultiplier (" + minimumPriceMultiplier
                    + ") must be below maximumPriceMultiplier (" + maximumPriceMultiplier
                    + "), or every price is clamped to one value and Karma and Heat stop mattering.");
        }
        if (buyPriceRatio > minimumPriceMultiplier) {
            // Warned rather than fatal, because FencePolicy clamps it to this bound on the way in and
            // the game is playable with the clamped value. Reported every reload, and the clamp is
            // idempotent, so a second reload of the same file says the same thing and changes nothing.
            problems.add("criminalJobs.fence.buyPriceRatio (" + buyPriceRatio
                    + ") is above minimumPriceMultiplier (" + minimumPriceMultiplier
                    + "), so a fence could pay more for an item than the least it ever charges for one;"
                    + " it has been clamped to " + minimumPriceMultiplier + " for pricing.");
        }
        if (buyPriceRatio <= 0.0D || buyPriceRatio >= 1.0D) {
            problems.add("criminalJobs.fence.buyPriceRatio (" + buyPriceRatio
                    + ") must be between 0.0 and 1.0 exclusive; at 1.0 or above a fence pays what it "
                    + "charges and buying then re-selling is free money.");
        }
        if (defaultBasePrice < 1) {
            problems.add("criminalJobs.fence.defaultBasePrice (" + defaultBasePrice
                    + ") must be at least 1.");
        }
        if (offerCount < 1) {
            problems.add("criminalJobs.fence.offerCount (" + offerCount
                    + ") must be at least 1, or a fence opens an empty screen.");
        }
        if (offerMaxUses < 1) {
            problems.add("criminalJobs.fence.offerMaxUses (" + offerMaxUses
                    + ") must be at least 1, or no trade a fence offers could ever be taken.");
        }
        if (restockIntervalDays < 0) {
            problems.add("criminalJobs.fence.restockIntervalDays (" + restockIntervalDays
                    + ") cannot be negative.");
        }
        return problems;
    }

    /**
     * The bounty block, as a pure function of the values.
     *
     * <p>A separate method for the reason every other block here is. {@code defineInRange} already
     * guards each key on its own; what is worth reporting is the pair that parses cleanly and then
     * quietly makes bounty hunting impossible or free — a floor above the ceiling, a price of nothing,
     * or both payout routes switched off while the system is still nominally enabled.
     */
    public static List<String> validateBounty(boolean enabled, int baseBounty, int minBounty, int maxBounty,
                                              double severityRewardScale, double fineRewardShare,
                                              int repeatOffenderBonus, boolean payForKills,
                                              boolean payForAliveCapture, double killMultiplier,
                                              double aliveCaptureMultiplier, int karmaReward,
                                              int claimRetentionDays, double deliveryRadius) {
        List<String> problems = new ArrayList<>();
        if (minBounty > maxBounty) {
            problems.add("bounty.minBounty (" + minBounty + ") cannot exceed bounty.maxBounty (" + maxBounty
                    + "); every price would clamp to the ceiling and the whole formula would stop mattering.");
        }
        if (severityRewardScale < 0.0D || severityRewardScale > 100.0D) {
            problems.add("bounty.severityRewardScale (" + severityRewardScale + ") must be between 0.0 and 100.0.");
        }
        if (fineRewardShare < 0.0D || fineRewardShare > 1.0D) {
            problems.add("bounty.fineRewardShare (" + fineRewardShare + ") must be between 0.0 and 1.0.");
        }
        if (repeatOffenderBonus < 0) {
            problems.add("bounty.repeatOffenderBonus (" + repeatOffenderBonus + ") cannot be negative.");
        }
        if (killMultiplier < 0.0D || killMultiplier > 10.0D) {
            problems.add("bounty.killMultiplier (" + killMultiplier + ") must be between 0.0 and 10.0.");
        }
        if (aliveCaptureMultiplier < 0.0D || aliveCaptureMultiplier > 10.0D) {
            problems.add("bounty.aliveCaptureMultiplier (" + aliveCaptureMultiplier
                    + ") must be between 0.0 and 10.0.");
        }
        if (karmaReward < 0 || karmaReward > 50) {
            problems.add("bounty.karmaReward (" + karmaReward + ") must be between 0 and 50.");
        }
        if (claimRetentionDays < 1) {
            problems.add("bounty.claimRetentionDays (" + claimRetentionDays
                    + ") must be at least 1; a claim forgotten the same day it is paid can be paid again.");
        }
        if (deliveryRadius < 1.0D || deliveryRadius > 16.0D) {
            problems.add("bounty.deliveryRadius (" + deliveryRadius + ") must be between 1.0 and 16.0.");
        }
        if (enabled && !payForKills && !payForAliveCapture) {
            problems.add("bounty.enabled is on with both payForKills and payForAliveCapture off, so no "
                    + "bounty can ever be collected by any route.");
        }
        if (enabled && maxBounty == 0) {
            problems.add("bounty.maxBounty is 0, so every bounty pays nothing however bad the outlaw is.");
        }
        if (enabled && baseBounty == 0 && severityRewardScale == 0.0D && fineRewardShare == 0.0D
                && repeatOffenderBonus == 0) {
            problems.add("Every term of the bounty formula is 0, so every price collapses to minBounty "
                    + "and a hunter is paid the same for a pickpocket as for a murderer.");
        }
        return problems;
    }

    private static List<String> fraction(String key, double value) {
        return value < 0.0D || value > 1.0D
                ? List.of("criminalJobs." + key + " (" + value + ") must be between 0.0 and 1.0.")
                : List.of();
    }

    private static List<String> chance(String key, double value) {
        return value < 0.0D || value > 1.0D
                ? List.of("criminalJobs." + key + " (" + value + ") must be between 0.0 and 1.0.")
                : List.of();
    }

    /**
     * The threat-compliance block, as a pure function of the values.
     *
     * <p>A separate method for the reason every other block here is. The ranges are already enforced by
     * {@code defineInRange}, so what is worth reporting is the combination that parses cleanly and then
     * quietly does nothing: a freeze nobody can be held by, and a slowdown that does not slow.
     */
    public static List<String> validateCompliance(double speedMultiplier, boolean freezeComplyingVictims,
                                                  boolean armedVillagersCanResist, double resistThreshold,
                                                  double helpThreshold) {
        List<String> problems = new ArrayList<>();
        if (speedMultiplier <= 0.0 || speedMultiplier > 1.0) {
            problems.add("reactions.civilianCrimeReactionSpeedMultiplier (" + speedMultiplier
                    + ") must be above 0.0 and at most 1.0.");
        }
        if (resistThreshold < 0.0 || resistThreshold > 1.0) {
            problems.add("reactions.complianceResistThreshold (" + resistThreshold
                    + ") must be between 0.0 and 1.0.");
        }
        if (helpThreshold < 0.0 || helpThreshold > 1.0) {
            problems.add("reactions.complianceHelpThreshold (" + helpThreshold
                    + ") must be between 0.0 and 1.0.");
        }
        if (resistThreshold <= 0.0 && !armedVillagersCanResist) {
            problems.add("reactions.complianceResistThreshold is 0 with armedVillagersCanResist off, so "
                    + "every villager resists every threat and nobody can ever be mugged.");
        }
        if (!freezeComplyingVictims && helpThreshold >= 1.0 && resistThreshold >= 1.0) {
            problems.add("reactions.freezeComplyingVictims is off and both compliance thresholds are 1.0, "
                    + "so every threatened villager can only ever run.");
        }
        return problems;
    }

    public static List<String> validateIntegrations(int pumpIntervalTicks, int pumpBudgetPerTick,
                                                    int maxDeliveryAttempts, int retryBaseDelayTicks,
                                                    int retryMaxDelayTicks, int dedupeRetentionTicks,
                                                    String fineResolutionStatus,
                                                    String servedResolutionStatus) {
        List<String> problems = new ArrayList<>();
        if (pumpIntervalTicks <= 0) {
            problems.add("integrations.pumpIntervalTicks must be positive; queued cross-mod writes "
                    + "would never be delivered.");
        }
        if (pumpBudgetPerTick <= 0) {
            problems.add("integrations.pumpBudgetPerTick must be positive; queued cross-mod writes "
                    + "would never be delivered.");
        }
        if (maxDeliveryAttempts <= 0) {
            problems.add("integrations.maxDeliveryAttempts must be at least 1.");
        }
        if (retryBaseDelayTicks > retryMaxDelayTicks) {
            problems.add("integrations.retryBaseDelayTicks (" + retryBaseDelayTicks
                    + ") is above retryMaxDelayTicks (" + retryMaxDelayTicks
                    + "); the backoff ceiling would sit below its own starting point.");
        }
        if (dedupeRetentionTicks <= 0) {
            problems.add("integrations.dedupeRetentionTicks must be positive; a replayed transaction "
                    + "would be applied a second time.");
        }
        if (!CrimeIncidentMapping.isValidConfiguredStatus(fineResolutionStatus)) {
            problems.add("integrations.reputation.fineResolutionStatus must be 'atoned' or 'apologized', "
                    + "not '" + fineResolutionStatus + "'.");
        }
        if (!CrimeIncidentMapping.isValidConfiguredStatus(servedResolutionStatus)) {
            problems.add("integrations.reputation.servedResolutionStatus must be 'atoned' or 'apologized', "
                    + "not '" + servedResolutionStatus + "'.");
        }
        return problems;
    }

    /**
     * Runtime validation against the live config, plus a best-effort registry-existence check on the
     * entity-id lists. Used by {@code /crime validate} and load-time validation.
     */
    public static List<String> validateCurrentConfig() {
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        List<String> problems = validate(
                c.karmaBlueThreshold.get(),
                c.karmaRedThreshold.get(),
                c.unwitnessedKarmaFactor.get(),
                c.maxCaptivityRealMinutes.get(),
                c.protectedEntities.get(),
                c.responderEntities.get());

        problems.addAll(validateJail(
                c.buildHoldingCell.get(),
                c.jailFallbackEnabled.get(),
                c.jailAssignedMaxDistance.get(),
                c.arrestEscortTimeoutTicks.get(),
                c.escortLeashBlocks.get(),
                c.escortTetherBlocks.get()));
        problems.addAll(validateGuardPopulation(
                c.manageGuardPopulation.get(),
                c.guardPopulationRatio.get(),
                c.guardPopulationMinimum.get(),
                c.guardPopulationMaxPerPass.get(),
                c.guardPopulationScanIntervalTicks.get(),
                c.guardPopulationCooldownTicks.get()));
        problems.addAll(validateIntegrations(
                c.pumpIntervalTicks.get(),
                c.pumpBudgetPerTick.get(),
                c.maxDeliveryAttempts.get(),
                c.retryBaseDelayTicks.get(),
                c.retryMaxDelayTicks.get(),
                c.dedupeRetentionTicks.get(),
                c.fineResolutionStatus.get(),
                c.servedResolutionStatus.get()));
        problems.addAll(validateGuardIntervention(
                c.guardThiefResponseRadius.get(),
                c.guardThiefPursuitTimeoutTicks.get(),
                c.guardAggroRadius.get(),
                c.returnStolenGoodsOnArrest.get(),
                c.stolenGoodsReturnRadius.get(),
                c.arrestEscortTimeoutTicks.get(),
                c.npcEscortOrphanTicks.get()));
        problems.addAll(validateCurrency(c.currencyId.get(), c.currencyItem.get()));
        problems.addAll(validateRestraints(
                c.maxConcurrentSessions.get(),
                c.sessionTimeoutTicks.get()));
        problems.addAll(validatePreset(c.preset.get().name(), c.appliedPreset.get()));
        problems.addAll(validateCompatibility(
                c.cuffedCoexistence.get(),
                c.optionalAdaptersEnabled.get(),
                c.reportAdapterVersions.get(),
                dev.otectus.mcacrime.compat.CuffedCoexistence.installed()));
        problems.addAll(validateRestraintDurability(
                c.durabilityHandcuffs.get(),
                c.durabilityShackles.get(),
                c.durabilityDuckTapeArms.get(),
                c.durabilityDuckTapeLegs.get(),
                c.durabilityDuckTapeHead.get(),
                c.durabilityBundleHood.get(),
                c.headTapeMufflesTextChat.get()));
        problems.addAll(validateRestraintApplication(
                c.applicationChannelTicks.get(),
                c.applicationMaxRangeBlocks.get(),
                c.applicationRequireLineOfSight.get(),
                c.allowSelfApplication.get(),
                c.lowHealthFraction.get(),
                c.vulnerabilityGates.get()));
        problems.addAll(validateRestraintEscape(
                c.escapeMinWorkIntervalTicks.get(),
                c.escapeMaxInputsPerSecond.get(),
                c.escapeReturnsWornItem.get(),
                c.dropItemWhenBroken.get()));
        problems.addAll(validateLocks(
                c.maxKeysPerRing.get(),
                c.foreignLockPolicy.get(),
                c.lockAutomationPolicy.get(),
                c.protectLockedBlocksFromBreaking.get(),
                c.protectLockedBlocksFromExplosions.get(),
                c.protectLockedBlocksFromPistons.get(),
                c.allowPadlockReinforcement.get()));
        problems.addAll(validateLockpicking(
                c.enableLockpicking.get(),
                c.lockpickDrainPerTickDivisor.get(),
                c.lockpickMinAttemptIntervalTicks.get(),
                c.lockpickWindowBelowDegrees.get(),
                c.lockpickWindowAboveDegrees.get(),
                c.lockpickMaxRangeBlocks.get(),
                c.lockpickDestructiveOutcome.get()));
        problems.addAll(validatePrison(c.safeSlots.get()));
        problems.addAll(validatePrisonConstruction(
                c.reinforcedBreakingPolicy.get(), c.reinforcedResistsExplosions.get(),
                c.reinforcedResistsPistons.get(), c.reinforcedAuthorisedRemovalOnly.get()));
        problems.addAll(validateFrisking(
                c.friskMaxRangeBlocks.get(), c.friskSessionTimeoutTicks.get(),
                c.friskTransferIntervalTicks.get(), c.friskRequiresArmRestraint.get(),
                c.friskLawfulSeizureToEscrow.get()));
        problems.addAll(validateTransport(
                c.maxChainLength.get(), c.overextensionLength.get(), c.suspensionDamagePerTick.get(),
                c.guardTransportHarmless.get(), c.maxTethersPerHolder.get()));
        problems.addAll(validateDetention(
                c.pilloryBreakoutTransitions.get(), c.guillotineEnabled.get(),
                c.guillotineActivationDelayTicks.get()));
        problems.addAll(validateCapitalPunishment(
                c.capitalPunishmentEnabled.get(), c.requiresExecutionDevice.get(),
                c.executionDelayTicks.get(), c.guardMayExecute.get(),
                c.guardKillingIsCapital.get(), c.npcOffendersEligible.get(),
                c.condemnedEscortTimeoutTicks.get(), c.executionSiteSearchRadius.get()));
        problems.addAll(validateEnchantments(
                c.allowedEnchantments.get(), c.imbueTransferPerLevel.get(),
                c.imbueMaxTransferFraction.get(), c.imbueMaxRecipients.get(),
                c.manaDrainPerTick.get(), c.enchantEffectDurationTicks.get(),
                c.enchantEffectAmplifier.get()));
        problems.addAll(validateCriminalJobs(
                c.enableThieves.get(),
                c.enableFences.get(),
                c.villageThiefChance.get(),
                c.villageFenceChance.get(),
                c.wildThiefChance.get(),
                c.minVillagePopulationForFence.get(),
                c.criminalAssignmentCooldownDays.get(),
                c.assignmentScanIntervalTicks.get(),
                c.staleRecordGraceDays.get()));
        problems.addAll(validateThief(
                c.thiefMugDurationTicks.get(),
                c.thiefMugCooldownTicks.get(),
                c.thiefScanIntervalTicks.get(),
                c.thiefTargetSearchRadius.get(),
                c.thiefGuardAvoidRadius.get(),
                c.thiefGuardHardAbortRadius.get(),
                c.thiefGuardRiskAbortThreshold.get(),
                c.npcMugHudUpdateIntervalTicks.get(),
                c.npcMugWeaponCheckIntervalTicks.get()));
        problems.addAll(validateTheft(
                c.thiefMinCurrencySteal.get(),
                c.thiefMaxCurrencySteal.get(),
                c.thiefStealAllIfBelowMinimum.get(),
                c.thiefProtectHotbar.get(),
                c.thiefProtectArmor.get(),
                c.thiefProtectOffhand.get(),
                c.thiefStolenGoodsPersistenceDays.get()));
        problems.addAll(validateFence(
                c.fenceMaxKarmaDiscount.get(),
                c.fenceMaxHeatMarkup.get(),
                c.fenceWantedMarkup.get(),
                c.fenceMinimumPriceMultiplier.get(),
                c.fenceMaximumPriceMultiplier.get(),
                c.fenceBuyPriceRatio.get(),
                c.fenceDefaultBasePrice.get(),
                c.fenceOfferCount.get(),
                c.fenceOfferMaxUses.get(),
                c.fenceRestockIntervalDays.get()));
        problems.addAll(validateCompliance(
                c.civilianCrimeReactionSpeedMultiplier.get(),
                c.freezeComplyingVictims.get(),
                c.armedVillagersCanResist.get(),
                c.complianceResistThreshold.get(),
                c.complianceHelpThreshold.get()));

        problems.addAll(validateBounty(
                c.bountyEnabled.get(),
                c.baseBounty.get(),
                c.minBounty.get(),
                c.maxBounty.get(),
                c.severityRewardScale.get(),
                c.fineRewardShare.get(),
                c.repeatOffenderBonus.get(),
                c.payForKills.get(),
                c.payForAliveCapture.get(),
                c.killMultiplier.get(),
                c.aliveCaptureMultiplier.get(),
                c.bountyKarmaReward.get(),
                c.claimRetentionDays.get(),
                c.bountyDeliveryRadius.get()));

        problems.addAll(validateBehaviour(
                c.enableObservations.get(),
                c.enableVillagerReactions.get(),
                c.enableDialogue.get(),
                c.witnessRadius.get(),
                c.hearingWitnessRadius.get(),
                c.reportRadius.get(),
                c.enableBail.get(),
                c.bailCostPerMinute.get(),
                c.enableNpcCrime.get(),
                c.enableRescue.get(),
                c.enableKidnappingNpc.get()));

        problems.addAll(validateFamilyLoyalty(
                c.enableFamilyLoyalty.get(),
                c.familyLoyaltyScope.get(),
                c.loyalPersonalities.get(),
                c.lawfulPersonalities.get(),
                c.loyaltyThreshold.get(),
                c.loyaltyHeartsWeight.get()));

        problems.addAll(validateAccomplices(
                c.enableAccomplices.get(),
                c.accompliceScope.get(),
                c.enableFamilyBail.get(),
                c.bailMin.get(),
                c.bailMax.get(),
                c.accompliceJailTicks.get()));

        problems.addAll(validateMugging(
                c.enableNpcMugging.get(),
                c.maxMuggingsPerPlayerPerDay.get(),
                c.playerMugProtectionTicks.get(),
                c.thiefMugCooldownTicks.get(),
                c.maxActiveThievesPerJurisdiction.get()));

        problems.addAll(validateContraband(
                c.enableContraband.get(),
                c.illegalItems.get(),
                c.contrabandDiscoveryMode.get(),
                c.contrabandSearchChance.get(),
                c.contrabandSearchIntervalTicks.get(),
                c.contrabandSearchRadius.get()));

        problems.addAll(validateMask(
                c.maskEnabled.get(),
                c.maskSuppressesHeat.get(),
                c.maskDefersHeat.get(),
                c.maskRemovalWitnessRadius.get(),
                c.maskedPursuitTicks.get(),
                c.guardsChallengeMaskWearers.get()));

        problems.addAll(validateSandBottle(
                c.enableSandBottles.get(),
                c.sandCooldownTicks.get(),
                c.sandDirectDurationTicks.get(),
                c.sandSplashDurationTicks.get(),
                c.sandRadius.get(),
                c.sandRecoveryTicks.get(),
                c.sandAffectsPlayers.get()));

        problems.addAll(validateWeapons(
                c.weaponWhitelist.get(),
                c.weaponBlacklist.get(),
                c.weaponGunKeywords.get(),
                c.weaponMods.get(),
                c.weaponAutoDetectMinAttackDamage.get()));

        registryCheck("protectedEntities", c.protectedEntities.get(), ForgeRegistries.ENTITY_TYPES,
                "an entity type", problems);
        registryCheck("responderEntities", c.responderEntities.get(), ForgeRegistries.ENTITY_TYPES,
                "an entity type", problems);
        registryCheck("weapons.whitelist", c.weaponWhitelist.get(), ForgeRegistries.ITEMS, "an item", problems);
        registryCheck("weapons.blacklist", c.weaponBlacklist.get(), ForgeRegistries.ITEMS, "an item", problems);
        currencyItemRegistryCheck(c.currencyItem.get(), problems);
        currencyIdRegistryCheck(c.currencyId.get(), problems);
        registryCheck("contraband.illegalItems", c.illegalItems.get(), ForgeRegistries.ITEMS, "an item",
                problems);

        // Jail / fine sanity (spec §6, §7, §12.3).
        if (c.jailableHeatThreshold.get() < c.wantedHeatThreshold.get()) {
            problems.add("jailableHeatThreshold (" + c.jailableHeatThreshold.get()
                    + ") should be at least wantedHeatThreshold (" + c.wantedHeatThreshold.get() + ").");
        }
        if (c.jailFallbackEnabled.get()) {
            if (ResourceLocation.tryParse(c.jailFallbackDim.get()) == null) {
                problems.add("jailFallbackDim is not a valid dimension id: '" + c.jailFallbackDim.get() + "'.");
            }
            if (c.jailFallbackPos.get().size() < 3) {
                problems.add("jailFallbackPos must list 3 coordinates [x, y, z].");
            }
        }

        // Ransom table sanity (spec §8.5, §12.3): catch a table that can never produce a real demand.
        if (c.ransomDemandTtlTicks.get() == 0) {
            problems.add("ransomDemandTtlTicks is 0 — open ransom demands would expire instantly.");
        }
        boolean allTiersZero = c.ransomSpouseMultiplier.get() == 0.0 && c.ransomParentMultiplier.get() == 0.0
                && c.ransomChildMultiplier.get() == 0.0 && c.ransomSiblingMultiplier.get() == 0.0
                && c.ransomRelativeMultiplier.get() == 0.0 && c.ransomVillageMultiplier.get() == 0.0;
        if (c.ransomBaseAmount.get() > 0 && allTiersZero) {
            problems.add("All ransom tier multipliers are 0 — every ransom would be free despite a non-zero base.");
        }
        if (c.muggingPurseInitialMax.get() > c.muggingPurseCapacity.get()) {
            problems.add("muggingPurseInitialMax cannot exceed muggingPurseCapacity.");
        }
        if (c.muggingBaseLoot.get() > c.muggingPurseCapacity.get()) {
            problems.add("muggingBaseLoot exceeds purse capacity; the configured excess can never transfer.");
        }
        if (c.maxUnlawfulCaptivesPerCaptor.get() < 1) {
            problems.add("maxUnlawfulCaptivesPerCaptor must be at least 1.");
        }

        problems.addAll(validateTownstead(
                TownsteadBridge.installed(),
                c.townsteadEnabled.get(),
                TownsteadDiagnostics.currentSwitches(),
                TownsteadBridge::has,
                c.townsteadSnapshotCacheTicks.get(),
                c.townsteadActivityLeaseTicks.get(),
                c.townsteadFacilitySearchRadius.get(),
                c.holdingCellSearchRadius.get()));
        problems.addAll(validateCommunityService(c.townsteadEnabled.get(),
                c.townsteadCommunityService.get(), c.enableFines.get()));

        // Surface crime-definition JSON parse errors from the last datapack load (spec §12.3).
        for (String crimeError : CrimeTypeRegistry.lastErrors()) {
            problems.add("Crime JSON: " + crimeError);
        }
        problems.addAll(townsteadDataProblems());
        return problems;
    }

    /**
     * What the three Townstead datapack loaders refused at the last reload.
     *
     * <p>Surfaced here as well as logged, and that is the point of the whole arrangement. Those loaders
     * publish a mapping whole or not at all, so the visible symptom of a bad file is that an old mapping
     * is still in force — which looks exactly like a pack that has not been reloaded yet. An operator
     * running {@code /crime validate} gets the file name and the bad id instead of a mystery.
     */
    public static List<String> townsteadDataProblems() {
        List<String> problems = new ArrayList<>();
        for (dev.otectus.mcacrime.compat.TownsteadDataProblem problem
                : dev.otectus.mcacrime.compat.TownsteadBuildingRoles.problems()) {
            problems.add("Townstead building role JSON: " + problem.describe());
        }
        for (dev.otectus.mcacrime.compat.TownsteadDataProblem problem
                : dev.otectus.mcacrime.compat.TownsteadPersonalityProfiles.problems()) {
            problems.add("Townstead personality profile JSON: " + problem.describe());
        }
        for (dev.otectus.mcacrime.compat.TownsteadDataProblem problem
                : dev.otectus.mcacrime.compat.TownsteadReactionBindings.problems()) {
            problems.add("Townstead reaction binding JSON: " + problem.describe());
        }
        return problems;
    }

    /**
     * The {@code [townstead]} section, as a pure function of its values and of what actually bound.
     *
     * <p>The rule this enforces is the one the whole integration is written around: <b>a switch that is
     * on while the capability behind it is missing must be reported as degraded, never left to read as
     * off.</b> "Off" is a decision the operator made; "degraded" is a fact about the installed mods, and
     * conflating them is how somebody ends up believing a protection is in force when nothing is doing
     * it.
     *
     * <p>Nothing is said about capabilities when Townstead is absent. That is the ordinary state of most
     * installs, and filling {@code /crime validate} with a dozen lines about a mod that is not there
     * would bury the problems that matter.
     *
     * @param installed whether Townstead is present at all
     * @param enabled the master switch
     * @param switches every {@code [townstead]} switch by config name, from
     *                 {@link TownsteadDiagnostics#currentSwitches()}
     * @param available whether one capability bound, normally {@code TownsteadBridge::has}
     */
    public static List<String> validateTownstead(boolean installed, boolean enabled,
                                                 Map<String, Boolean> switches,
                                                 Predicate<TownsteadCapability> available,
                                                 int snapshotCacheTicks, int activityLeaseTicks,
                                                 int facilitySearchRadius, int holdingCellSearchRadius) {
        List<String> problems = new ArrayList<>();

        if (facilitySearchRadius < 1) {
            problems.add("townstead.facilitySearchRadius (" + facilitySearchRadius + ") must be at least 1.");
        }
        if (facilitySearchRadius > 0 && holdingCellSearchRadius > 0
                && facilitySearchRadius < holdingCellSearchRadius) {
            // Not fatal, but it inverts the ladder the operator thinks they configured: an assigned cell
            // further away than the radius is skipped and a temporary cage is dug instead, next to the
            // jail somebody built.
            problems.add("townstead.facilitySearchRadius (" + facilitySearchRadius + ") is smaller than "
                    + "holdingCellSearchRadius (" + holdingCellSearchRadius + "), so an arrest can build a "
                    + "temporary cell closer than an assigned facility it refused to consider.");
        }

        if (snapshotCacheTicks < 1) {
            problems.add("townstead.snapshotCacheTicks (" + snapshotCacheTicks + ") must be at least 1.");
        }
        if (activityLeaseTicks < 1) {
            problems.add("townstead.activityLeaseTicks (" + activityLeaseTicks + ") must be at least 1.");
        }
        if (activityLeaseTicks > 0 && snapshotCacheTicks > 0 && activityLeaseTicks < snapshotCacheTicks) {
            problems.add("townstead.activityLeaseTicks (" + activityLeaseTicks + ") is shorter than "
                    + "snapshotCacheTicks (" + snapshotCacheTicks + "), so an enforcement claim can expire "
                    + "while the snapshot it was made from is still being reused.");
        }

        if (!installed) {
            return problems; // absent is the normal case and is never reported as a problem
        }

        long on = switches.values().stream().filter(Boolean.TRUE::equals).count();
        if (!enabled) {
            if (on > 0) {
                problems.add("townstead.enabled is false while " + on + " [townstead] setting(s) are on; "
                        + "none of them does anything until enabled is true.");
            }
            return problems;
        }

        // A dependency between two switches rather than between a switch and a capability, which is why
        // it is not in the requirement table. Automatic protection writes property policies, and a
        // policy does nothing whatsoever while property law is off -- so this combination silently
        // fills a world's property table with claims nothing evaluates, and an operator reading
        // "autoProtectGeneratedProperty = true" would reasonably believe their stores were protected.
        if (Boolean.TRUE.equals(switches.get("autoProtectGeneratedProperty"))
                && !Boolean.TRUE.equals(switches.get("propertyLaw"))) {
            problems.add("townstead.autoProtectGeneratedProperty is on while townstead.propertyLaw is "
                    + "off. Automatic protection only writes property policies, and nothing evaluates a "
                    + "policy until property law is on, so no container is protected and no taking is a "
                    + "crime. Turn propertyLaw on, or turn this off.");
        }

        for (TownsteadDiagnostics.SwitchRequirement requirement : TownsteadDiagnostics.REQUIREMENTS) {
            boolean switchedOn = Boolean.TRUE.equals(switches.get(requirement.setting()));
            if (TownsteadDiagnostics.stateOf(requirement, switchedOn, available)
                    != TownsteadDiagnostics.FeatureState.DEGRADED) {
                continue;
            }
            List<String> missing = new ArrayList<>();
            for (TownsteadCapability capability : requirement.capabilities()) {
                if (!available.test(capability)) {
                    missing.add(capability.id());
                }
            }
            problems.add("townstead." + requirement.setting() + " is on but the installed Townstead does "
                    + "not provide " + String.join(", ", missing) + "; the feature is DEGRADED (not running), "
                    + "not off. " + requirement.summary() + " — until then MCA: Crime uses its own "
                    + "behaviour unchanged.");
        }
        return problems;
    }

    /**
     * The one cross-section dependency community service has, and it is not a Townstead capability.
     *
     * <p>Civic work is offered exactly where a fine could have been paid, so with
     * {@code jail.enableFines} off there is never anything for it to be an alternative to: {@code SettlementPolicy} refuses to
     * quote, every offer is declined, and an operator reading {@code communityService = true} would
     * reasonably believe their players had a way to work off a case. Kept out of the capability table
     * on purpose -- that table answers "what does the installed Townstead provide", and this is a
     * question about MCA: Crime's own settings.
     *
     * <p>Pure, so it can be asserted without a loaded config.
     */
    public static List<String> validateCommunityService(boolean townsteadEnabled,
                                                        boolean communityService, boolean finesEnabled) {
        List<String> problems = new ArrayList<>();
        if (townsteadEnabled && communityService && !finesEnabled) {
            problems.add("townstead.communityService is on while jail.enableFines is off. Civic work "
                    + "is offered only where a fine could have been paid, so with fines disabled there "
                    + "is nothing for it to replace and no contract will ever be offered.");
        }
        return problems;
    }

    /**
     * The one registry check that is a single value rather than a list.
     *
     * <p>Worth reporting separately because the failure is silent in play: an item id from a mod that
     * is no longer installed resolves to nothing, {@code ItemCurrency} keeps paying in emeralds, and
     * the only other evidence is one line in a log nobody reads after the server started fine.
     */
    /**
     * Whether {@code integrations.currencyId} names a currency somebody actually registered. Only
     * answerable once every mod has loaded, which is why it lives here and not in
     * {@link #validateCurrency}: an economy mod's id is unknowable before then. Mirrors the runtime
     * fallback in {@code Currencies.reload()} so the operator sees the same fact in {@code /crime validate}.
     */
    private static void currencyIdRegistryCheck(String currencyId, List<String> problems) {
        ResourceLocation id = currencyId == null ? null : ResourceLocation.tryParse(currencyId.trim());
        if (id == null) {
            return; // already reported by validateCurrency
        }
        try {
            if (dev.otectus.mcacrime.economy.Currencies.byId(id).isEmpty()) {
                problems.add("integrations.currencyId '" + currencyId + "' is not a registered currency "
                        + "(economy mod absent or typo); fines, bail, ransom and theft fall back to "
                        + "'mcacrime:emerald' until it is fixed.");
            }
        } catch (RuntimeException registryUnavailable) {
            // Registries not up yet: the runtime reload reports the same fallback with one warning.
        }
    }

    private static void currencyItemRegistryCheck(String currencyItem, List<String> problems) {
        if (currencyItem == null || currencyItem.isBlank()) {
            return; // already reported by validateCurrency
        }
        try {
            ResourceLocation rl = ResourceLocation.tryParse(currencyItem.trim());
            if (rl != null && !ForgeRegistries.ITEMS.containsKey(rl)) {
                problems.add("integrations.currencyItem '" + currencyItem + "' is not a registered item "
                        + "(mod absent or typo); mcacrime:item will pay in emeralds until it is fixed.");
            }
        } catch (Throwable ignored) {
            // Registries unavailable (e.g. very early load) — skip the existence check silently.
        }
    }

    private static void registryCheck(String listName, List<? extends String> ids,
                                      IForgeRegistry<?> registry, String noun, List<String> problems) {
        for (String id : ids) {
            if (id == null || id.isBlank() || id.startsWith("#") || id.indexOf('*') >= 0) {
                continue; // blanks/tags/wildcards handled (or skipped) by the parse pass
            }
            try {
                ResourceLocation rl = ResourceLocation.tryParse(id);
                if (rl != null && !registry.containsKey(rl)) {
                    problems.add(listName + " references " + noun + " that is not registered: '" + id + "'.");
                }
            } catch (Throwable ignored) {
                // Registries unavailable (e.g. very early load) — skip the existence check silently.
            }
        }
    }
}
