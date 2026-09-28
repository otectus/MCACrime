package dev.otectus.mcacrime.audio;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import javax.annotation.Nullable;

/**
 * The mod's audio vocabulary, as named moments rather than sound ids.
 *
 * <p>Law and social moments are played with <b>vanilla</b> sounds, and that is a deliberate choice
 * rather than a placeholder. Shipping custom {@code .ogg} files would mean a sound the player has never heard before
 * carrying information they need immediately — that the cuffs went on, that a guard is challenging
 * them, that the mugging failed. Vanilla sounds arrive pre-learned: a player already knows what a
 * chain being placed means, and what an iron door closing behind them means. The mapping below is
 * chosen so each moment sounds like the thing it is.
 *
 * <p>Everything is server-side and positional, so the sound reaches whoever is close enough to have
 * heard it happen — which is the same rule the hearing-witness radius uses, and for the same reason.
 * The two are not wired to each other, but they should stay consistent: a player who hears a struggle
 * should be able to assume a villager nearby heard it too.
 *
 * <p><b>Device and restraint moments are different</b> (§3.11). Those carry registered MCA: Crime
 * sound ids, so a resource pack can reach them, and each one resolves through
 * {@link CrimeSoundEvents#resolve} to a named vanilla sound for as long as this mod ships no audio
 * file of its own. The id is what is stable; the file behind it is what may arrive later.
 *
 * <p>Every method is a no-op on a null level or entity. Audio is feedback; it must never be the thing
 * that throws.
 */
public final class CrimeSounds {

    private CrimeSounds() {
    }

    // --- coercion ---------------------------------------------------------

    /** A mugging channel opens. Low, tense, and audible to bystanders. */
    public static void mugStart(@Nullable Entity at) {
        play(at, SoundEvents.VILLAGER_HURT, 0.7F, 0.8F);
    }

    /** The purse changes hands. */
    public static void mugSuccess(@Nullable Entity at) {
        play(at, SoundEvents.ITEM_PICKUP, 1.0F, 0.8F);
    }

    /** There was nothing to take. Deliberately a flat, disappointed note. */
    public static void mugEmpty(@Nullable Entity at) {
        play(at, SoundEvents.VILLAGER_NO, 0.8F, 1.0F);
    }

    /** The victim fought back or the channel broke. */
    public static void resisted(@Nullable Entity at) {
        play(at, SoundEvents.VILLAGER_NO, 1.0F, 0.7F);
    }

    // --- restraint and custody -------------------------------------------

    /** Restraints go on. The chain sound is the one players already read as "bound". */
    public static void restrainApplied(@Nullable Entity at) {
        play(at, SoundEvents.CHAIN_PLACE, 1.0F, 0.9F);
    }

    /**
     * Restraints go on, with the definition's own sound id.
     *
     * <p>{@code mcacrime:restraint.apply_handcuffs} and {@code restraint.apply_shackles} are the two
     * ids the definitions table already names. Each plays our registered event when its file ships
     * and a distinct vanilla sound when it does not — distinct, because the two ids exist so that
     * shackles and handcuffs do not sound the same, and a shared fallback would quietly undo that.
     *
     * @param applySound the definition's {@code applySound} id, or null for the generic sound
     */
    public static void restrainApplied(@Nullable Entity at,
                                       @Nullable net.minecraft.resources.ResourceLocation applySound) {
        if (applySound == null) {
            restrainApplied(at);
            return;
        }
        String path = applySound.getPath();
        switch (path) {
            case "restraint.apply_handcuffs" -> play(at, CrimeSoundEvents.resolve(path,
                    CrimeSoundEvents.RESTRAINT_APPLY_HANDCUFFS, SoundEvents.CHAIN_PLACE), 1.0F, 0.9F);
            case "restraint.apply_shackles" -> play(at, CrimeSoundEvents.resolve(path,
                    CrimeSoundEvents.RESTRAINT_APPLY_SHACKLES, SoundEvents.IRON_TRAPDOOR_CLOSE),
                    1.0F, 1.1F);
            default -> restrainApplied(at);
        }
    }

    // --- locks and devices -------------------------------------------------

