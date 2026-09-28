package dev.otectus.mcacrime.compat.mcaquests;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.bounty.BountyContract;
import dev.otectus.mcacrime.bounty.BountyContractBoard;
import dev.otectus.mcacrime.bounty.BountyResolution;
import dev.otectus.mcacrime.bounty.BountyService;
import dev.otectus.mcacrime.compat.BountyCopyReconciler;
import dev.otectus.mcacrime.compat.BountyQuestBridge;
import dev.otectus.mcacrime.compat.McaQuestsBridge;
import dev.otectus.mcacrime.util.CrimeDebug;
import dev.otectus.mcaquests.api.McaQuestsApi;
import dev.otectus.mcaquests.api.event.QuestFailedEvent;
import dev.otectus.mcaquests.quest.QuestDefinition;
import dev.otectus.mcaquests.quest.QuestManager;
import dev.otectus.mcaquests.quest.objective.QuestObjective;
import dev.otectus.mcaquests.quest.situation.QuestDefinitions;
import dev.otectus.mcaquests.state.ActiveQuest;
import dev.otectus.mcaquests.state.PlayerQuestData;
import dev.otectus.mcaquests.state.QuestCapabilities;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The MCA: Quests half of the bounty bridge — the only class in this mod that names an MCA: Quests
 * type (0.5.1).
 *
 * <p>Loaded by name from {@code McaQuestsBridge} after {@code ModList} confirms the mod is installed,
 * and never referenced from anywhere else, so a server without MCA: Quests never asks a classloader
 * for any of the imports above. {@code OptionalClassloadTest} enforces that by scanning the compiled
 * output, and the build compiles this package against the vendored, hash-pinned MCA: Quests API jar.
 *
 * <h2>What this owns, and what it must not</h2>
 *
 * <p>Presentation only. The contract, the price, the legality of a resolution and the payment are all
 * MCA: Crime's, and this class is told about them after the fact. In particular {@link #publish} does
 * not create anything: MCA: Quests has no runtime quest-creation API, so the bounty board is a JSON
 * quest that exists permanently and hides itself through {@code BountyObjective.unofferableReason}
 * whenever nothing is posted.
 *
 * <h2>OPEN contract mode</h2>
 *
 * <p>The spec ships one mode: any number of hunters may hold the contract, and only the player the
 * ledger credited is paid. So a resolution completes the credited player's copy and, once the board
 * is empty, fails everyone else's with {@code TARGET_LOST} — the reason MCA: Quests already uses for
 * "the thing this quest was about is gone", which it is.
 *
 * <h2>Who holds the contract</h2>
 *
 * <p>Read from MCA: Quests' persisted per-player data every time, through
 * {@link BountyCopyReconciler}, never from memory. Until 0.7.5 holders lived in a static set filled by
 * the acceptance event, which a restart emptied, so a copy accepted before a restart could never fail.
 * A player who was offline when the board emptied is reconciled when they next log in.
 */
public final class McaQuestsBountyCompat implements BountyQuestBridge {

    /** The JSON quest this adapter drives. Its definition ships in this mod's own datapack. */
    public static final ResourceLocation QUEST_ID = new ResourceLocation("mcacrime", "bounty_board");

    /** The external signal a paid bounty pushes into MCA: Quests. */
    public static final ResourceLocation SIGNAL = new ResourceLocation("mcacrime", "bounty_resolved");

    /** The objective id registered with MCA: Quests, matching {@code "type"} in the quest JSON. */
    private static final ResourceLocation OBJECTIVE_ID = new ResourceLocation("mcacrime", "bounty");

    private static McaQuestsBountyCompat instance;

    /**
     * Registers the objective type and starts listening. Called reflectively from
     * {@code McaQuestsBridge.init()}, which itself runs inside {@code FMLCommonSetupEvent.enqueueWork}
     * — where MCA: Quests' own API documentation asks add-ons to register their types.
     *
     * <p>This is also the version handshake. MCA: Quests publishes no API generation constant, so the
     * honest test is whether these calls resolve: a build without them throws {@code NoSuchMethodError}
     * or {@code NoClassDefFoundError} out of here, and the bridge switches the integration off.
     */
    public static void register() {
        if (instance != null) {
            return;
        }
        BountyObjective.type = McaQuestsApi.registerObjective(OBJECTIVE_ID, BountyObjective.CODEC);
        instance = new McaQuestsBountyCompat();
        MinecraftForge.EVENT_BUS.register(instance);
        McaQuestsBridge.setImplementation(instance);
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public void publish(BountyContract contract) {
        // Nothing to create: the quest is permanent and offers itself as soon as the board is not
        // empty. Logged because "why is the guard offering that" is a question worth being able to
        // answer from a log rather than from a save file.
        CrimeDebug.compat("MCA: Quests bounty board now carries {} ({})", contract.targetName(),
                contract.contractId());
        // A new posting can also take work away: a hunter who has just earned their own warrant now
        // sees a board that is, for them, empty, so their copy is failed here rather than left to sit
        // as a quest they could never be paid for.
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            failHolders(server, null);
        }
    }

