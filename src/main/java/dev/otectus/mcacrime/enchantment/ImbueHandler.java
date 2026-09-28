package dev.otectus.mcacrime.enchantment;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.restraint.PhysicalRestraintState;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;

import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Imbue: part of a captor's damage is taken by the people they hold (0.7.5 §3.10, M6.1).
 *
 * <p>A rewrite of {@code event/ModServerEvents.onLivingDamaged} ({@code :399-446}), not a port. The
 * arithmetic is in {@link ImbueMath} and the recipient set is in {@link ImbueIndex}; what is left
 * here is the event plumbing and the three guarantees upstream does not have:
 *
 * <ol>
 *   <li><b>No recursion.</b> Hurting a prisoner raises another {@code LivingDamageEvent}, which
 *       re-enters this handler; if that prisoner is themselves a captor of an Imbue-restrained
 *       subject, upstream's recursion has no terminator. A re-entrancy flag on this thread ends it
 *       at depth one: a transfer never transfers again.</li>
 *   <li><b>No duplicate recipients and no in-loop accumulator.</b> Both are structural — see
 *       {@link ImbueIndex} and {@link ImbueMath}.</li>
 *   <li><b>Attributed damage.</b> The transfer arrives as {@code mcacrime:imbue} naming the captor,
 *       rather than as anonymous magic.</li>
 * </ol>
 *
 * <p>Relief is priced on what was actually taken: recipients are resolved and filtered for being
 * alive <em>before</em> the budget is split, so a captor is never spared damage that no prisoner
 * received.
 */
@EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class ImbueHandler {

    /**
     * True while this handler is inside a transfer on this thread.
     *
     * <p>A {@link ThreadLocal} rather than a static boolean because damage can be dealt from more
     * than one thread in a modded server, and a flag that leaked across threads would silently
     * disable the enchantment rather than fail visibly.
     */
    private static final ThreadLocal<Boolean> TRANSFERRING = ThreadLocal.withInitial(() -> false);

    private ImbueHandler() {
    }

    @SubscribeEvent
    public static void onDamage(LivingDamageEvent.Pre event) {
        LivingEntity captor = event.getEntity();
        if (captor == null || captor.level().isClientSide() || TRANSFERRING.get()) {
            return;
        }
        if (ImbueIndex.empty() || !enabled()) {
            return;
        }
        MinecraftServer server = captor.getServer();
        if (server == null) {
            return;
        }
        float original = event.getNewDamage();
        if (!Float.isFinite(original) || original <= 0.0F) {
            return; // a non-finite amount is rejected, never propagated onto a prisoner
        }
        List<UUID> held = ImbueIndex.recipients(captor.getUUID());
        if (held.isEmpty()) {
            return;
        }
        CrimeWorldData data = CrimeWorldData.get(server);
        List<LivingEntity> recipients = new ArrayList<>(held.size());
        int level = 0;
        for (UUID subjectId : held) {
            LivingEntity subject = living(server, subjectId);
            if (subject == null || !subject.isAlive() || subject == captor) {
                continue;
            }
            PhysicalRestraintState state = data == null ? null : data.physicalRestraint(subjectId);
            int worn = RestraintEnchantments.levelOn(state, CrimeEnchantKind.IMBUE);
            if (worn <= 0) {
                continue; // the index is a second old; the authority is what they are wearing now
            }
            level = Math.max(level, worn);
            recipients.add(subject);
        }
        if (recipients.isEmpty() || level <= 0) {
            return;
        }
        ImbueMath.Distribution split = ImbueMath.distribute(original, level, transferPerLevel(),
                maxTransferFraction(), recipients.size(), maxRecipients());
        if (!split.moves()) {
            return;
        }
        DamageSource source = ImbueDamage.source(captor.level(), captor);
        if (source == null) {
            return; // no damage type, no transfer, and the captor keeps every point of it
        }
        TRANSFERRING.set(true);
        try {
            for (int i = 0; i < split.recipients() && i < recipients.size(); i++) {
                recipients.get(i).hurt(source, split.perRecipient());
            }
        } finally {
            TRANSFERRING.set(false);
        }
        event.setNewDamage(split.retained());
    }

    /** Whether Imbue is switched on at all. */
    private static boolean enabled() {
        return EnchantmentApplicability.allowedBy(CrimeEnchantKind.IMBUE,
                EnchantmentApplicability.allowedFromConfig());
    }

    private static double transferPerLevel() {
        try {
            return McaCrimeConfig.COMMON.imbueTransferPerLevel.get();
        } catch (IllegalStateException | NullPointerException notLoaded) {
            return ImbueMath.DEFAULT_PER_LEVEL;
        }
    }

    private static double maxTransferFraction() {
        try {
            return McaCrimeConfig.COMMON.imbueMaxTransferFraction.get();
        } catch (IllegalStateException | NullPointerException notLoaded) {
            return ImbueMath.DEFAULT_MAX_FRACTION;
        }
    }

    private static int maxRecipients() {
        try {
            return McaCrimeConfig.COMMON.imbueMaxRecipients.get();
        } catch (IllegalStateException | NullPointerException notLoaded) {
            return 8;
        }
    }

    @Nullable
    private static LivingEntity living(MinecraftServer server, @Nullable UUID id) {
        if (id == null) {
            return null;
        }
        for (ServerLevel level : server.getAllLevels()) {
            Entity found = level.getEntity(id);
            if (found instanceof LivingEntity living) {
                return living;
            }
        }
        return null;
    }
}
