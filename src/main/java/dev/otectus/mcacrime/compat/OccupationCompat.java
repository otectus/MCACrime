package dev.otectus.mcacrime.compat;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.compat.mca.McaHandles;
import dev.otectus.mcacrime.job.HistoricalProfessionKind;
import dev.otectus.mcacrime.mixin.MerchantOffersAccessor;
import dev.otectus.mcacrime.state.world.WorksiteRef;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.entity.npc.VillagerProfession;
import net.minecraft.world.item.trading.MerchantOffers;

import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * The occupational surface of an MCA villager, expressed in vanilla types (0.7.2 §9.3).
 *
 * <h2>Why this is separate from {@link McaCompat}</h2>
 *
 * <p>{@code McaCompat} answers questions about MCA: is this one of its villagers, what is its
 * profession, who is its family. Almost nothing an occupation transaction has to write is an MCA
 * method at all — villager data, trading XP, brain memories, merchant offers and POI tickets are
 * <em>Minecraft's</em>, and MCA's villager extends {@code Villager} so a virtual call through the
 * vanilla supertype dispatches to MCA's overrides for free. Reflecting those through
 * {@link dev.otectus.mcacrime.compat.mca.McaBinding} would be worse than useless: they carry SRG names
 * in a production jar and could not be bound by their readable names anyway.
 *
 * <p>So every method here takes an {@link Entity} and begins with one checked
 * {@code instanceof Villager}. Only three operations genuinely need MCA — the profession setter, the
 * clothing string and the family-tree profession — and those still go through the binding.
 *
 * <h2>Nothing here throws</h2>
 *
 * <p>Same contract as {@code McaHandles}: a failure is a {@code false} or an empty {@link Optional},
 * never an exception escaping into a brain tick or a POI callback.
 */
public final class OccupationCompat {

    private OccupationCompat() {
    }

    /** The checked vanilla supertype, or null. Every write below starts here. */
    @Nullable
    private static Villager villager(@Nullable Entity entity) {
        return entity instanceof Villager v ? v : null;
    }

    /** True when this entity exposes the vanilla villager surface a transaction needs. */
    public static boolean isVanillaVillager(@Nullable Entity entity) {
        return entity instanceof Villager;
    }

    // --- snapshot --------------------------------------------------------------------------------

    /**
     * Captures everything the transaction may have to put back, before it changes anything.
     *
     * <p>Empty when the entity is not a villager at all. A villager whose clothing or family
     * profession could not be read still produces a snapshot, with the unreadable flags set: refusing
     * one outright would make a partially-bound MCA unable even to roll back.
     */
    public static Optional<OccupationSnapshot> capture(@Nullable Entity entity) {
        Villager villager = villager(entity);
        if (villager == null) {
            return Optional.empty();
        }
        try {
            Optional<String> clothes = McaHandles.clothes(entity);
            Optional<ResourceLocation> family = McaHandles.familyProfessionId(entity);
            ResourceLocation profession = McaCompat.getProfessionId(entity).orElse(null);
            HistoricalProfessionKind kind = kindOf(villager, profession);
            MerchantOffers offers = rawOffers(villager);
            return Optional.of(new OccupationSnapshot(
                    villager.getUUID(),
                    villager.getVillagerData(),
                    villager.getVillagerXp(),
                    WorksiteRef.of(memory(villager, MemoryModuleType.JOB_SITE)),
                    WorksiteRef.of(memory(villager, MemoryModuleType.POTENTIAL_JOB_SITE)),
                    offers != null,
                    offers == null ? null : offers.copy(),
                    kind,
                    kind == HistoricalProfessionKind.ID ? profession : null,
                    clothes.isPresent(),
                    clothes.orElse(null),
                    family.isPresent(),
                    family.orElse(null),
                    McaHandles.despawnDelay(entity)));
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA: Crime could not snapshot occupational state; refusing the transition", t);
            return Optional.empty();
        }
    }

