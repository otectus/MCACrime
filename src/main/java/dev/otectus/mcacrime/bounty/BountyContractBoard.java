package dev.otectus.mcacrime.bounty;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.api.event.BountyResolvedEvent;
import dev.otectus.mcacrime.api.event.WarrantChangedEvent;
import dev.otectus.mcacrime.compat.McaQuestsBridge;
import dev.otectus.mcacrime.economy.Currencies;
import dev.otectus.mcacrime.ledger.Warrant;
import dev.otectus.mcacrime.state.world.BountyContractRecord;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.util.CrimeDebug;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What is currently worth collecting, and who has been told about it (0.5.1).
 *
 * <p>The spec's rule for contract generation is "not one per point of Heat": a contract is minted when
 * a warrant becomes bounty-eligible and re-minted when a revision changes what it is worth, which is
 * exactly the two edges {@link WarrantChangedEvent} already reports. So the board owns no schedule and
 * no scan — it reacts to the warrant ledger and to payment, and nothing else.
 *
 * <p>One contract per {@code (warrant, revision)}, enforced by replacement rather than by a uniqueness
 * check: a revision is a new price on a new offence, so posting it removes the posting it supersedes.
 * That matters because a claim key carries the revision too — a hunter who accepted the old posting
 * and kills the target now is paid at the new price, once, and the old contract must not be sitting
 * around suggesting otherwise.
 *
 * <p>Contracts live in {@code CrimeWorldData}, not in the quest mod. A contract that existed only
 * inside an optional mod would vanish with it, taking the outstanding warrant's reward with it.
 */
@EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class BountyContractBoard {

    /** How many postings the objective text lists in full before it summarises the rest. */
    private static final int MAX_LISTED = 3;

    private BountyContractBoard() {
    }

    // ------------------------------------------------------------------ the two edges that matter

    @SubscribeEvent
    public static void onWarrantChanged(WarrantChangedEvent event) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || !McaCrimeConfig.COMMON.bountyEnabled.get()) {
            return;
        }
        Warrant warrant = event.getWarrant();
        CrimeWorldData data = CrimeWorldData.get(server);

        if (event.getChange() == WarrantChangedEvent.Change.CLOSED) {
            // The spec is explicit that a contract on somebody who is no longer an outlaw must become
            // invalid rather than stand: it would otherwise be an instruction to murder a lawful
            // civilian for money the ledger will refuse to pay.
            withdrawAndNotify(data, warrant.id());
            return;
        }

        long principal = BountyService.price(server, event.getOffender());
        if (principal <= 0L) {
            // Eligible, but worth nothing: posting a contract for a reward of zero is worse than
            // posting none, because a hunter would take it.
            withdrawAndNotify(data, warrant.id());
            return;
        }
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        BountyContract contract = post(data, warrant, nameOf(server, event.getOffender(), data),
                principal, c.payForKills.get(), c.payForAliveCapture.get(), 0L);
        CrimeDebug.crime("bounty contract posted for {} rev {} worth {}", event.getOffender(),
                warrant.revision(), principal);
        McaQuestsBridge.get().publish(contract);
    }

    @SubscribeEvent
    public static void onBountyResolved(BountyResolvedEvent event) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        // Keyed on the claim key rather than the target: a contract posted against a later revision is
        // a different posting and is not settled by this payment. Withdrawn before the bridge is told,
        // so a bridge that asks "is anything still posted" sees the board as it now stands.
        withdrawClaimed(CrimeWorldData.get(server), event.getResolution().claimKey());
        McaQuestsBridge.get().onBountyResolved(event.getResolution());
    }

    /** Withdraws every posting of one warrant and tells the bridge about each, one call per id. */
    private static void withdrawAndNotify(CrimeWorldData data, UUID warrantId) {
        for (UUID id : withdraw(data, warrantId)) {
            McaQuestsBridge.get().invalidate(id);
        }
    }

    // ------------------------------------------------------------------ the board itself (pure)

    /**
     * Posts a contract for the current revision of {@code warrant}, replacing any earlier posting of
     * the same warrant.
     */
    public static BountyContract post(CrimeWorldData data, Warrant warrant, String targetName,
                                      long principalReward, boolean killAllowed, boolean captureAllowed,
                                      long expiresAtGameTime) {
        withdraw(data, warrant.id());
        BountyContract contract = new BountyContract(UUID.randomUUID(), warrant.offender(), targetName,
                warrant.id(), warrant.revision(), Math.max(0L, principalReward), killAllowed,
                captureAllowed, expiresAtGameTime);
        data.putBountyContract(contract.toRecord());
        return contract;
    }

    /** Removes every posting of one warrant, whatever revision. Returns the ids removed. */
    public static List<UUID> withdraw(CrimeWorldData data, UUID warrantId) {
        List<UUID> removed = new ArrayList<>();
        if (data == null || warrantId == null) {
            return removed;
        }
        for (BountyContractRecord record : data.bountyContracts()) {
            if (warrantId.equals(record.warrantId())) {
                data.removeBountyContract(record.contractId());
                removed.add(record.contractId());
            }
        }
        return removed;
    }

    /**
     * Removes the one posting a claim just settled, if it is still up.
     *
     * <p>Empty on the second call for the same key, which is the property that matters: a resolution
     * is paid once, so it may invalidate a contract once.
     */
    public static Optional<UUID> withdrawClaimed(CrimeWorldData data, BountyClaimKey key) {
        if (data == null || key == null) {
            return Optional.empty();
        }
        for (BountyContractRecord record : data.bountyContracts()) {
            if (key.target().equals(record.target()) && key.warrantId().equals(record.warrantId())
                    && key.revision() == record.warrantRevision()) {
                data.removeBountyContract(record.contractId());
                return Optional.of(record.contractId());
            }
        }
        return Optional.empty();
    }

    /** Every contract currently posted. */
    public static List<BountyContract> openContracts(CrimeWorldData data) {
        List<BountyContract> out = new ArrayList<>();
        if (data == null) {
            return out;
        }
        for (BountyContractRecord record : data.bountyContracts()) {
            out.add(BountyContract.from(record));
        }
        return out;
    }

    /** Every contract currently posted in this world. */
    public static List<BountyContract> openContracts(@Nullable MinecraftServer server) {
        return server == null ? List.of() : openContracts(CrimeWorldData.get(server));
    }

    /** Whether anything at all is posted. The quest bridge hides its offer when nothing is. */
    public static boolean hasOpenContracts(@Nullable MinecraftServer server) {
        return !openContracts(server).isEmpty();
    }

    // ------------------------------------------------------------------ presentation

    /**
     * The wanted poster for one contract: who, what for, what it pays, and on what terms.
     *
     * <p>Built here rather than in the quest adapter because the adapter is optional and this text is
     * not — a debug command or a later in-game board reads the same lines. The offence and the "and N
     * more" count come from the live warrant, so a poster written before three more burglaries still
     * says what the target is currently wanted for.
     */
    public static Component describe(BountyContract contract) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        MutableComponent text = Component.translatable("quest.mcacrime.bounty.title", contract.targetName())
                .withStyle(ChatFormatting.GOLD);

        if (server != null) {
            Warrant warrant = CrimeWorldData.get(server).warrant(contract.targetUuid());
            if (warrant != null && warrant.topOffense() != null) {
                text.append("\n").append(Component.translatable("quest.mcacrime.bounty.wanted_for",
                        Component.translatable("crime.mcacrime." + warrant.topOffense().getPath())));
                int more = CrimeWorldData.get(server).actionableFor(contract.targetUuid()).size() - 1;
                if (more > 0) {
                    text.append("\n").append(Component.translatable("quest.mcacrime.bounty.more", more));
                }
            }
        }

        text.append("\n").append(Component.translatable("quest.mcacrime.bounty.reward",
                Currencies.active().format(contract.principalReward())));
        if (contract.captureAllowed()) {
            text.append("\n").append(Component.translatable("quest.mcacrime.bounty.alive",
                    percent(McaCrimeConfig.COMMON.aliveCaptureMultiplier.get())));
        }
        if (contract.killAllowed()) {
            text.append("\n").append(Component.translatable("quest.mcacrime.bounty.dead",
                    percent(McaCrimeConfig.COMMON.killMultiplier.get())));
        }
        text.append("\n").append(Component.translatable("quest.mcacrime.bounty.body"));
        return text;
    }

    /**
     * The whole board as one block of text, for an objective line.
     *
     * <p>Capped at {@link #MAX_LISTED}: a server with forty outlaws on it would otherwise hand the
     * quest log forty posters, and the fourth one is not what the hunter is reading anyway.
     */
    public static Component describeBoard(@Nullable MinecraftServer server) {
        List<BountyContract> open = openContracts(server);
        if (open.isEmpty()) {
            return Component.translatable("quest.mcacrime.bounty.none_posted");
        }
        MutableComponent text = Component.empty();
        for (int i = 0; i < Math.min(MAX_LISTED, open.size()); i++) {
            if (i > 0) {
                text.append("\n");
            }
            text.append(describe(open.get(i)));
        }
        if (open.size() > MAX_LISTED) {
            text.append("\n").append(Component.translatable("quest.mcacrime.bounty.more",
                    open.size() - MAX_LISTED));
        }
        return text;
    }

    /** A multiplier as the whole-number percentage the spec's poster shows. */
    private static int percent(double multiplier) {
        return (int) Math.round(Math.max(0.0D, multiplier) * 100.0D);
    }

    /**
     * The name to put on the poster. Online name first, then the profile cache, then whatever the
     * previous posting said — an offline target's name cannot be recomputed, so a stale one beats none.
     */
    private static String nameOf(MinecraftServer server, UUID offender, CrimeWorldData data) {
        ServerPlayer online = server.getPlayerList().getPlayer(offender);
        if (online != null) {
            return online.getGameProfile().getName();
        }
        Optional<String> cached = server.getProfileCache() == null ? Optional.empty()
                : server.getProfileCache().get(offender).map(profile -> profile.getName());
        if (cached.isPresent() && !cached.get().isBlank()) {
            return cached.get();
        }
        for (BountyContractRecord record : data.bountyContracts()) {
            if (offender.equals(record.target()) && !record.targetName().isBlank()) {
                return record.targetName();
            }
        }
        return offender.toString();
    }
}
