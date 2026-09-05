package dev.otectus.mcacrime.compat.mcaquests;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.bounty.BountyContract;
import dev.otectus.mcacrime.bounty.BountyContractBoard;
import dev.otectus.mcacrime.bounty.BountyResolution;
import dev.otectus.mcacrime.compat.BountyQuestBridge;
import dev.otectus.mcacrime.compat.McaQuestsBridge;
import dev.otectus.mcacrime.util.CrimeDebug;
import dev.otectus.mcaquests.api.McaQuestsApi;
import dev.otectus.mcaquests.api.event.QuestAbandonedEvent;
import dev.otectus.mcaquests.api.event.QuestAcceptedEvent;
import dev.otectus.mcaquests.api.event.QuestCompletedEvent;
import dev.otectus.mcaquests.api.event.QuestFailedEvent;
import dev.otectus.mcaquests.quest.QuestDefinition;
import dev.otectus.mcaquests.quest.QuestManager;
import dev.otectus.mcaquests.quest.situation.QuestDefinitions;
import dev.otectus.mcaquests.state.ActiveQuest;
import dev.otectus.mcaquests.state.PlayerQuestData;
import dev.otectus.mcaquests.state.QuestCapabilities;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The MCA: Quests half of the bounty bridge — the only class in this mod that names an MCA: Quests
 * type (0.5.1).
 *
 * <p>Loaded by name from {@code McaQuestsBridge} after {@code ModList} confirms the mod is installed,
 * and never referenced from anywhere else, so a server without MCA: Quests never asks a classloader
 * for any of the imports above. {@code OptionalClassloadTest} enforces that by scanning the compiled
 * output, and the Gradle build drops this package entirely when the sibling project is not present.
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
 */
public final class McaQuestsBountyCompat implements BountyQuestBridge {

    /** The JSON quest this adapter drives. Its definition ships in this mod's own datapack. */
    public static final ResourceLocation QUEST_ID = McaCrime.id("bounty_board");

    /** The external signal a paid bounty pushes into MCA: Quests. */
    public static final ResourceLocation SIGNAL = McaCrime.id("bounty_resolved");

    /** The objective id registered with MCA: Quests, matching {@code "type"} in the quest JSON. */
    private static final ResourceLocation OBJECTIVE_ID = McaCrime.id("bounty");

    /**
     * Players known to be holding the contract.
     *
     * <p>A cache, not the truth: {@code PlayerQuestData} is authoritative and is re-checked before
     * anything is failed. It exists so the common case — a bounty paid on a server where nobody took
     * the quest — costs one empty-set check instead of a capability lookup per online player.
     */
    private static final Set<UUID> HOLDERS = ConcurrentHashMap.newKeySet();

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
        // Imperative listeners, never @EventBusSubscriber: NeoForge's annotation scan runs for every
        // class in the jar, so an annotated listener in this package would be eagerly loaded -- and
        // throw NoClassDefFoundError -- on any server that does not have MCA: Quests installed.
        NeoForge.EVENT_BUS.addListener(instance::onQuestAccepted);
        NeoForge.EVENT_BUS.addListener(instance::onQuestCompleted);
        NeoForge.EVENT_BUS.addListener(instance::onQuestFailed);
        NeoForge.EVENT_BUS.addListener(instance::onQuestAbandoned);
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
        if (!BountyContractBoard.hasOpenContracts(server)) {
            failHolders(server, claimant == null ? null : claimant.getUUID());
        }
    }

    @Override
    public void invalidate(UUID contractId) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null || BountyContractBoard.hasOpenContracts(server)) {
            // Still work on the board: the contract is a board, not a target, so one posting closing
            // leaves every accepted copy perfectly playable.
            return;
        }
        failHolders(server, null);
    }

    // ------------------------------------------------------------------ holder bookkeeping

    public void onQuestAccepted(QuestAcceptedEvent event) {
        if (QUEST_ID.equals(event.getQuestId())) {
            HOLDERS.add(event.getPlayer().getUUID());
        }
    }

    public void onQuestCompleted(QuestCompletedEvent event) {
        forget(event.getQuestId(), event.getPlayer());
    }

    public void onQuestFailed(QuestFailedEvent event) {
        forget(event.getQuestId(), event.getPlayer());
    }

    public void onQuestAbandoned(QuestAbandonedEvent event) {
        forget(event.getQuestId(), event.getPlayer());
    }

    private void forget(ResourceLocation questId, ServerPlayer player) {
        if (QUEST_ID.equals(questId)) {
            HOLDERS.remove(player.getUUID());
        }
    }

    /**
     * Fails every online copy of the contract except the credited player's.
     *
     * <p>Only reached with an empty board, which is the honest meaning of TARGET_LOST for a contract
     * whose target is now dead, jailed or lawful. The credited player is skipped because their copy is
     * satisfied and about to be turned in — failing it would take back what the ledger just paid for.
     */
    private void failHolders(MinecraftServer server, @Nullable UUID credited) {
        if (HOLDERS.isEmpty()) {
            return;
        }
        QuestDefinition definition = QuestDefinitions.resolve(QUEST_ID).orElse(null);
        if (definition == null) {
            return;
        }
        for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
            if (player.getUUID().equals(credited) || !HOLDERS.contains(player.getUUID())) {
                continue;
            }
            PlayerQuestData data = QuestCapabilities.get(player).orElse(null);
            if (data == null) {
                continue;
            }
            for (ActiveQuest active : new ArrayList<>(data.active())) {
                if (!QUEST_ID.equals(active.questId())) {
                    continue;
                }
                try {
                    QuestManager.failQuest(player, active, active.resolve(definition),
                            QuestFailedEvent.Reason.TARGET_LOST, null, data);
                } catch (Throwable t) {
                    McaCrime.LOGGER.debug("MCA: Crime - failing a bounty contract copy threw; ignoring", t);
                }
            }
        }
    }
}
