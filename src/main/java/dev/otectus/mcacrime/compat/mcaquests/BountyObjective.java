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
        return BountyContractBoard.describeBoard(level.getServer());
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
        return BountyContractBoard.hasOpenContracts(context.level().getServer())
                ? Optional.empty()
                : Optional.of(Component.translatable("quest.mcacrime.bounty.none_posted"));
    }
}
