package dev.otectus.mcacrime.audio;

import dev.otectus.mcacrime.McaCrime;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import org.jetbrains.annotations.Nullable;
import java.util.Set;

/**
 * The mod's own sound ids, and the indirection that lets them exist before their audio does (§3.11).
 *
 * <p>Every id here is registered and stable. None of them has an {@code .ogg} file yet: the upstream
 * audio is unattributed and is not carried over (see {@code docs/0.7.5/PROVENANCE.md}), so each
 * moment is played through {@link #resolve} which answers with a named <em>vanilla</em>
 * {@link SoundEvent} until MCA: Crime ships its own file for that id. When one lands, its path joins
 * {@link #SHIPPED} and the sound changes with no other code change — which is the whole reason the
 * ids are registered now rather than when the audio arrives.
 *
 * <p>This is not a contradiction of {@code CrimeSounds}' "vanilla sounds are the rule" javadoc: that
 * rule still governs law and social moments. Device and restraint moments get named events, because
 * they are the ones a server may want to reskin in a resource pack, and a resource pack can only
 * reach an id that exists.
 */
public final class CrimeSoundEvents {

    public static final DeferredRegister<SoundEvent> SOUNDS =
            DeferredRegister.create(Registries.SOUND_EVENT, McaCrime.MOD_ID);

    /**
     * The sound paths whose {@code .ogg} file and {@code sounds.json} entry actually ship.
     *
     * <p>Empty, and deliberately so. {@link #resolve} reads it rather than assuming: a registered id
     * with no file behind it plays nothing at all in vanilla, which is a silent failure, and silence
     * is exactly the wrong outcome for a sound whose job is to tell a player that the cuffs went on.
     */
    private static final Set<String> SHIPPED = Set.of();

    public static final DeferredHolder<SoundEvent, SoundEvent> RESTRAINT_APPLY_HANDCUFFS =
            register("restraint.apply_handcuffs");
    public static final DeferredHolder<SoundEvent, SoundEvent> RESTRAINT_APPLY_SHACKLES =
            register("restraint.apply_shackles");
    public static final DeferredHolder<SoundEvent, SoundEvent> PILLORY_USE = register("block.pillory.use");
    public static final DeferredHolder<SoundEvent, SoundEvent> GUILLOTINE_USE =
            register("block.guillotine.use");
    /** Arming a guillotine for an authorised execution (§3.19, M4.10). Original audio when it lands. */
    public static final DeferredHolder<SoundEvent, SoundEvent> GUILLOTINE_ARM =
            register("block.guillotine.arm");
    public static final DeferredHolder<SoundEvent, SoundEvent> SAFE_OPEN = register("block.safe.open");
    public static final DeferredHolder<SoundEvent, SoundEvent> SAFE_CLOSE = register("block.safe.close");

    private CrimeSoundEvents() {
    }

    private static DeferredHolder<SoundEvent, SoundEvent> register(String path) {
        return SOUNDS.register(path, () -> SoundEvent.createVariableRangeEvent(McaCrime.id(path)));
    }

    public static void register(IEventBus modBus) {
        SOUNDS.register(modBus);
    }

    /** Whether this mod ships an audio file for {@code path}. */
    public static boolean shipped(@Nullable String path) {
        return path != null && SHIPPED.contains(path);
    }

    /**
     * Our sound for {@code path} when its file ships, and {@code fallback} when it does not.
     *
     * <p>Null-tolerant on the registry side on purpose: this is called from gameplay code, and a
     * registry that is not ready yet must degrade to the vanilla sound rather than throw in the middle
     * of an arrest.
     */
    public static SoundEvent resolve(String path, @Nullable DeferredHolder<SoundEvent, SoundEvent> ours,
                                     SoundEvent fallback) {
        if (!shipped(path) || ours == null) {
            return fallback;
        }
        try {
            SoundEvent resolved = ours.isBound() ? ours.get() : null;
            return resolved == null ? fallback : resolved;
        } catch (RuntimeException notReady) {
            return fallback;
        }
    }
}