    /**
     * What the captured profession is, told apart honestly.
     *
     * <p>MCA's reader is the authority, and it returning nothing is not the same as the villager being
     * unemployed: vanilla data still carries a profession, so a villager whose MCA read failed but
     * whose vanilla profession is something other than {@code minecraft:none} is recorded as
     * {@link HistoricalProfessionKind#UNREADABLE} and never rolled back to a guess.
     */
    private static HistoricalProfessionKind kindOf(Villager villager, @Nullable ResourceLocation read) {
        if (read != null) {
            return HistoricalProfessionKind.ID;
        }
        VillagerProfession vanilla = villager.getVillagerData().getProfession();
        return vanilla == null || vanilla == VillagerProfession.NONE
                ? HistoricalProfessionKind.NONE
                : HistoricalProfessionKind.UNREADABLE;
    }

    @Nullable
    private static GlobalPos memory(Villager villager, MemoryModuleType<GlobalPos> type) {
        return villager.getBrain().getMemory(type).orElse(null);
    }

    @Nullable
    private static MerchantOffers rawOffers(Villager villager) {
        return villager instanceof MerchantOffersAccessor accessor ? accessor.mcacrime$rawOffers() : null;
    }

    // --- mutation --------------------------------------------------------------------------------

    /**
     * Applies a profession through MCA's own setter and verifies it read back (spec §9.3 step 6).
     *
     * <p>The verification is the point. {@code McaHandles.setProfession} returning true only says the
     * handle did not throw; MCA's setter writes villager data, randomises clothing, rewrites the
     * family entry and refreshes the brain, so the only honest confirmation is asking again.
     */
    public static boolean applyProfessionVerified(@Nullable Entity entity, ResourceLocation professionId) {
        if (villager(entity) == null || professionId == null) {
            return false;
        }
        if (!BuiltInRegistries.VILLAGER_PROFESSION.containsKey(professionId)) {
            return false;
        }
        McaCompat.setVillagerProfession(entity, professionId);
        return professionId.equals(McaCompat.getProfessionId(entity).orElse(null));
    }

    /** Trading XP, read through the vanilla supertype. */
    public static int villagerXp(@Nullable Entity entity) {
        Villager villager = villager(entity);
        return villager == null ? 0 : villager.getVillagerXp();
    }

    /**
     * Raises trading XP to at least one, which is what keeps a committed Thief (spec §"reset").
     *
     * <p>MCA's {@code LoseUnimportantJobTask} and vanilla's {@code ResetProfession} both require
     * <em>zero</em> XP before they will take a profession away. One point is therefore the whole
     * protection, it costs a player nothing (a Thief has no trades), and it applies to novices too so
     * that native reset cannot bypass Crime's own grace period.
     */
    public static boolean applyXpFloor(@Nullable Entity entity) {
        Villager villager = villager(entity);
        if (villager == null) {
            return false;
        }
        villager.setVillagerXp(Math.max(1, villager.getVillagerXp()));
        return true;
    }

    public static boolean setVillagerXp(@Nullable Entity entity, int xp) {
        Villager villager = villager(entity);
        if (villager == null) {
            return false;
        }
        villager.setVillagerXp(xp);
        return true;
    }

    /**
     * Installs an empty trade list (spec §10.6: "must not retain the previous profession's offers").
     *
     * <p>Written through the field rather than {@code setOffers}, because the two differ for null and
     * this is the one place that difference matters.
     */
    public static boolean clearOffers(@Nullable Entity entity) {
        Villager villager = villager(entity);
        if (!(villager instanceof MerchantOffersAccessor accessor)) {
            return false;
        }
        accessor.mcacrime$setRawOffers(new MerchantOffers());
        return true;
    }

    /** Puts back exactly what the snapshot captured, null included. */
    public static boolean restoreOffers(@Nullable Entity entity, OccupationSnapshot snapshot) {
        Villager villager = villager(entity);
        if (!(villager instanceof MerchantOffersAccessor accessor) || snapshot == null) {
            return false;
        }
        if (!snapshot.offersPresent()) {
            accessor.mcacrime$setRawOffers(null);
            return true;
        }
        MerchantOffers captured = snapshot.offers();
        accessor.mcacrime$setRawOffers(captured == null ? new MerchantOffers() : captured.copy());
        return true;
    }