    /** A safe's door swings open. */
    public static void safeOpened(@Nullable net.minecraft.world.level.Level level,
                                  @Nullable net.minecraft.core.BlockPos pos) {
        playAt(level, pos, CrimeSoundEvents.resolve("block.safe.open", CrimeSoundEvents.SAFE_OPEN,
                SoundEvents.IRON_DOOR_OPEN), 0.5F, 1.0F);
    }

    /** And shuts again. */
    public static void safeClosed(@Nullable net.minecraft.world.level.Level level,
                                  @Nullable net.minecraft.core.BlockPos pos) {
        playAt(level, pos, CrimeSoundEvents.resolve("block.safe.close", CrimeSoundEvents.SAFE_CLOSE,
                SoundEvents.IRON_DOOR_CLOSE), 0.5F, 1.0F);
    }

    /** A pillory's boards clack shut, or swing open. */
    public static void pilloryUsed(@Nullable net.minecraft.world.level.Level level,
                                   @Nullable net.minecraft.core.BlockPos pos, boolean closing) {
        playAt(level, pos, CrimeSoundEvents.resolve("block.pillory.use", CrimeSoundEvents.PILLORY_USE,
                closing ? SoundEvents.WOODEN_TRAPDOOR_CLOSE : SoundEvents.WOODEN_TRAPDOOR_OPEN),
                1.0F, closing ? 0.8F : 1.0F);
    }

    /** A guillotine's blade drops, or is winched back up. */
    public static void guillotineUsed(@Nullable net.minecraft.world.level.Level level,
                                      @Nullable net.minecraft.core.BlockPos pos, boolean dropping) {
        playAt(level, pos, CrimeSoundEvents.resolve("block.guillotine.use",
                CrimeSoundEvents.GUILLOTINE_USE,
                dropping ? SoundEvents.IRON_TRAPDOOR_CLOSE : SoundEvents.CHAIN_PLACE),
                1.0F, dropping ? 0.7F : 1.0F);
    }

    /** A guillotine is armed under a live execution order. Distinct from merely dropping the blade. */
    public static void guillotineArmed(@Nullable net.minecraft.world.level.Level level,
                                       @Nullable net.minecraft.core.BlockPos pos) {
        playAt(level, pos, CrimeSoundEvents.resolve("block.guillotine.arm",
                CrimeSoundEvents.GUILLOTINE_ARM, SoundEvents.BELL_RESONATE), 1.0F, 0.9F);
    }

    /** A detention device is broken open from the inside. */
    public static void detentionBroken(@Nullable net.minecraft.world.level.Level level,
                                       @Nullable net.minecraft.core.BlockPos pos) {
        playAt(level, pos, SoundEvents.ITEM_BREAK, 1.0F, 0.9F);
    }

    /** A chain goes on, or comes off. */
    public static void chainAttached(@Nullable Entity at, boolean attaching) {
        play(at, attaching ? SoundEvents.CHAIN_PLACE : SoundEvents.CHAIN_BREAK, 1.0F,
                attaching ? 0.9F : 1.1F);
    }

    /** A lock turns, either way. Not a device id: this is the ordinary key-in-lock moment. */
    public static void lockToggled(@Nullable net.minecraft.world.level.Level level,
                                   @Nullable net.minecraft.core.BlockPos pos, boolean locked) {
        playAt(level, pos, locked ? SoundEvents.CHAIN_PLACE : SoundEvents.CHAIN_BREAK, 0.8F,
                locked ? 0.8F : 1.1F);
    }

    /** A key is cut to a lock, or a padlock accepts one. */
    public static void keyBound(@Nullable Entity at) {
        play(at, SoundEvents.CHAIN_FALL, 1.0F, 1.0F);
    }

    /** A lockpick finds the pin. Short, quiet, and only the picker's business. */
    public static void lockpickProgress(@Nullable ServerPlayer player) {
        toPlayer(player, SoundEvents.ITEM_PICKUP, 0.4F, 1.6F);
    }

    /** A lockpick snaps. */
    public static void lockpickBroke(@Nullable Entity at) {
        play(at, SoundEvents.ITEM_BREAK, 0.8F, 1.3F);
    }

