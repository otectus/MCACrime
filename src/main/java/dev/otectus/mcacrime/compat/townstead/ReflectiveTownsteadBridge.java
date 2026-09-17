package dev.otectus.mcacrime.compat.townstead;

import dev.otectus.mcacrime.compat.TownsteadBridge;
import dev.otectus.mcacrime.compat.TownsteadBuildingView;
import dev.otectus.mcacrime.compat.TownsteadCalendarView;
import dev.otectus.mcacrime.compat.TownsteadCapability;
import dev.otectus.mcacrime.compat.TownsteadLifeStageView;
import dev.otectus.mcacrime.compat.TownsteadNeedsView;
import dev.otectus.mcacrime.compat.TownsteadQueryResult;
import dev.otectus.mcacrime.compat.TownsteadScheduleView;
import dev.otectus.mcacrime.compat.TownsteadSpiritView;
import dev.otectus.mcacrime.compat.TownsteadVillagerView;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.fml.ModList;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The real {@link TownsteadBridge.Ops}, backed by {@link TownsteadHandles}.
 *
 * <p>Instantiated by name from {@code TownsteadBridge} only after {@code ModList} has confirmed
 * Townstead is present, which is why this class may reference {@link TownsteadHandles} — and why
 * nothing outside this package may reference <em>it</em>.
 *
 * <p>Constructing it forces the binding, so {@link #state()} is meaningful the moment the object
 * exists and the caller can log one accurate line.
 */
public final class ReflectiveTownsteadBridge implements TownsteadBridge.Ops {

    private final TownsteadBridge.State state;
    private final Set<TownsteadCapability> capabilities;
    private final String version;
    private final Optional<String> variant;

    public ReflectiveTownsteadBridge() {
        TownsteadBinding.Resolution resolution = TownsteadHandles.resolution();
        this.state = resolution.state();
        this.capabilities = resolution.capabilities();
        this.variant = Optional.ofNullable(resolution.variant());
        this.version = ModList.get()
                .getModContainerById(TownsteadBridge.MOD_ID)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse("");
    }

    @Override
    public TownsteadBridge.State state() {
        return state;
    }

    @Override
    public Set<TownsteadCapability> capabilities() {
        return capabilities;
    }

    @Override
    public String detectedVersion() {
        return version;
    }

    @Override
    public Optional<String> variant() {
        return variant;
    }

    @Override
    public List<String> unresolvedMembers() {
        return TownsteadHandles.resolution().unresolved();
    }

    @Override
    public TownsteadQueryResult<TownsteadVillagerView> villager(@Nullable Entity entity) {
        return TownsteadHandles.villager(entity);
    }

    @Override
    public TownsteadQueryResult<TownsteadNeedsView> needs(@Nullable Entity entity) {
        return TownsteadHandles.needs(entity);
    }

    @Override
    public TownsteadQueryResult<TownsteadScheduleView> schedule(@Nullable Entity entity) {
        return TownsteadHandles.schedule(entity);
    }

    @Override
    public TownsteadQueryResult<TownsteadLifeStageView> lifeStage(@Nullable Entity entity) {
        return TownsteadHandles.lifeStage(entity);
    }

    @Override
    public TownsteadQueryResult<TownsteadBuildingView> buildingAt(@Nullable ServerLevel level,
                                                                  @Nullable BlockPos pos) {
        return TownsteadHandles.buildingAt(level, pos);
    }

    @Override
    public TownsteadQueryResult<List<TownsteadBuildingView>> buildingsAt(@Nullable ServerLevel level,
                                                                         @Nullable BlockPos pos) {
        return TownsteadHandles.buildingsAt(level, pos);
    }

    @Override
    public TownsteadQueryResult<Integer> villageRevision(@Nullable ServerLevel level, int villageId) {
        return TownsteadHandles.villageRevision(level, villageId);
    }

    @Override
    public TownsteadQueryResult<Boolean> feedInCustody(@Nullable LivingEntity prisoner,
                                                       @Nullable ItemStack food,
                                                       @Nullable BlockPos source) {
        return TownsteadHandles.feedInCustody(prisoner, food, source);
    }

    @Override
    public TownsteadQueryResult<TownsteadCalendarView> calendar(@Nullable MinecraftServer server) {
        return TownsteadHandles.calendar(server);
    }

    @Override
    public TownsteadQueryResult<TownsteadSpiritView> spirit(@Nullable ServerLevel level, int villageId) {
        return TownsteadHandles.spirit(level, villageId);
    }

    @Override
    public TownsteadQueryResult<Integer> dispatchReaction(@Nullable ServerLevel level,
                                                          @Nullable LivingEntity villager,
                                                          @Nullable ResourceLocation taskId,
                                                          String phase) {
        return TownsteadHandles.dispatchReaction(level, villager, taskId, phase);
    }
}