    /**
     * Clears the occupational memories a profession change invalidates.
     *
     * <p>Only these four. An old whole-brain object is never restored and unrelated combat, social or
     * home memories are never touched — that is the difference between detaching a job and resetting a
     * villager.
     */
    public static void clearOccupationalMemories(@Nullable Entity entity) {
        Villager villager = villager(entity);
        if (villager == null) {
            return;
        }
        var brain = villager.getBrain();
        brain.eraseMemory(MemoryModuleType.JOB_SITE);
        brain.eraseMemory(MemoryModuleType.POTENTIAL_JOB_SITE);
        brain.eraseMemory(MemoryModuleType.SECONDARY_JOB_SITE);
        brain.eraseMemory(MemoryModuleType.LAST_WORKED_AT_POI);
    }

    public static void setJobSite(@Nullable Entity entity, GlobalPos site) {
        Villager villager = villager(entity);
        if (villager != null && site != null) {
            villager.getBrain().setMemory(MemoryModuleType.JOB_SITE, site);
        }
    }

    public static Optional<GlobalPos> jobSite(@Nullable Entity entity) {
        Villager villager = villager(entity);
        return villager == null ? Optional.empty() : villager.getBrain().getMemory(MemoryModuleType.JOB_SITE);
    }

    public static Optional<GlobalPos> potentialJobSite(@Nullable Entity entity) {
        Villager villager = villager(entity);
        return villager == null
                ? Optional.empty()
                : villager.getBrain().getMemory(MemoryModuleType.POTENTIAL_JOB_SITE);
    }

    public static void erasePotentialJobSite(@Nullable Entity entity) {
        Villager villager = villager(entity);
        if (villager != null) {
            villager.getBrain().eraseMemory(MemoryModuleType.POTENTIAL_JOB_SITE);
        }
    }

    /** Rebuilds the brain for the current profession. Server-side only; MCA's setter has no guard. */
    public static void refreshBrain(@Nullable Entity entity, ServerLevel level) {
        Villager villager = villager(entity);
        if (villager != null && level != null) {
            villager.refreshBrain(level);
        }
    }

    // --- native POI tickets ----------------------------------------------------------------------

    /**
     * Takes the ticket at exactly this position, or empty when somebody else got there first.
     *
     * <p>A radius-one {@code take} rather than a search: the candidate was already chosen, and a
     * wider call could silently reserve a different station and report success.
     */
    public static Optional<BlockPos> takeExact(ServerLevel level, BlockPos pos,
                                               ResourceKey<PoiType> type) {
        if (level == null || pos == null || type == null) {
            return Optional.empty();
        }
        try {
            BlockPos target = pos.immutable();
            return level.getPoiManager().take(holder -> holder.is(type),
                    (holder, candidate) -> candidate.equals(target), target, 1);
        } catch (Throwable t) {
            McaCrime.LOGGER.debug("MCA: Crime could not reserve a POI ticket at {}", pos, t);
            return Optional.empty();
        }
    }

