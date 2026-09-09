package dev.otectus.mcacrime.dialogue;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.ai.ReactionFactors;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.crime.Band;
import dev.otectus.mcacrime.engine.CrimeState;
import dev.otectus.mcacrime.memory.OffenderMemory;
import dev.otectus.mcacrime.memory.VillagerCrimeProfile;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Picks and delivers villager lines (spec §16).
 *
 * <p>Three invariants shape this class, all from §16.1. The server chooses the <em>key</em> and the
 * client renders it, so lines are localisable and no text is ever sent over the wire. Nothing
 * downstream branches on what was said, so a datapack rewriting every line cannot change an outcome.
 * And selection is deterministic per encounter, so reopening a conversation repeats the line instead
 * of rerolling until the player sees one they like.
 *
 * <p>The cooldown is per speaker-listener pair rather than global. A village reacting to a murder
 * should produce several villagers each saying something once, not one villager talking and the rest
 * silenced by a shared timer.
 */
public final class CrimeDialogueService {

    /** event id -> definition. Swapped atomically on datapack reload. */
    private static volatile Map<ResourceLocation, CrimeDialogueDefinition> definitions = Map.of();
    /** Errors from the last load, surfaced by {@code /crime validate}. */
    private static volatile List<String> loadErrors = List.of();
    /** "speaker:listener" -> game time the pair may speak again. */
    private static final Map<String, Long> COOLDOWNS = new ConcurrentHashMap<>();
    /** Ceiling on remembered cooldown pairs, so a long session cannot grow the map without bound. */
    private static final int MAX_COOLDOWNS = 4096;

    private CrimeDialogueService() {
    }

    static void replaceAll(Map<ResourceLocation, CrimeDialogueDefinition> loaded, List<String> errors) {
        definitions = Map.copyOf(loaded);
        loadErrors = List.copyOf(errors);
        COOLDOWNS.clear();
    }

    public static List<String> loadErrors() {
        return loadErrors;
    }

    public static int definitionCount() {
        return definitions.size();
    }

    /** Whether a shipped or pack-supplied definition exists for this event. */
    public static boolean has(ResourceLocation event) {
        return definitions.containsKey(event);
    }

    /**
     * The translation key for this event and context. Falls back to
     * {@code dialogue.mcacrime.<path>.generic} when no definition is loaded at all, so an event that a
     * pack removed entirely still produces a renderable line rather than an empty bubble.
     */
    public static String line(ResourceLocation event, DialogueContext context) {
        CrimeDialogueDefinition definition = definitions.get(event);
        if (definition == null) {
            return "dialogue.mcacrime." + event.getPath() + ".generic";
        }
        return definition.select(context);
    }

    /**
     * Says a line to one player, respecting the per-pair cooldown.
     *
     * @return false when dialogue is disabled or the pair is still on cooldown, so a caller that wants
     *         to fall back to a plain system message can tell that nothing was said
     */
    public static boolean speak(@Nullable LivingEntity speaker, ServerPlayer listener,
                                ResourceLocation event, DialogueContext context, Object... args) {
        if (!McaCrimeConfig.COMMON.enableDialogue.get() || listener == null
                || speaker != null && !dev.otectus.mcacrime.ai.NpcAwareness.isAwake(speaker)) {
            return false;
        }
        long now = listener.level().getGameTime();
        UUID speakerId = speaker == null ? listener.getUUID() : speaker.getUUID();
        String pair = speakerId + ":" + listener.getUUID();
        Long until = COOLDOWNS.get(pair);
        if (until != null && now < until) {
            return false;
        }
        rememberCooldown(pair, now + McaCrimeConfig.COMMON.dialogueCooldownTicks.get());

        Component name = speaker == null ? Component.empty() : McaCompat.getVillagerDisplayName(speaker);
        Component text = Component.translatable(line(event, context), args);
        listener.sendSystemMessage(Component.translatable("mcacrime.dialogue.line", name, text));
        return true;
    }

    /** Drops every cooldown involving this entity. Called on death and on release. */
    public static void forget(UUID entity) {
        if (entity == null) {
            return;
        }
        String prefix = entity + ":";
        String suffix = ":" + entity;
        COOLDOWNS.keySet().removeIf(key -> key.startsWith(prefix) || key.endsWith(suffix));
    }

