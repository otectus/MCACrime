package dev.otectus.mcacrime.audio;

import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import org.jetbrains.annotations.Nullable;

/**
 * The mod's audio vocabulary, as named moments rather than sound ids.
 *
 * <p>Every sound here is a <b>vanilla</b> one, and that is a deliberate choice rather than a
 * placeholder. Shipping custom {@code .ogg} files would mean a sound the player has never heard before
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

    /** Plays to one player only, for feedback nobody else has any business hearing. */
    public static void toPlayer(@Nullable ServerPlayer player, SoundEvent sound, float volume, float pitch) {
        if (player == null) {
            return;
        }
        player.playNotifySound(sound, SoundSource.NEUTRAL, volume, pitch);
    }
}