    /** Gives a ticket back. False when the position holds no POI, which is not an error here. */
    public static boolean releaseTicket(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return false;
        }
        try {
            return level.getPoiManager().release(pos.immutable());
        } catch (Throwable t) {
            // release throws IllegalStateException for a position with no POI at all -- a station
            // somebody broke between the reservation and the rollback, which is a normal race.
            McaCrime.LOGGER.debug("MCA: Crime could not release a POI ticket at {}", pos, t);
            return false;
        }
    }

    /** Whether a POI of this type still exists at the position. False when the chunk is not loaded. */
    public static boolean poiExists(ServerLevel level, BlockPos pos, ResourceKey<PoiType> type) {
        if (level == null || pos == null || type == null) {
            return false;
        }
        try {
            return level.getPoiManager().existsAtPosition(type, pos.immutable());
        } catch (Throwable t) {
            return false;
        }
    }

    /** Free tickets at a position, or -1 when it could not be asked. */
    public static int freeTickets(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) {
            return -1;
        }
        try {
            return level.getPoiManager().getFreeTickets(pos.immutable());
        } catch (Throwable t) {
            return -1;
        }
    }

    // --- rollback --------------------------------------------------------------------------------

    /**
     * Puts the captured occupational state back, and says whether it all went back.
     *
     * <p>Order matters. The profession is restored first, because MCA's setter randomises clothing and
     * rewrites the family entry on its way through, so restoring those before it would immediately be
     * undone. Potential-job-site memory is deliberately not restored here — the caller reinstates it
     * after any brain refresh, so that stopping {@code GoToPotentialJobSite} cannot release a
     * reservation the transaction still owns.
     *
     * @return true when every captured field was restored; false leaves the caller to suspend with a
     *         diagnostic rather than to pretend the villager is clean
     */
    public static boolean restore(@Nullable Entity entity, OccupationSnapshot snapshot,
                                  @Nullable ServerLevel level) {
        Villager villager = villager(entity);
        if (villager == null || snapshot == null) {
            return false;
        }
        boolean complete = true;
        try {
            if (snapshot.professionKind() == HistoricalProfessionKind.ID && snapshot.professionId() != null) {
                complete &= applyProfessionVerified(entity, snapshot.professionId());
            } else if (snapshot.professionKind() == HistoricalProfessionKind.NONE) {
                complete &= applyProfessionVerified(entity, ResourceLocation.fromNamespaceAndPath("minecraft", "none"));
            } else {
                complete = false; // UNREADABLE: never invent a profession to roll back to
            }
            VillagerData data = snapshot.villagerData();
            if (data != null) {
                villager.setVillagerData(data);
            } else {
                complete = false;
            }
            villager.setVillagerXp(snapshot.villagerXp());
            complete &= restoreOffers(entity, snapshot);
            if (snapshot.clothesReadable() && snapshot.clothes() != null) {
                complete &= McaHandles.setClothes(entity, snapshot.clothes());
            }
            if (snapshot.familyProfessionReadable() && snapshot.familyProfessionId() != null) {
                complete &= restoreFamilyProfession(entity, snapshot.familyProfessionId());
            }
            var brain = villager.getBrain();
            brain.eraseMemory(MemoryModuleType.POTENTIAL_JOB_SITE);
            GlobalPos jobSite = snapshot.jobSite() == null ? null : snapshot.jobSite().toGlobalPos(level);
            if (jobSite != null) {
                brain.setMemory(MemoryModuleType.JOB_SITE, jobSite);
            } else {
                brain.eraseMemory(MemoryModuleType.JOB_SITE);
            }
            if (level != null) {
                villager.refreshBrain(level);
            }
            GlobalPos potential = snapshot.potentialJobSite() == null
                    ? null : snapshot.potentialJobSite().toGlobalPos(level);
            if (potential != null) {
                brain.setMemory(MemoryModuleType.POTENTIAL_JOB_SITE, potential);
            }
        } catch (Throwable t) {
            McaCrime.LOGGER.warn("MCA: Crime could not fully roll back an occupation transition for {}",
                    snapshot.villager(), t);
            return false;
        }
        return complete;
    }

    private static boolean restoreFamilyProfession(Entity entity, ResourceLocation id) {
        VillagerProfession profession = BuiltInRegistries.VILLAGER_PROFESSION.get(id);
        return profession != null && McaHandles.setFamilyProfession(entity, profession);
    }

    /** The vanilla profession value for a registry id, for the family-tree write. */
    public static Optional<VillagerProfession> profession(ResourceLocation id) {
        return id == null ? Optional.empty() : BuiltInRegistries.VILLAGER_PROFESSION.getOptional(id);
    }

    /** Keeps MCA's family tree in step after a profession change this mod made itself. */
    public static boolean syncFamilyProfession(@Nullable Entity entity, ResourceLocation id) {
        return profession(id).map(p -> McaHandles.setFamilyProfession(entity, p)).orElse(false);
    }

    /** The thief-occupation capability members that did not bind, for the diagnostic. */
    public static List<String> missingCapability() {
        return McaHandles.thiefOccupationCapability();
    }

    /** Convenience for a {@link Holder} predicate over one POI key. */
    public static java.util.function.Predicate<Holder<PoiType>> isType(ResourceKey<PoiType> key) {
        return holder -> holder.is(key);
    }
}