    private static void rememberCooldown(String pair, long until) {
        if (COOLDOWNS.size() >= MAX_COOLDOWNS) {
            // Cheap bound: a full map is dropped wholesale rather than LRU-tracked. The only cost is
            // that a few villagers may speak one line sooner than their cooldown intended.
            COOLDOWNS.clear();
        }
        COOLDOWNS.put(pair, until);
    }

    /**
     * Builds the standard context for a villager reacting to a player. Every caller that has both
     * entities should use this rather than assembling facts itself, so one villager describes itself
     * the same way to every event.
     *
     * @param encounterId the identity that makes the choice deterministic — an action session, an
     *                    incident, or a custody record. Never a random value.
     */
    public static DialogueContext context(ServerLevel level, LivingEntity villager, ServerPlayer player,
                                          UUID encounterId, ResourceLocation event) {
        long seed = encounterId == null
                ? event.hashCode()
                : encounterId.getMostSignificantBits() ^ event.hashCode();
        DialogueContext.Builder builder = DialogueContext.builder(seed);

        int hearts = McaCompat.getHearts(player, villager);
        boolean responder = McaCompat.isGuard(villager);
        boolean adult = McaCompat.isAdult(villager);

        OffenderMemory memory = null;
        VillagerCrimeProfile profile = level.getServer() == null ? null
                : CrimeWorldData.get(level.getServer()).villagerProfile(villager.getUUID()).orElse(null);
        if (profile != null) {
            memory = profile.offenderMemories().get(player.getUUID());
        }
        int encounters = memory == null ? 0 : memory.attempts();

        ReactionFactors factors = ReactionFactors.of(villager.getUUID(), hearts, responder, adult, encounters, false);
        builder.put("personality", factors.bravery() >= 0.66F ? "bold"
                : factors.bravery() <= 0.33F ? "cautious" : "neutral");
        builder.putBand("bravery", factors.bravery());
        builder.putBand("greed", factors.greed());
        builder.put("relationship", relationship(villager, player, hearts));
        builder.put("role", responder ? "guard"
                : McaCompat.getProfessionId(villager).map(ResourceLocation::getPath).orElse("none"));
        builder.put("adult", adult);
        builder.put("repeat", encounters > 0);
        builder.put("encounters", encounters == 0 ? "none" : encounters == 1 ? "one" : "many");
        builder.put("band", CrimeState.getBand(player).name());
        builder.put("wanted", CrimeState.isWanted(player));
        builder.put("night", !level.isDay());
        var crimeMemories = dev.otectus.mcacrime.memory.VictimMemoryService.memories(level.getServer(), villager.getUUID(), player.getUUID());
        builder.put("crime_victim", crimeMemories.stream().anyMatch(m -> !m.indirect()));
        builder.put("crime_family", crimeMemories.stream().anyMatch(m -> m.category().equals("FAMILY_HARM")));
        builder.put("crime_witness", crimeMemories.stream().anyMatch(m -> m.category().equals("WITNESSED")));
        builder.put("crime_restitution", crimeMemories.stream().anyMatch(m -> m.restitutionPaid()));
        builder.putBand("crime_fear", (float) crimeMemories.stream().mapToDouble(m -> m.fear()).max().orElse(0));
        builder.putBand("crime_anger", (float) crimeMemories.stream().mapToDouble(m -> m.anger()).max().orElse(0));
        if (profile != null) {
            long balance = profile.purse().balance();
            builder.put("purse", balance <= 0 ? "empty" : balance <= 4 ? "low" : "rich");
        }
        return builder.build();
    }

    /**
     * Normalises the actor's standing with this villager into one of four tiers. Family is read from
     * MCA's relationship graph when it resolved; otherwise hearts alone decide, which is a weaker
     * answer but never a wrong-looking one.
     */
    private static String relationship(LivingEntity villager, ServerPlayer player, int hearts) {
        if (McaCompat.isRelationshipApiAvailable()) {
            UUID playerId = player.getUUID();
            if (McaCompat.getSpouseUuid(villager).filter(playerId::equals).isPresent()
                    || McaCompat.getParentUuids(villager).contains(playerId)
                    || McaCompat.getChildUuids(villager).contains(playerId)
                    || McaCompat.getSiblingUuids(villager).contains(playerId)) {
                return "family";
            }
        }
        if (hearts >= 50) {
            return "friend";
        }
        return hearts <= -25 ? "enemy" : "stranger";
    }

    /** Convenience for the common band fact when only the band is known. */
    public static String bandFact(Band band) {
        return band == null ? "grey" : band.name().toLowerCase(java.util.Locale.ROOT);
    }
}
