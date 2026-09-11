package dev.otectus.mcacrime.enforcement;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.crime.type.CrimeIds;
import dev.otectus.mcacrime.detect.WitnessResult;
import dev.otectus.mcacrime.incident.IncidentService;
import dev.otectus.mcacrime.item.contraband.ContrabandInventoryScanner;
import dev.otectus.mcacrime.item.contraband.ContrabandInventoryScanner.Options;
import dev.otectus.mcacrime.item.contraband.ContrabandProbe;
import dev.otectus.mcacrime.item.contraband.ContrabandRules;
import dev.otectus.mcacrime.item.contraband.ContrabandScanAdapter;
import dev.otectus.mcacrime.state.CrimeAttachments;
import dev.otectus.mcacrime.state.PlayerCrimeData;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import org.jetbrains.annotations.Nullable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Guards finding what a player is carrying (0.7.0, plan §5.2).
 *
 * <p>Throttled to {@code contraband.searchIntervalTicks} on the server tick, never per tick per player,
 * and it costs nothing at all while the feature is off or the list is empty — which is the shipped
 * default. What decides a search is {@link ContrabandSearchRules}; this gathers the facts, walks the
 * inventory through {@code ContrabandScanAdapter}, and files the charge.
 *
 * <p>A search is not free to a player either, so it is deliberately hard to trigger: a guard has to be
 * close, have watched them for {@code searchLosTicksRequired} unbroken ticks, have a reason to stop
 * them at all, and then win a roll. One guard searches at most one player per pass.
 */
@EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class ContrabandSearchService {

    /** guard id -> who it has been watching, and for how long. Memory-only; a watch is not a fact. */
    private static final Map<UUID, Watch> WATCHES = new ConcurrentHashMap<>();

    private record Watch(UUID player, long losTicks) {
    }

    private static int counter;

    private ContrabandSearchService() {
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        int interval = Math.max(1, McaCrimeConfig.COMMON.contrabandSearchIntervalTicks.get());
        if (++counter < interval) {
            return;
        }
        counter = 0;
        if (!ContrabandPolicy.enabled() || !ContrabandPolicy.mode().allowsPatrol()) {
            WATCHES.clear(); // nothing is being watched while nothing can be searched
            return;
        }
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        try {
            patrol(server, interval);
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA: Crime contraband patrol failed; continuing", t);
        }
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        WATCHES.clear();
        counter = 0;
    }

    private static void patrol(MinecraftServer server, int interval) {
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        double radius = c.contrabandSearchRadius.get();
        int requiredLos = c.contrabandSearchLosTicksRequired.get();
        boolean requiresSuspicion = c.contrabandSearchRequiresSuspicion.get();
        double chance = c.contrabandSearchChance.get();
        Set<UUID> busy = new LinkedHashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!(player.level() instanceof ServerLevel level) || player.isSpectator() || player.isCreative()) {
                continue;
            }
            boolean suspicious = OutlawResolver.resolve(player).lawfulCombatTarget();
            AABB box = player.getBoundingBox().inflate(radius);
            for (LivingEntity guard : level.getEntitiesOfClass(LivingEntity.class, box, McaCompat::isGuard)) {
                UUID guardId = guard.getUUID();
                if (!busy.add(guardId)) {
                    continue; // one candidate per guard per pass
                }
                boolean lineOfSight = guard.hasLineOfSight(player);
                Watch previous = WATCHES.get(guardId);
                long carried = previous != null && player.getUUID().equals(previous.player())
                        ? previous.losTicks() : 0L;
                long losTicks = ContrabandSearchRules.accumulate(carried, lineOfSight, interval);
                WATCHES.put(guardId, new Watch(player.getUUID(), losTicks));
                if (!ContrabandSearchRules.searches(true, true, true, losTicks, requiredLos,
                        requiresSuspicion, suspicious, chance, level.random.nextDouble())) {
                    continue;
                }
                // The watch is spent whether or not anything was found: a guard that has patted somebody
                // down starts counting again rather than searching them every pass from then on.
                WATCHES.put(guardId, new Watch(player.getUUID(), 0L));
                search(level, guard, player);
            }
        }
        WATCHES.keySet().removeIf(guard -> !busy.contains(guard));
    }

    /**
     * The search an arrest always runs (plan §5.2).
     *
     * <p>No chance roll and no line-of-sight requirement: somebody being arrested is already in a
     * guard's hands, and "you were searched only if a die said so while you were being cuffed" is not a
     * rule anybody would write down. {@code GUARD_PATROL} mode is the one case where it does not run.
     */
    public static void onArrest(ServerLevel level, ServerPlayer player, @Nullable LivingEntity guard) {
        if (level == null || player == null || !ContrabandPolicy.enabled()
                || !ContrabandPolicy.mode().allowsArrest()) {
            return;
        }
        try {
            search(level, guard, player);
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA: Crime contraband arrest search failed; continuing", t);
        }
    }

    /**
     * One search: walk what the player is carrying, charge for what is listed, and say what happened.
     *
     * <p>"Never announce what a guard did not find" is the rule the two messages encode: the
     * {@code searched} line is sent only when a search actually ran and turned up nothing, and the
     * {@code found} line names the item, because a player told only that they have been charged cannot
     * tell which of the things they are carrying is the illegal one.
     */
    public static void search(ServerLevel level, @Nullable LivingEntity guard, ServerPlayer player) {
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        ContrabandRules rules = ContrabandPolicy.rules();
        Options options = ContrabandPolicy.options();
        boolean perStack = c.perItemStackHeat.get();
        List<ContrabandProbe> listed = ContrabandInventoryScanner.listed(
                ContrabandScanAdapter.probes(player, options), rules, options, !perStack);
        Component guardName = guard == null ? Component.translatable("mcacrime.contraband.guard")
                : guard.getDisplayName().copy();
        if (listed.isEmpty()) {
            player.sendSystemMessage(Component.translatable("mcacrime.msg.contraband.searched", guardName));
            return;
        }
        player.sendSystemMessage(Component.translatable("mcacrime.msg.contraband.found",
                Component.literal(listed.get(0).displayName()), guardName));

        long fingerprint = ContrabandFingerprint.of(listed);
        long now = level.getGameTime();
        PlayerCrimeData data = CrimeAttachments.get(player);
        long lastFingerprint = data.getLastContrabandFingerprint();
        long lastCharge = data.getLastContrabandChargeTick();
        if (ContrabandFingerprint.shouldCharge(fingerprint, lastFingerprint, now, lastCharge,
                c.contrabandRechargeTicks.get())) {
            charge(level, player, listed, fingerprint, now, perStack);
            data.setLastContrabandFingerprint(fingerprint);
            data.setLastContrabandChargeTick(now);
        }
        if (c.confiscateOnDiscovery.get()) {
            confiscate(level, player, guardName, listed, options);
        }
    }

    /**
     * One charge for the haul, or one per distinct illegal item id when {@code perItemStackHeat} is on.
     *
     * <p>Per <em>id</em> rather than per stack: a player carrying three stacks of the same banned item
     * has committed one kind of offence, and pricing it by how they happened to stack it is the same
     * mistake {@code thiefItemTheftMode} exists to avoid.
     */
    private static void charge(ServerLevel level, ServerPlayer player, List<ContrabandProbe> listed,
                               long fingerprint, long now, boolean perStack) {
        McaCrimeConfig.Common c = McaCrimeConfig.COMMON;
        Long karma = c.contrabandKarma.get() < 0L ? null : -Math.abs(c.contrabandKarma.get());
        Long heat = c.contrabandHeat.get() < 0L ? null : c.contrabandHeat.get();
        Set<ResourceLocation> charged = new LinkedHashSet<>();
        for (ContrabandProbe probe : listed) {
            if (!perStack && !charged.isEmpty()) {
                break;
            }
            if (probe.itemId() != null && !charged.add(probe.itemId())) {
                continue;
            }
            IncidentService.commitPlayer(incidentId(player, fingerprint, now, charged.size()), player,
                    CrimeIds.POSSESS_CONTRABAND, null, level, WitnessResult.none(), "contraband",
                    Map.of("contraband.item", probe.idString()), karma, heat);
            if (probe.itemId() == null) {
                break; // an unregistered item cannot be told apart from the next one
            }
        }
    }

    /**
     * A stable id for this haul, so a retry of the same search commits once. The ordinal keeps the
     * per-item charges distinct without making any of them depend on the order they were found in.
     */
    private static UUID incidentId(ServerPlayer player, long fingerprint, long now, int ordinal) {
        return UUID.nameUUIDFromBytes(("contraband:" + player.getUUID() + ":" + fingerprint + ":" + now
                + ":" + ordinal).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Takes the listed items. Destructive and documented as such: there is no recovery ledger for a
     * confiscation, because the existing escrow returns property to its owner and the owner is the
     * person it was taken from.
     *
     * <p>Top-level stacks only. Emptying a shulker box would mean rewriting its block-entity NBT and
     * saving it back, which is a second inventory implementation living inside a search; a nested item
     * still produces the charge, it simply is not taken.
     */
    private static void confiscate(ServerLevel level, ServerPlayer player, Component guardName,
                                   List<ContrabandProbe> listed, Options options) {
        Set<ResourceLocation> ids = new LinkedHashSet<>();
        for (ContrabandProbe probe : listed) {
            if (probe.itemId() != null && probe.slot() != ContrabandProbe.ContrabandSlot.NESTED) {
                ids.add(probe.itemId());
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        Inventory inventory = player.getInventory();
        List<String> taken = new ArrayList<>();
        removeFrom(inventory.items, ids, taken);
        if (options.includeEquipped()) {
            removeFrom(inventory.armor, ids, taken);
        }
        if (options.includeOffhand()) {
            removeFrom(inventory.offhand, ids, taken);
        }
        if (taken.isEmpty()) {
            return;
        }
        McaCrime.LOGGER.info("MCA: Crime confiscated {} from {} in {}", taken, player.getGameProfile().getName(),
                level.dimension().location());
        player.sendSystemMessage(Component.translatable("mcacrime.msg.contraband.confiscated", guardName,
                Component.literal(String.join(", ", taken))));
        player.getInventory().setChanged();
    }

    private static void removeFrom(List<ItemStack> stacks, Set<ResourceLocation> ids, List<String> taken) {
        for (int i = 0; i < stacks.size(); i++) {
            ItemStack stack = stacks.get(i);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (id != null && ids.contains(id)) {
                taken.add(stack.getCount() + "x " + id);
                stacks.set(i, ItemStack.EMPTY);
            }
        }
    }
}