    @Override
    public void onBountyResolved(BountyResolution resolution) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        ServerPlayer claimant = server.getPlayerList().getPlayer(resolution.claimant());
        if (claimant != null) {
            // The signal, not a completion: MCA: Quests advances every matching objective, applies its
            // own rewards and runs its own turn-in. The principal has already been paid by MCA: Crime.
            QuestManager.notifyExternalObjective(claimant, SIGNAL, null);
        }
        // No board-wide check here: whether anything is left to collect is a per-holder question, and
        // failHolders asks it for each of them in turn.
        failHolders(server, claimant == null ? null : claimant.getUUID());
    }

    @Override
    public void invalidate(UUID contractId) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        // The contract is a board, not a target, so one posting closing leaves every accepted copy
        // playable — for the holders who still have something to claim. That is decided per holder,
        // because a holder whose only remaining posting is their own has nothing claimable at all.
        failHolders(server, null);
    }

    // ------------------------------------------------------------------ holders

    /**
     * Reconciles a player who was offline when the board changed: the board may have emptied while
     * they were away, and nothing else would ever fail their copy. LOW priority, so MCA: Crime's own
     * login handler has already delivered any payment they were owed, and with it the signal that
     * satisfies the copy; a payment still pending keeps the copy alive either way.
     */
    @SubscribeEvent(priority = EventPriority.LOW)
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.getServer() == null) {
            return;
        }
        QuestDefinition definition = QuestDefinitions.resolve(QUEST_ID).orElse(null);
        PlayerQuestData data = QuestCapabilities.get(player).orElse(null);
        if (definition == null || data == null) {
            return;
        }
        BountyCopyReconciler.reconcile(List.of(new QuestHolder(player, data, definition)), null,
                board(player.getServer()));
    }

    /**
     * Fails every online copy of the contract held by a player with nothing left to collect.
     *
     * <p>Asked per holder rather than of the board as a whole, because a board that still carries one
     * posting may carry only that holder's own warrant, which they can never claim. An empty board is
     * the honest meaning of TARGET_LOST for a contract whose target is now dead, jailed or lawful, and
     * a board holding nothing but their own name means exactly that to them. The credited player, a
     * copy already satisfied, and a holder with a payment still pending are skipped: each is owed a
     * turn-in, and failing it would take back what the ledger paid for ({@link BountyCopyReconciler}).
     */
    private void failHolders(MinecraftServer server, @Nullable UUID credited) {
        QuestDefinition definition = QuestDefinitions.resolve(QUEST_ID).orElse(null);
        if (definition == null) {
            return;
        }
        List<QuestHolder> holders = new ArrayList<>();
        for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
            QuestCapabilities.get(player).ifPresent(data -> holders.add(new QuestHolder(player, data, definition)));
        }
        BountyCopyReconciler.reconcile(holders, credited, board(server));
    }

    private static BountyCopyReconciler.Board board(MinecraftServer server) {
        return new BountyCopyReconciler.Board() {
            @Override
            public boolean hasClaimable(UUID player) {
                return BountyContractBoard.hasOpenContracts(server, player);
            }

            @Override
            public boolean paymentPending(UUID player) {
                return BountyService.hasUndeliveredPayment(server, player);
            }
        };
    }

    /** One online player's contract copies, read from their persisted MCA: Quests data. */
    private record QuestHolder(ServerPlayer player, PlayerQuestData data, QuestDefinition definition)
            implements BountyCopyReconciler.Holder<ActiveQuest> {

        @Override
        public UUID id() {
            return player.getUUID();
        }

        @Override
        public List<ActiveQuest> copies() {
            List<ActiveQuest> copies = new ArrayList<>();
            for (ActiveQuest active : data.active()) {
                if (QUEST_ID.equals(active.questId())) {
                    copies.add(active);
                }
            }
            return copies;
        }

        @Override
        public boolean satisfied(ActiveQuest copy) {
            List<QuestObjective> objectives = copy.resolve(definition).objectives();
            for (int i = 0; i < objectives.size(); i++) {
                if (objectives.get(i) instanceof BountyObjective bounty
                        && bounty.isSatisfied(player, copy.progress(i))) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void fail(ActiveQuest copy) {
            try {
                QuestManager.failQuest(player, copy, copy.resolve(definition),
                        QuestFailedEvent.Reason.TARGET_LOST, null, data);
            } catch (Throwable t) {
                McaCrime.LOGGER.debug("MCA: Crime - failing a bounty contract copy threw; ignoring", t);
            }
        }
    }
}
