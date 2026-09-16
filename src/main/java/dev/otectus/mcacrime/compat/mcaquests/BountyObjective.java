package dev.otectus.mcacrime.compat.mcaquests;

import com.mojang.serialization.Codec;
import dev.otectus.mcacrime.bounty.BountyContractBoard;
import dev.otectus.mcaquests.api.ExternalSignalObjective;
import dev.otectus.mcaquests.quest.condition.QuestContext;
import dev.otectus.mcaquests.quest.objective.ObjectiveProgress;
import dev.otectus.mcaquests.quest.objective.QuestObjective;
import dev.otectus.mcaquests.quest.objective.QuestObjectiveType;
import dev.otectus.mcaquests.state.ActiveQuest;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.Optional;
import java.util.UUID;

/**
 * The quest objective "collect one of the bounties currently posted" (0.5.1).
 *
 * <p>It carries no target, and that is the design rather than a simplification. MCA: Quests defines
 * quests in JSON with no runtime creation API, so a quest per outlaw cannot exist; what can is one
 * standing contract from a guard whose text is computed when it is shown. The board is therefore read
 * at display time and never captured, which also means a hunter who accepted the contract yesterday
 * sees today's postings rather than a poster for somebody who has since served their sentence.
 *
 * <p>Read per viewer, too: the board is filtered to what this player could actually claim, so a wanted
 * player is never offered — or shown — the contract against themselves, and the quest hides itself when
 * their own warrant is the only thing posted.
 *
 * <p>Progress arrives from outside: MCA: Crime pays the bounty, decides it was lawful, and only then
 * pushes {@link McaQuestsBountyCompat#SIGNAL} at the credited player. Nothing here can complete
 * itself, which is what keeps the two mods from both awarding the principal.
 */
public record BountyObjective() implements QuestObjective, ExternalSignalObjective {

    /** No fields, so no data: the objective is entirely {@code {"type": "mcacrime:bounty"}}. */
    public static final Codec<BountyObjective> CODEC = Codec.unit(BountyObjective::new);

    /** Set once by {@link McaQuestsBountyCompat#register()}; the dispatch codec needs it back. */
    static QuestObjectiveType<BountyObjective> type;

    @Override
    public QuestObjectiveType<?> type() {
        return type;
    }

    @Override
    public Component describe() {
        return Component.translatable("quest.mcacrime.bounty.body");
    }

    @Override
    public Component describe(ServerPlayer player, ActiveQuest active, ServerLevel level) {
        return BountyContractBoard.describeBoard(level.getServer(), player.getUUID());
    }

    @Override
    public int required() {
        return 1;
    }

    @Override
    public int current(ServerPlayer player, ObjectiveProgress progress) {
        return Math.min(progress.count(), required());
    }

    @Override
    public boolean isSatisfied(ServerPlayer player, ObjectiveProgress progress) {
        return progress.count() >= required();
    }

    @Override
    public boolean isEventDriven() {
        return true;
    }

    @Override
    public boolean matchesSignal(ResourceLocation signalId, UUID villagerUuid) {
        // Never villager-specific: the guard who posted the contract is not the one who resolves it.
        return McaQuestsBountyCompat.SIGNAL.equals(signalId);
    }

    @Override
    public Optional<Component> unofferableReason(QuestContext context) {
        // A guard with nothing on the board should not be offering bounty work at all, which is the
        // whole of the "publish" side of the bridge: the quest exists permanently and hides itself.
        // Hidden for a second reason as well: a board carrying nothing but this player's own warrant
        // is work they could never be paid for, so it is declined with its own line rather than the
        // empty-board one.
        MinecraftServer server = context.level().getServer();
        if (BountyContractBoard.hasOpenContracts(server, context.player().getUUID())) {
            return Optional.empty();
        }
        return Optional.of(Component.translatable(BountyContractBoard.hasOpenContracts(server)
                ? "quest.mcacrime.bounty.own_warrant"
                : "quest.mcacrime.bounty.none_posted"));
    }
}
