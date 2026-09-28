package dev.otectus.mcacrime.locks;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.otectus.mcacrime.state.CrimeDataComponents;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

import org.jetbrains.annotations.Nullable;
import java.util.Optional;
import java.util.UUID;

/**
 * What a key stack knows about the lock it was cut for (§3.7).
 *
 * <p>Three fields, and the middle one is the whole point. Upstream's key carries a bare {@code UUID},
 * so a "reset" that wants to invalidate old copies has no way to say so except by rerolling the
 * identity — which orphans the block, the padlock entity and every audit reference at once. Carrying
 * the binding revision with the id means a rekey invalidates every copy while the lock stays the same
 * lock.
 *
 * <p>The name is presentation: a key copied from a named key keeps the name so a ring of eight keys
 * is usable. It is never compared and never trusted for access.
 *
 * <p>Comparison is {@link UUID#equals}, everywhere, deliberately: upstream's ring index compares its
 * UUIDs with {@code ==} ({@code items/KeyRingItem.java:183}) and therefore essentially never finds a
 * key it is holding.
 *
 * <p>1.21.1 note: the Forge baseline keeps this in item NBT. There is no item NBT here, so the
 * binding is a registered {@link net.minecraft.core.component.DataComponentType} —
 * {@code CrimeDataComponents.LOCK_BINDING} — and this record carries the {@link #CODEC} and
 * {@link #STREAM_CODEC} that type is built from. The stack accessors below are the only readers and
 * writers, so "where a key's binding lives" is still stated exactly once.
 *
 * @param lockId          the lock identity this key opens
 * @param bindingRevision the lock's binding revision when the key was cut
 * @param name            the display name carried through copying, or null
 */
public record KeyBinding(UUID lockId, long bindingRevision, @Nullable String name) {

    public static final Codec<KeyBinding> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            UUIDUtil.STRING_CODEC.fieldOf("lock_id").forGetter(KeyBinding::lockId),
            Codec.LONG.fieldOf("binding_revision").forGetter(KeyBinding::bindingRevision),
            Codec.STRING.optionalFieldOf("name").forGetter(KeyBinding::displayName))
            .apply(instance, (id, revision, name) -> new KeyBinding(id, revision, name.orElse(null))));

    public static final StreamCodec<ByteBuf, KeyBinding> STREAM_CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, KeyBinding::lockId,
            ByteBufCodecs.VAR_LONG, KeyBinding::bindingRevision,
            ByteBufCodecs.optional(ByteBufCodecs.STRING_UTF8), KeyBinding::displayName,
            (id, revision, name) -> new KeyBinding(id, revision, name.orElse(null)));

    public KeyBinding {
        bindingRevision = Math.max(1L, bindingRevision);
        name = name == null || name.isBlank() ? null : name;
    }

    public static KeyBinding of(UUID lockId, long bindingRevision) {
        return new KeyBinding(lockId, bindingRevision, null);
    }

    /** A key cut for {@code lock} right now. */
    public static KeyBinding forLock(LockRecord lock, @Nullable String name) {
        return new KeyBinding(lock.lockId(), lock.bindingRevision(), name);
    }

    public Optional<String> displayName() {
        return Optional.ofNullable(name);
    }

    /**
     * Whether this key still opens {@code lock}.
     *
     * <p>Identity <em>and</em> revision. A key for another lock is a wrong key; a key for this lock at
     * an older revision is a key somebody has been locked out of, and the two are different answers a
     * player deserves to be told apart.
     */
    public boolean opens(@Nullable LockRecord lock) {
        return lock != null && lock.lockId().equals(lockId) && lock.accepts(bindingRevision);
    }

    /**
     * Writes this binding onto a key stack.
     *
     * <p>The key's own component rather than the mold's: {@code KEY_MOLD_BINDING} is a <em>record</em>
     * of a key, which is a different thing with a different lifetime, and a mold must never be
     * mistaken for a key by a component read.
     */
    public void writeTo(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        stack.set(CrimeDataComponents.LOCK_BINDING.get(), this);
    }

    /** The binding on a key stack, if it has one. */
    public static Optional<KeyBinding> read(@Nullable ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(stack.get(CrimeDataComponents.LOCK_BINDING.get()));
    }

    /** Clears a key's binding, turning it back into a blank. */
    public static void clear(@Nullable ItemStack stack) {
        if (stack != null && !stack.isEmpty()) {
            stack.remove(CrimeDataComponents.LOCK_BINDING.get());
        }
    }

    /** Whether a key stack carries any binding at all. */
    public static boolean bound(@Nullable ItemStack stack) {
        return read(stack).isPresent();
    }
}