    /** A lock gives way to a pick. */
    public static void lockPicked(@Nullable net.minecraft.world.level.Level level,
                                  @Nullable net.minecraft.core.BlockPos pos) {
        playAt(level, pos, SoundEvents.CHAIN_BREAK, 1.0F, 1.2F);
    }

    /** Restraints come off, or a captive is released. */
    public static void restraintRemoved(@Nullable Entity at) {
        play(at, SoundEvents.CHAIN_BREAK, 1.0F, 1.1F);
    }

    /** Escape work finally breaks the restraint. Higher and brighter than removal. */
    public static void restraintBroken(@Nullable Entity at) {
        play(at, SoundEvents.CHAIN_BREAK, 1.0F, 1.4F);
    }

    /** Somebody else cut a captive free. */
    public static void rescued(@Nullable Entity at) {
        play(at, SoundEvents.LEASH_KNOT_BREAK, 1.0F, 1.2F);
    }

    // --- law --------------------------------------------------------------

    /** A guard challenges. Sharp, and unmistakably aimed at you. */
    public static void guardChallenge(@Nullable Entity at) {
        play(at, SoundEvents.VILLAGER_NO, 1.2F, 0.6F);
    }

    /** The challenge is over and the guard is standing down. */
    public static void standDown(@Nullable Entity at) {
        play(at, SoundEvents.VILLAGER_TRADE, 0.8F, 1.0F);
    }

    /** A cell door closing. The one sound in the mod that should feel final. */
    public static void jailed(@Nullable Entity at) {
        play(at, SoundEvents.IRON_DOOR_CLOSE, 1.0F, 0.8F);
    }

    /** Released from jail: the same door, opening. */
    public static void released(@Nullable Entity at) {
        play(at, SoundEvents.IRON_DOOR_OPEN, 1.0F, 1.0F);
    }

    /** A fine, bail, or ransom is paid. */
    public static void paid(@Nullable Entity at) {
        play(at, SoundEvents.EXPERIENCE_ORB_PICKUP, 1.0F, 1.2F);
    }

    /** A report reaches a guard. Quiet — the player should notice it, not be startled by it. */
    public static void reportFiled(@Nullable Entity at) {
        play(at, SoundEvents.VILLAGER_CELEBRATE, 0.4F, 0.7F);
    }

    /** Restorative: an apology lands, restitution is accepted. */
    public static void amends(@Nullable Entity at) {
        play(at, SoundEvents.VILLAGER_CELEBRATE, 0.7F, 1.1F);
    }

    // --- private ----------------------------------------------------------

    /**
     * Plays positionally on the server so everyone in range hears it, including the actor. Passing a
     * null player is what broadcasts it — handing the acting player here would exclude them from their
     * own sound, since vanilla treats that argument as "the client that already played this locally".
     */
    private static void play(@Nullable Entity at, SoundEvent sound, float volume, float pitch) {
        if (at == null || !(at.level() instanceof ServerLevel level)) {
            return;
        }
        level.playSound(null, at.getX(), at.getY(), at.getZ(), sound, SoundSource.NEUTRAL, volume, pitch);
    }

    /**
     * Plays positionally at a block. The block moments have no entity to hang off.
     *
     * <p>{@link SoundSource#BLOCKS} rather than {@code NEUTRAL}: a door and a safe belong to the
     * category a player's own volume slider already covers for doors and safes.
     */
    private static void playAt(@Nullable net.minecraft.world.level.Level level,
                               @Nullable net.minecraft.core.BlockPos pos, SoundEvent sound,
                               float volume, float pitch) {
        if (!(level instanceof ServerLevel server) || pos == null) {
            return;
        }
        server.playSound(null, pos, sound, SoundSource.BLOCKS, volume, pitch);
    }

    /** Plays to one player only, for feedback nobody else has any business hearing. */
    public static void toPlayer(@Nullable ServerPlayer player, SoundEvent sound, float volume, float pitch) {
        if (player == null) {
            return;
        }
        player.playNotifySound(sound, SoundSource.NEUTRAL, volume, pitch);
    }
}
