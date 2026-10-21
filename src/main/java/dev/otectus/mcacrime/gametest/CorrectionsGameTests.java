package dev.otectus.mcacrime.gametest;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.block.CrimeBlocks;
import dev.otectus.mcacrime.captivity.CustodyOwner;
import dev.otectus.mcacrime.captivity.CustodyReleaseReason;
import dev.otectus.mcacrime.captivity.CustodyService;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.crime.type.CrimeIds;
import dev.otectus.mcacrime.detect.DamageIncidentService;
import dev.otectus.mcacrime.economy.Currencies;
import dev.otectus.mcacrime.engine.CrimeState;
import dev.otectus.mcacrime.enforcement.ArrestPhase;
import dev.otectus.mcacrime.enforcement.ArrestStates;
import dev.otectus.mcacrime.enforcement.ChallengeResponse;
import dev.otectus.mcacrime.enforcement.GuardChallengeService;
import dev.otectus.mcacrime.enforcement.GuardEnforcement;
import dev.otectus.mcacrime.enforcement.LawHold;
import dev.otectus.mcacrime.frisk.FriskRefusal;
import dev.otectus.mcacrime.frisk.FriskRevision;
import dev.otectus.mcacrime.frisk.FriskSession;
import dev.otectus.mcacrime.frisk.FriskSlotRef;
import dev.otectus.mcacrime.frisk.FriskTransaction;
import dev.otectus.mcacrime.frisk.FriskingService;
import dev.otectus.mcacrime.frisk.InventoryProviders;
import dev.otectus.mcacrime.frisk.StolenGoodsInventoryProvider;
import dev.otectus.mcacrime.jail.HoldingCell;
import dev.otectus.mcacrime.jail.HoldingCellService;
import dev.otectus.mcacrime.jail.JailAnchor;
import dev.otectus.mcacrime.jail.JailRecovery;
import dev.otectus.mcacrime.jail.JailRegion;
import dev.otectus.mcacrime.jail.JailService;
import dev.otectus.mcacrime.jail.JailState;
import dev.otectus.mcacrime.jail.ReleaseReason;
import dev.otectus.mcacrime.job.CriminalProfessions;
import dev.otectus.mcacrime.job.NpcMuggerEligibility;
import dev.otectus.mcacrime.job.NpcMuggerEligibilityReason;
import dev.otectus.mcacrime.job.OccupationRequest;
import dev.otectus.mcacrime.job.OccupationSource;
import dev.otectus.mcacrime.job.OccupationStatus;
import dev.otectus.mcacrime.job.OccupationTransitionResult;
import dev.otectus.mcacrime.job.WorldCriminalJobService;
import dev.otectus.mcacrime.justice.ThiefCombatPolicy;
import dev.otectus.mcacrime.ledger.CrimeCaseService;
import dev.otectus.mcacrime.ledger.CrimeContext;
import dev.otectus.mcacrime.ledger.CrimeRecord;
import dev.otectus.mcacrime.ledger.Resolution;
import dev.otectus.mcacrime.ledger.SentenceAssignmentService;
import dev.otectus.mcacrime.mug.npc.MuggingThreats;
import dev.otectus.mcacrime.mug.npc.NpcMugAbortReason;
import dev.otectus.mcacrime.mug.npc.NpcMuggingService;
import dev.otectus.mcacrime.mug.npc.StolenGoodsRecovery;
import dev.otectus.mcacrime.restraint.SessionCancelCause;
import dev.otectus.mcacrime.state.CrimeAttachments;
import dev.otectus.mcacrime.state.PlayerCrimeData;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.PropertyLot;
import dev.otectus.mcacrime.state.world.StolenGoodsRecord;
import dev.otectus.mcacrime.state.world.WorksiteRef;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.Property;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Runtime regressions for the 2026-10-04 corrections (0.7.7), against real MCA on a real server.
 *
 * <p>One test per behaviour the report named: the cell door's state definition carrying whatever is
 * added to every door; only a canonical adult Thief stealing; a mugging victim fighting back without
 * being charged; stolen property recovered from a living thief through the search screen; MCA guards
 * actually damaging what they are told to fight and keeping their own fights; and sentences that end
 * without leaving the prisoner a legal target or stuck.
 *
 * <p>The guard tests run in batches of their own: a guard with its brain running answers whatever is
 * in reach, and a neighbouring test's mob or Wanted player would otherwise be a fair target. Crime
 * records are a permanent ledger with no removal, so every test works on players and villagers of its
 * own and asserts only about them.
 */
@GameTestHolder(McaCrime.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CorrectionsGameTests {

    private static final BlockPos STATION = new BlockPos(6, 1, 6);

    private CorrectionsGameTests() {
    }

    // ------------------------------------------------------------------ 1. startup / cell door

    /**
     * The cell door and the reinforced bars carry every property, and every default, of the vanilla
     * block they extend (0.7.7). Before, they listed the vanilla properties themselves, so a property a
     * mod injects into every door -- Dramatic Doors' waterlogged -- was missing, and that mod's
     * constructor hook threw during block registration.
     */
    @GameTest(template = "cell_parity")
    public static void theCellDoorAndBarsKeepEveryPropertyTheirParentsHave(GameTestHelper helper) {
        assertSuperset(helper, CrimeBlocks.CELL_DOOR.get(), Blocks.IRON_DOOR, "cell door");
        assertSuperset(helper, CrimeBlocks.REINFORCED_BARS.get(), Blocks.IRON_BARS, "reinforced bars");
        helper.succeed();
    }

    private static void assertSuperset(GameTestHelper helper, Block ours, Block parent, String what) {
        for (Property<?> property : parent.getStateDefinition().getProperties()) {
            Property<?> mine = ours.getStateDefinition().getProperty(property.getName());
            helper.assertTrue(mine != null, what + " lacks the " + property.getName() + " property");
            Object ourDefault = ours.defaultBlockState().getValue(mine);
            Object parentDefault = parent.defaultBlockState().getValue(property);
            helper.assertTrue(Objects.equals(ourDefault, parentDefault), what + " defaults " + property.getName()
                    + " to " + ourDefault + " where its parent defaults to " + parentDefault);
        }
    }

    // ------------------------------------------------------------------ 4. only a canonical thief steals

    /**
     * The actor matrix (0.7.7): a valid Thief may start a mugging; the same villager made a guard may
     * not, and one made a nitwit by somebody else mid-mugging -- a stale THIEF record -- has the
     * session ended before anything is taken and cannot start another. The lifecycle then retires the
     * stale record without overwriting the profession it was given.
     */
    @GameTest(template = "cell_parity", timeoutTicks = 200)
    public static void onlyACanonicalThiefMayStartOrFinishAMugging(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer victim = player(helper, new ArrayList<>(), 5);
        AutoCloseable online = online(helper, victim);
        Mob thief = thief(helper);
        WorldCriminalJobService jobs = WorldCriminalJobService.of(level.getServer());
        ResourceLocation nitwit = ResourceLocation.withDefaultNamespace("nitwit");
        try {
            victim.getInventory().setItem(12, new ItemStack(Items.EMERALD, 64));
            helper.assertTrue(execution(jobs, thief).eligible(),
                    "A committed adult Thief may not act: " + jobs.diagnose(thief).executionDetail());

            McaCompat.setVillagerProfession(thief, ResourceLocation.fromNamespaceAndPath("mca", "guard"));
            helper.assertTrue(execution(jobs, thief).reason() == NpcMuggerEligibilityReason.RESPONDER,
                    "A guard holding a Thief record may act: " + execution(jobs, thief).reason());
            helper.assertTrue(NpcMuggingService.begin(level, thief, victim).isEmpty(), "A guard started a mugging");
            McaCompat.setVillagerProfession(thief, CriminalProfessions.THIEF_ID);
            helper.assertTrue(execution(jobs, thief).eligible(), "Restoring the Thief profession did not restore it");

            helper.assertTrue(NpcMuggingService.begin(level, thief, victim).isPresent(),
                    "A valid Thief could not start a mugging");
            McaCompat.setVillagerProfession(thief, nitwit);
            helper.assertTrue(execution(jobs, thief).reason() == NpcMuggerEligibilityReason.NOT_A_THIEF,
                    "A nitwit with a stale Thief record may act: " + execution(jobs, thief).reason());
            String detail = jobs.diagnose(thief).executionDetail();
            helper.assertTrue(detail.contains("minecraft:nitwit"), "The diagnosis does not name the nitwit: " + detail);
            NpcMuggingService.tick(level.getServer());
            helper.assertTrue(NpcMuggingService.sessionForThief(thief.getUUID()).isEmpty(),
                    "The mugging survived its actor becoming a nitwit");
            helper.assertTrue(victim.getInventory().getItem(12).getCount() == 64, "Something was taken");
            helper.assertTrue(CrimeWorldData.get(level.getServer()).stolenGoodsByThief(thief.getUUID()).isEmpty(),
                    "A theft was filed");
            helper.assertTrue(NpcMuggingService.begin(level, thief, victim).isEmpty(), "A nitwit started a mugging");
        } catch (RuntimeException | Error failure) {
            cleanUp(level, thief, online);
            throw failure;
        }
        helper.runAfterDelay(45, () -> {
            try {
                helper.assertTrue(jobs.occupationStatus(thief.getUUID()) == OccupationStatus.RETIRED,
                        "The stale Thief record was not retired: " + jobs.occupationStatus(thief.getUUID()));
                helper.assertTrue(nitwit.equals(McaCompat.getProfessionId(thief).orElse(null)),
                        "Retirement overwrote the profession somebody else gave them: "
                                + McaCompat.getProfessionId(thief));
                helper.succeed();
            } finally {
                cleanUp(level, thief, online);
            }
        });
    }

    /** A child holding a Thief record and the Thief profession still may not rob anybody (0.7.7). */
    @GameTest(template = "cell_parity", timeoutTicks = 100)
    public static void aChildWithAThiefRecordCannotMug(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer victim = player(helper, new ArrayList<>(), 5);
        AutoCloseable online = online(helper, victim);
        Mob thief = thief(helper);
        WorldCriminalJobService jobs = WorldCriminalJobService.of(level.getServer());
        try {
            ((Villager) thief).setAge(-24000);
            helper.assertTrue(execution(jobs, thief).reason() == NpcMuggerEligibilityReason.NOT_ADULT,
                    "A child Thief may act: " + execution(jobs, thief).reason());
            helper.assertTrue(NpcMuggingService.begin(level, thief, victim).isEmpty(), "A child started a mugging");
            helper.succeed();
        } finally {
            cleanUp(level, thief, online);
        }
    }

    private static NpcMuggerEligibility.Result execution(WorldCriminalJobService jobs, Mob thief) {
        return jobs.evaluate(thief, NpcMuggerEligibility.Context.EXECUTION);
    }

    // ------------------------------------------------------------------ 3. defending against a mugger

    /**
     * The victim of a mugging may hit the mugger before being hurt (0.7.7). Under NORMAL_LAW -- where a
     * Thief has no special status, so only the threat can explain it -- the punch ends the mugging and is
     * charged nothing, the right to answer the threat outlives the session, killing the mugger is still
     * charged as excessive defensive force, and an innocent villager hit the same way is assaulted.
     */
    @GameTest(template = "cell_parity", timeoutTicks = 100)
    public static void aMuggingVictimMayHitTheMuggerFirstButNotKillUnderNormalLaw(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer victim = player(helper, new ArrayList<>(), 5);
        AutoCloseable online = online(helper, victim);
        Mob thief = thief(helper);
        Mob innocent = villagerAt(helper, new BlockPos(8, 1, 8));
        ModConfigSpec.EnumValue<ThiefCombatPolicy> policy = McaCrimeConfig.COMMON.thiefCombatPolicy;
        ModConfigSpec.BooleanValue forgiveness = McaCrimeConfig.COMMON.enableMinorHarmForgiveness;
        ThiefCombatPolicy policyBefore = policy.get();
        boolean forgivenessBefore = forgiveness.get();
        policy.set(ThiefCombatPolicy.NORMAL_LAW);
        forgiveness.set(false);
        // thiefCombatPolicy is a world-rule-mapped setting, resolved once per tick.
        dev.otectus.mcacrime.config.CrimeWorldSettings.invalidate();
        CrimeWorldData data = CrimeWorldData.get(level.getServer());
        try {
            helper.assertTrue(NpcMuggingService.begin(level, thief, victim).isPresent(), "The mugging did not start");
            helper.assertTrue(thief.hurt(level.damageSources().playerAttack(victim), 1F), "The punch did no damage");
            helper.assertTrue(NpcMuggingService.sessionFor(victim.getUUID()).isEmpty(),
                    "The victim's punch did not interrupt the mugging");
            DamageIncidentService.flush(level.getServer());
            helper.assertTrue(data.recordsForOffender(victim.getUUID()).isEmpty(),
                    "Fighting off a mugger was charged: " + data.recordsForOffender(victim.getUUID()));
            helper.assertTrue(CrimeState.getHeat(victim) == 0L, "Fighting off a mugger cost Heat");
            helper.assertTrue(MuggingThreats.threatens(thief.getUUID(), victim.getUUID(), level.getGameTime(),
                    McaCrimeConfig.COMMON.thiefDefenseGraceTicks.get()), "The right to answer the threat ended with it");

            thief.hurt(level.damageSources().playerAttack(victim), 1000F);
            DamageIncidentService.flush(level.getServer());
            List<CrimeRecord> killing = data.recordsForOffender(victim.getUUID());
            helper.assertTrue(killing.size() == 1, "Killing the mugger under NORMAL_LAW was not charged once: " + killing);
            helper.assertTrue("excessive_defensive_force".equals(killing.get(0).context().get(CrimeContext.COMBAT_BASIS)),
                    "The killing was not judged excessive defensive force: " + killing.get(0).context());

            helper.assertTrue(innocent.hurt(level.damageSources().playerAttack(victim), 1F), "The innocent took no damage");
            DamageIncidentService.flush(level.getServer());
            helper.assertTrue(data.recordsForOffender(victim.getUUID()).size() == 2,
                    "Hitting an innocent villager was not charged: " + data.recordsForOffender(victim.getUUID()));
            helper.succeed();
        } finally {
            policy.set(policyBefore);
            forgiveness.set(forgivenessBefore);
            dev.otectus.mcacrime.config.CrimeWorldSettings.invalidate();
            CrimeState.setHeat(victim, 0);
            innocent.discard();
            cleanUp(level, thief, online);
        }
    }

    /** A mugger already in custody is a prisoner, not a threat: hitting them is charged (0.7.7). */
    @GameTest(template = "cell_parity", timeoutTicks = 100)
    public static void aMuggerInCustodyIsProtected(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer victim = player(helper, new ArrayList<>(), 5);
        AutoCloseable online = online(helper, victim);
        Mob thief = thief(helper);
        ModConfigSpec.BooleanValue forgiveness = McaCrimeConfig.COMMON.enableMinorHarmForgiveness;
        boolean forgivenessBefore = forgiveness.get();
        forgiveness.set(false);
        CrimeWorldData data = CrimeWorldData.get(level.getServer());
        try {
            helper.assertTrue(NpcMuggingService.begin(level, thief, victim).isPresent(), "The mugging did not start");
            NpcMuggingService.abort(victim.getUUID(), NpcMugAbortReason.GUARD_INTERVENTION);
            helper.assertTrue(CustodyService.captureLawful(data, thief.getUUID(), false,
                    CustodyOwner.guard(UUID.randomUUID()), level.getGameTime(), thief.blockPosition(),
                    level.dimension().location()).ok(), "The mugger could not be taken into custody");
            thief.hurt(level.damageSources().playerAttack(victim), 1F);
            DamageIncidentService.flush(level.getServer());
            helper.assertTrue(!data.recordsForOffender(victim.getUUID()).isEmpty(),
                    "Hitting a mugger in custody was treated as self-defence");
            helper.succeed();
        } finally {
            forgiveness.set(forgivenessBefore);
            CrimeState.setHeat(victim, 0);
            CustodyService.release(level.getServer(), thief.getUUID(), CustodyReleaseReason.ADMIN);
            cleanUp(level, thief, online);
        }
    }

    // ------------------------------------------------------------------ 2. stolen property is searchable

    /**
     * A victim searching a thief sees their own stolen property first and takes it back exactly as it was
     * taken, with no theft filed; a replay moves nothing; another victim's item taken by them is a new
     * theft that still names that victim as the owner; their own purse comes back as currency and the
     * other victim's purse cannot be taken at all (0.7.7).
     */
    @GameTest(template = "cell_parity", timeoutTicks = 200)
    public static void aVictimRecoversTheirStolenSwordFromALivingThief(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        HolderLookup.Provider registries = level.registryAccess();
        ServerPlayer victim = player(helper, new ArrayList<>(), 5);
        AutoCloseable online = online(helper, victim);
        floor(helper);
        Mob thief = villagerAt(helper, new BlockPos(4, 1, 4));
        UUID other = UUID.randomUUID();
        CrimeWorldData data = CrimeWorldData.get(level.getServer());
        ModConfigSpec.BooleanValue restraint = McaCrimeConfig.COMMON.friskRequiresArmRestraint;
        boolean restraintBefore = restraint.get();
        restraint.set(false);
        Runnable tidy = () -> {
            restraint.set(restraintBefore);
            data.stolenGoodsByThief(thief.getUUID()).forEach(record -> data.removeStolenGoods(record.transactionId()));
            data.stolenGoodsByThief(victim.getUUID()).forEach(record -> data.removeStolenGoods(record.transactionId()));
            FriskingService.closeAll(victim, SessionCancelCause.CANCELLED);
            CrimeState.setHeat(victim, 0);
            thief.discard();
            close(online);
        };
        ItemStack sword = new ItemStack(Items.IRON_SWORD);
        sword.enchant(registries.lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS), 3);
        sword.set(DataComponents.CUSTOM_NAME, Component.literal("Grandfather's sword"));
        sword.setDamageValue(17);
        Predicate<ItemStack> isSword = stack -> ItemStack.isSameItemSameComponents(stack, sword);
        long now = level.getGameTime();
        UUID swordTx = UUID.randomUUID();
        UUID breadTx = UUID.randomUUID();
        UUID purseTx = UUID.randomUUID();
        UUID otherPurseTx = UUID.randomUUID();
        String provider = Currencies.active().id().toString();
        try {
            data.putStolenGoods(StolenGoodsRecord.ofStack(registries, breadTx, thief.getUUID(), other,
                    new ItemStack(Items.BREAD, 5), 0L, now - 30));
            data.putStolenGoods(StolenGoodsRecord.ofStack(registries, swordTx, thief.getUUID(), victim.getUUID(),
                    sword.copy(), 0L, now - 20));
            data.putStolenGoods(new StolenGoodsRecord(purseTx, thief.getUUID(), victim.getUUID(), null, 12L,
                    now - 10, provider));
            data.putStolenGoods(new StolenGoodsRecord(otherPurseTx, thief.getUUID(), other, null, 7L, now - 5,
                    provider));

            helper.assertTrue(FriskingService.open(victim, thief), "The search did not open");
            FriskSession session = FriskingService.sessionFor(victim);
            helper.assertTrue(session != null, "No search session");
            List<FriskSlotRef> view = session.view();
            helper.assertTrue(view.size() >= 4, "The stolen property is not in the search: " + view);
            helper.assertTrue(victim.getUUID().equals(StolenGoodsInventoryProvider.ownerOf(thief, view.get(0)))
                            && victim.getUUID().equals(StolenGoodsInventoryProvider.ownerOf(thief, view.get(1))),
                    "The searcher's own property is not first: " + view);

            int swordIndex = indexOf(thief, view, swordTx);
            int swordRevision = FriskRevision.of(InventoryProviders.peek(thief, view.get(swordIndex)));
            FriskingService.transfer(victim, session.id(), swordIndex, swordRevision, 1);
            helper.assertTrue(data.stolenGoods(swordTx) == null, "The recovered sword is still in the ledger");
            helper.assertTrue(count(victim, isSword) == 1, "The sword did not come back exactly as it was taken");
            helper.assertTrue(data.stolenGoodsByThief(victim.getUUID()).isEmpty(), "Recovering it was filed as theft");
            FriskingService.transfer(victim, session.id(), swordIndex, swordRevision, 1);
            helper.assertTrue(count(victim, isSword) == 1, "A replayed recovery duplicated the sword");
        } catch (RuntimeException | Error failure) {
            tidy.run();
            throw failure;
        }

        helper.runAfterDelay(8, () -> {
            try {
                FriskSession session = FriskingService.sessionFor(victim);
                List<FriskSlotRef> view = session.view();
                int breadIndex = indexOf(thief, view, breadTx);
                FriskingService.transfer(victim, session.id(), breadIndex,
                        FriskRevision.of(InventoryProviders.peek(thief, view.get(breadIndex))), 2);
                StolenGoodsRecord rest = data.stolenGoods(breadTx);
                helper.assertTrue(rest != null && rest.stack(registries).getCount() == 3,
                        "Taking two of five breads left " + rest);
                List<StolenGoodsRecord> refiled = data.stolenGoodsByThief(victim.getUUID());
                helper.assertTrue(refiled.size() == 1 && other.equals(refiled.get(0).owner()),
                        "Taking another victim's property did not stay that victim's: " + refiled);
                // 0.7.9: the two breads are in the searcher's inventory, so their provenance must never
                // become a second, claimable copy; the three left with the thief still recover once.
                helper.assertTrue(!refiled.get(0).recoverable(), "Re-theft recorded an extra claimable copy");
                CrimeWorldData expiryData = new CrimeWorldData();
                expiryData.putStolenGoods(refiled.get(0));
                expiryData.putStolenGoods(rest);
                expiryData = CrimeWorldData.load(expiryData.save(new net.minecraft.nbt.CompoundTag(), registries),
                        registries);
                helper.assertTrue(dev.otectus.mcacrime.mug.npc.StolenGoodsLedger.expire(expiryData,
                        now / 24000L + 7, 7, java.util.Set.of()) == 2,
                        "Re-theft provenance and the thief's remainder did not expire");
                helper.assertTrue(count(victim, stack -> stack.is(Items.BREAD)) == 2, "The physical re-theft item was lost");
                helper.assertTrue(expiryData.propertyEscrowFor(other).size() == 1
                                && expiryData.propertyEscrowFor(other).get(0).stack(registries).getCount() == 3,
                        "Expiry duplicated the two breads already held by the player");

                CrimeWorldData seizureData = new CrimeWorldData();
                var seized = dev.otectus.mcacrime.frisk.SeizureLedger.criminal(seizureData, registries,
                        UUID.randomUUID(), victim.getUUID(), other, now, () -> new ItemStack(Items.DIAMOND));
                helper.assertTrue(seized.committed(), "A criminal seizure was refused");
                helper.assertTrue(dev.otectus.mcacrime.mug.npc.StolenGoodsLedger.expire(seizureData,
                                now / 24000L + 7, 7, java.util.Set.of()) == 1
                                && seizureData.propertyEscrowFor(other).isEmpty(),
                        "An ordinary criminal seizure minted a recovery copy");
            } catch (RuntimeException | Error failure) {
                tidy.run();
                throw failure;
            }
            helper.runAfterDelay(8, () -> {
                try {
                    FriskSession session = FriskingService.sessionFor(victim);
                    List<FriskSlotRef> view = session.view();
                    int emeraldsBefore = count(victim, stack -> stack.is(Items.EMERALD));
                    int purseIndex = indexOf(thief, view, purseTx);
                    FriskingService.transfer(victim, session.id(), purseIndex,
                            FriskRevision.of(InventoryProviders.peek(thief, view.get(purseIndex))), 1);
                    helper.assertTrue(data.stolenGoods(purseTx) == null, "The purse is still with the thief");
                    helper.assertTrue(count(victim, stack -> stack.is(Items.EMERALD)) == emeraldsBefore + 12,
                            "The purse did not come back as its own currency");
                } catch (RuntimeException | Error failure) {
                    tidy.run();
                    throw failure;
                }
                helper.runAfterDelay(8, () -> {
                    try {
                        FriskSession session = FriskingService.sessionFor(victim);
                        int otherIndex = indexOf(thief, session.view(), otherPurseTx);
                        int revision = FriskRevision.of(InventoryProviders.peek(thief, session.view().get(otherIndex)));
                        var facts = FriskingService.facts(victim, session, otherIndex, revision, 1, true);
                        FriskTransaction.Result result = FriskTransaction.commit(victim, thief, session, otherIndex,
                                revision, 1, facts, data, level.getGameTime());
                        helper.assertTrue(result.refusal() == FriskRefusal.NOT_YOUR_PURSE,
                                "Somebody else's purse was taken: " + result.refusal());
                        helper.assertTrue(data.stolenGoods(otherPurseTx) != null, "The other purse moved");
                        helper.succeed();
                    } finally {
                        tidy.run();
                    }
                });
            });
        });
    }

    /**
     * 0.7.9: opening a schema-17 store classifies its theft rows by who holds the goods. An online
     * player's and a saved offline player's rows become inventory provenance; an NPC's row stays the
     * property's only backing store. Expiry then retires all three and escrows the NPC copy alone.
     *
     * <p>Swaps the server's own store for a scratch one, so it runs in a batch of its own.
     */
    @GameTest(template = "cell_parity", batch = "legacy_property_custody")
    public static void legacyPropertyCustodyIsResolvedFromPlayerIdentities(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        var server = level.getServer();
        HolderLookup.Provider registries = level.registryAccess();
        ServerPlayer online = player(helper, new ArrayList<>(), 5);
        AutoCloseable onlineFixture = online(helper, online);
        UUID saved = UUID.randomUUID();
        UUID npc = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        java.nio.file.Path playerData = server.getWorldPath(
                net.minecraft.world.level.storage.LevelResource.PLAYER_DATA_DIR);
        java.nio.file.Path savedFile = playerData.resolve(saved + ".dat");
        CrimeWorldData original = CrimeWorldData.get(server);
        try {
            java.nio.file.Files.createDirectories(playerData);
            net.minecraft.nbt.NbtIo.writeCompressed(new net.minecraft.nbt.CompoundTag(), savedFile);
            CrimeWorldData scratch = new CrimeWorldData();
            for (UUID thief : List.of(online.getUUID(), saved, npc)) {
                scratch.putStolenGoods(StolenGoodsRecord.ofStack(registries, UUID.randomUUID(), thief, owner,
                        new ItemStack(Items.DIAMOND), 0L, 0L));
            }
            net.minecraft.nbt.CompoundTag oldSave = scratch.save(new net.minecraft.nbt.CompoundTag(), registries);
            oldSave.putInt(dev.otectus.mcacrime.state.world.CrimeDataMigrations.TAG_SCHEMA,
                    dev.otectus.mcacrime.state.world.CrimeDataMigrations.SCHEMA_DECREES);
            for (var row : oldSave.getList("stolenGoods", net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                ((net.minecraft.nbt.CompoundTag) row).remove("custody");
            }
            server.overworld().getDataStorage().set(CrimeWorldData.DATA_NAME, CrimeWorldData.load(oldSave, registries));
            CrimeWorldData migrated = CrimeWorldData.get(server);
            helper.assertTrue(!migrated.stolenGoodsByThief(online.getUUID()).get(0).recoverable(),
                    "An online player's inventory became loot");
            helper.assertTrue(!migrated.stolenGoodsByThief(saved).get(0).recoverable(),
                    "A saved offline player's inventory became loot");
            helper.assertTrue(migrated.stolenGoodsByThief(npc).get(0).recoverable(), "NPC loot lost its backing store");
            helper.assertTrue(dev.otectus.mcacrime.mug.npc.StolenGoodsLedger.expire(migrated, 7, 7,
                    java.util.Set.of()) == 3, "The migrated rows did not expire");
            helper.assertTrue(migrated.propertyEscrowFor(owner).size() == 1, "Legacy inventory minted extra escrow");
        } finally {
            server.overworld().getDataStorage().set(CrimeWorldData.DATA_NAME, original);
            java.nio.file.Files.deleteIfExists(savedFile);
            close(onlineFixture);
            online.discard();
        }
        helper.succeed();
    }

    /**
     * Confirmed death escrows ledger property only for a thief who holds it in the ledger alone (0.7.7). A
     * player who took something carries it, so escrowing the record as well would duplicate it.
     */
    @GameTest(template = "cell_parity")
    public static void aPlayerThiefsDeathEscrowsNothingTheyCarry(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        HolderLookup.Provider registries = level.registryAccess();
        ServerPlayer playerThief = player(helper, new ArrayList<>(), 5);
        AutoCloseable online = online(helper, playerThief);
        floor(helper);
        Mob npcThief = villagerAt(helper, new BlockPos(8, 1, 8));
        CrimeWorldData data = CrimeWorldData.get(level.getServer());
        UUID owner = UUID.randomUUID();
        UUID playerTx = UUID.randomUUID();
        UUID npcTx = UUID.randomUUID();
        long now = level.getGameTime();
        try {
            data.putStolenGoods(StolenGoodsRecord.ofStack(registries, playerTx, playerThief.getUUID(), owner,
                    new ItemStack(Items.DIAMOND, 2), 0L, now));
            data.putStolenGoods(StolenGoodsRecord.ofStack(registries, npcTx, npcThief.getUUID(), owner,
                    new ItemStack(Items.EMERALD, 3), 0L, now));
            StolenGoodsRecovery.confirmedDeath(level, playerThief);
            helper.assertTrue(data.stolenGoods(playerTx) != null, "A player thief's death consumed the provenance record");
            helper.assertTrue(data.propertyEscrow().stream().noneMatch(lot -> owner.equals(lot.owner())),
                    "A player thief's death escrowed what they still carry");
            StolenGoodsRecovery.confirmedDeath(level, npcThief);
            helper.assertTrue(data.stolenGoods(npcTx) == null, "An NPC thief's death left its record in the ledger");
            helper.assertTrue(data.propertyEscrow().stream().filter(lot -> owner.equals(lot.owner())).count() == 1,
                    "An NPC thief's death did not escrow its property exactly once");
            helper.succeed();
        } finally {
            data.removeStolenGoods(playerTx);
            data.removeStolenGoods(npcTx);
            data.propertyEscrow().stream().filter(lot -> owner.equals(lot.owner()))
                    .map(PropertyLot::lotId).toList().forEach(data::removePropertyLot);
            npcThief.discard();
            close(online);
        }
    }

    private static int indexOf(Mob thief, List<FriskSlotRef> view, UUID transaction) {
        for (int i = 0; i < view.size(); i++) {
            StolenGoodsRecord record = StolenGoodsInventoryProvider.recordAt(thief, view.get(i));
            if (record != null && record.transactionId().equals(transaction)) {
                return i;
            }
        }
        throw new AssertionError("No slot for " + transaction + " in " + view);
    }

    private static int count(ServerPlayer player, Predicate<ItemStack> test) {
        int n = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!stack.isEmpty() && test.test(stack)) {
                n += stack.getCount();
            }
        }
        return n;
    }

    // ------------------------------------------------------------------ 5. guards fight

    /**
     * A real MCA guard damages a monster it is fighting, and a reaction ending does not take MCA's own
     * fight away from it (0.7.7: a release clears only targets this mod assigned).
     */
    @GameTest(template = "cell_parity", timeoutTicks = 400, batch = "mcacrime_guard_monster")
    public static void aGuardFightsAMonsterAndKeepsItsOwnFight(GameTestHelper helper) {
        floor(helper);
        Mob guard = guardAt(helper, new BlockPos(4, 1, 4));
        guard.setNoAi(false);
        Husk husk = helper.spawn(EntityType.HUSK, new BlockPos(6, 1, 4));
        husk.setNoAi(true);
        float full = husk.getHealth();
        guard.getBrain().setMemory(MemoryModuleType.ATTACK_TARGET, husk);
        McaCompat.releaseVillagerControl(guard);
        helper.assertTrue(guard.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).filter(t -> t == husk).isPresent(),
                "Releasing the guard took MCA's own fight away");
        helper.succeedWhen(() -> {
            helper.assertTrue(husk.getHealth() < full || husk.isDeadOrDying(),
                    "The guard has not damaged the husk (health " + husk.getHealth() + ")");
            guard.discard();
            husk.discard();
        });
    }

    /**
     * A Wanted player who refuses arrest is attacked, and actually hurt, by a real MCA guard (0.7.7). The
     * player is added to the level, so the guard's own senses see them as they would a real one.
     */
    @GameTest(template = "cell_parity", timeoutTicks = 600, batch = "mcacrime_guard_force")
    public static void aGuardHurtsAWantedPlayerWhoRefusedArrest(GameTestHelper helper) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        Mob guard = guardAt(helper, new BlockPos(4, 1, 4));
        ServerPlayer outlaw = player(helper, new ArrayList<>(), 6);
        level.addNewPlayer(outlaw);
        AutoCloseable online = online(helper, outlaw);
        Runnable tidy = () -> {
            GuardChallengeService.forget(outlaw.getUUID());
            ArrestStates.clear(outlaw);
            GuardEnforcement.forget(outlaw.getUUID());
            LawHold.clear(guard.getUUID());
            CrimeState.setHeat(outlaw, 0);
            close(online);
            level.removePlayerImmediately(outlaw, Entity.RemovalReason.DISCARDED);
            guard.discard();
        };
        try {
            CrimeState.setHeat(outlaw, 100);
            helper.assertTrue(CrimeState.isWanted(outlaw), "The fixture is not Wanted");
            scan(helper);
            var challenge = GuardChallengeService.open(outlaw.getUUID());
            helper.assertTrue(challenge != null, "The guard did not confront the Wanted player");
            GuardChallengeService.menuDisplayed(outlaw, challenge.encounterId());
            GuardChallengeService.respond(outlaw, challenge.encounterId(), ChallengeResponse.REFUSE);
            helper.assertTrue(GuardChallengeService.forcePermitted(outlaw, level.getGameTime()),
                    "Refusing arrest did not permit force");
            guard.setNoAi(false);
            scan(helper);
            helper.assertTrue(guard.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET).filter(t -> t == outlaw).isPresent(),
                    "The guard was not told to fight: " + guard.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET));
        } catch (RuntimeException | Error failure) {
            tidy.run();
            throw failure;
        }
        float full = outlaw.getHealth();
        helper.succeedWhen(() -> {
            helper.assertTrue(outlaw.getHealth() < full, "No guard has hurt the player yet (health "
                    + outlaw.getHealth() + ", target " + guard.getBrain().getMemory(MemoryModuleType.ATTACK_TARGET) + ")");
            tidy.run();
        });
    }

    private static void scan(GameTestHelper helper) {
        ServerTickEvent.Post event = new ServerTickEvent.Post(() -> true, helper.getLevel().getServer());
        for (int tick = 0; tick < McaCrimeConfig.COMMON.guardScanIntervalTicks.get(); tick++) {
            GuardEnforcement.onServerTick(event);
        }
    }

    // ------------------------------------------------------------------ 6. sentences end cleanly

    /**
     * Serving a sentence settles the Heat it was for and no more (0.7.7): the prisoner walks out no longer
     * Wanted, Heat from a crime committed in the cell survives, and decay while serving is not taken twice.
     */
    @GameTest(template = "cell_parity", timeoutTicks = 100)
    public static void servingASentenceSettlesItsHeatButNotTheCellsOwnCrimes(GameTestHelper helper) {
        floor(helper);
        ServerPlayer prisoner = player(helper, new ArrayList<>(), 6);
        AutoCloseable online = online(helper, prisoner);
        try {
            CrimeState.setHeat(prisoner, 120);
            helper.assertTrue(JailService.jail(prisoner, 600, anchor(helper)), "The prisoner could not be jailed");
            JailState jail = CrimeAttachments.get(prisoner).getJail();
            helper.assertTrue(jail.getSentenceHeat() == 120L, "The obligation is " + jail.getSentenceHeat());
            CrimeState.addHeat(prisoner, -30);   // decay while serving
            CrimeState.addHeat(prisoner, 20);    // a crime in the cell
            helper.assertTrue(jail.getSentenceHeat() == 90L, "Decay did not come off the obligation first: "
                    + jail.getSentenceHeat());
            JailService.release(prisoner, ReleaseReason.SENTENCE_SERVED);
            helper.assertTrue(CrimeState.getHeat(prisoner) == 20L,
                    "Release left " + CrimeState.getHeat(prisoner) + " Heat instead of the cell's own 20");
            helper.assertTrue(!CrimeState.isWanted(prisoner), "The released prisoner is still Wanted");
            helper.assertTrue(!JailService.isJailed(prisoner), "Still jailed");
            JailService.release(prisoner, ReleaseReason.SENTENCE_SERVED);
            helper.assertTrue(CrimeState.getHeat(prisoner) == 20L, "A second release settled Heat again");
            helper.succeed();
        } finally {
            if (JailService.isJailed(prisoner)) {
                JailService.release(prisoner, ReleaseReason.ADMIN);
            }
            CrimeState.setHeat(prisoner, 0);
            ArrestStates.clear(prisoner);
            close(online);
        }
    }

    /**
     * The captivity cap serves the sentence's cases (0.7.7), an operator's release forgives nothing and
     * holds the guards off, and a pardon of every charge ends the sentence on the jail clock's next second.
     */
    @GameTest(template = "cell_parity", timeoutTicks = 100)
    public static void theCapServesAdminReleaseForgivesNothingAndAPardonFrees(GameTestHelper helper) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        ServerPlayer prisoner = player(helper, new ArrayList<>(), 6);
        AutoCloseable online = online(helper, prisoner);
        CrimeWorldData data = CrimeWorldData.get(level.getServer());
        try {
            CrimeRecord capped = charge(prisoner.getUUID());
            UUID sentence = arrest(helper, data, prisoner, capped);
            helper.assertTrue(JailService.jail(prisoner, 600, anchor(helper), sentence, false), "First jailing failed");
            JailService.release(prisoner, ReleaseReason.CAPTIVITY_CAP);
            helper.assertTrue(data.recordById(capped.id()).map(CrimeRecord::resolution).orElse(null) == Resolution.SERVED,
                    "The captivity cap left its case open: " + data.recordById(capped.id()).map(CrimeRecord::resolution));
            helper.assertTrue(data.getCustody(prisoner.getUUID()) == null, "The cap left the custody open");

            CrimeRecord kept = charge(prisoner.getUUID());
            UUID second = arrest(helper, data, prisoner, kept);
            CrimeState.setHeat(prisoner, 80);
            helper.assertTrue(JailService.jail(prisoner, 600, anchor(helper), second, false), "Second jailing failed");
            JailService.release(prisoner, ReleaseReason.ADMIN);
            CrimeRecord after = data.recordById(kept.id()).orElseThrow();
            helper.assertTrue(after.actionable() && after.sentenceId() == null,
                    "An operator's release served or kept binding the case: " + after.resolution() + " "
                            + after.sentenceId());
            helper.assertTrue(CrimeState.getHeat(prisoner) == 80L, "An operator's release forgave Heat");
            helper.assertTrue(ArrestStates.phaseOf(prisoner) == ArrestPhase.RECOVERY,
                    "The guards are not held off after an operator's release: " + ArrestStates.phaseOf(prisoner));
            ArrestStates.clear(prisoner);

            CrimeRecord pardoned = charge(prisoner.getUUID());
            UUID third = arrest(helper, data, prisoner, pardoned);
            helper.assertTrue(JailService.jail(prisoner, 6000, anchor(helper), third, false), "Third jailing failed");
            CrimeCaseService.resolve(level.getServer(), pardoned.id(), Resolution.PARDONED,
                    McaCrime.id("gametest_pardon"), "pardon:" + pardoned.id(), null, Map.of(), true);
            helper.assertTrue(JailRecovery.inspect(prisoner).contains(JailRecovery.Finding.RESOLVED_ELSEWHERE),
                    "A fully pardoned sentence is not recognised as over: " + JailRecovery.inspect(prisoner));
            // The jail clock is driven per player tick; this fixture has no connection ticking it.
            PlayerCrimeData crime = CrimeAttachments.get(prisoner);
            for (int tick = 0; tick < 40 && JailService.isJailed(prisoner); tick++) {
                crime.incrementOnlineTicks();
                JailService.tick(prisoner, crime);
            }
            helper.assertTrue(!JailService.isJailed(prisoner), "A pardoned prisoner is still serving");
            helper.assertTrue(data.getCustody(prisoner.getUUID()) == null, "The pardon left the custody open");
            helper.succeed();
        } finally {
            if (JailService.isJailed(prisoner)) {
                JailService.release(prisoner, ReleaseReason.ADMIN);
            }
            CrimeState.setHeat(prisoner, 0);
            ArrestStates.clear(prisoner);
            close(online);
        }
    }

    /**
     * A built cell whose door side is walled off still lets its prisoner out (0.7.7): the exit search
     * walks around the cell instead of leaving it standing around somebody the law has released.
     */
    @GameTest(template = "cell_parity")
    public static void aCellWithItsDoorWalledOffStillHasAWayOut(GameTestHelper helper) {
        floor(helper);
        ServerLevel level = helper.getLevel();
        BlockPos anchor = helper.absolutePos(new BlockPos(6, 1, 6));
        ResourceLocation dim = level.dimension().location();
        HoldingCell cell = new HoldingCell(UUID.randomUUID(), UUID.randomUUID(), anchor, dim, 2,
                level.getGameTime(), Map.of(), Map.of()).withDoor(anchor.east(2), Direction.EAST, null, null);
        BlockPos stand = cell.outsideStand();
        for (int dy = -1; dy <= 9; dy++) {
            level.setBlock(stand.above(dy), Blocks.STONE.defaultBlockState(), 3);
        }
        try {
            helper.assertTrue(!standable(level, stand), "The fixture did not wall the door side off");
            HoldingCellService.Exit exit = HoldingCellService.exitFor(level.getServer(), level, cell, true);
            helper.assertTrue(exit != null, "A walled-off door side left the prisoner no way out");
            helper.assertTrue(!JailRegion.contains(anchor, 2, dim, exit.pos(), exit.level().dimension().location()),
                    "The way out is inside the cell: " + exit.pos());
            helper.succeed();
        } finally {
            for (int dy = -1; dy <= 9; dy++) {
                level.setBlock(stand.above(dy), Blocks.AIR.defaultBlockState(), 3);
            }
        }
    }

    private static boolean standable(ServerLevel level, BlockPos stand) {
        return dev.otectus.mcacrime.jail.SafeCustodyDestination.validate(level, stand, 8).isPresent();
    }

    // ------------------------------------------------------------------ fixtures

    private static CrimeRecord charge(UUID offender) {
        return new CrimeRecord(UUID.randomUUID(), offender, null, CrimeIds.THEFT,
                OptionalInt.empty(), true, 10L, 40L, -20L, 0L, 600L, Resolution.UNRESOLVED);
    }

    private static UUID arrest(GameTestHelper helper, CrimeWorldData data, ServerPlayer offender, CrimeRecord charge) {
        ServerLevel level = helper.getLevel();
        JailAnchor anchor = anchor(helper);
        helper.assertTrue(CustodyService.captureLawful(data, offender.getUUID(), true,
                        CustodyOwner.jail(-1, anchor.pos(), level.dimension().location()),
                        level.getGameTime(), anchor.pos(), level.dimension().location()).ok(),
                "The offender could not be taken into lawful custody");
        data.addRecord(charge);
        UUID sentence = UUID.randomUUID();
        helper.assertTrue(SentenceAssignmentService.assign(data, offender.getUUID(), sentence, List.of(charge.id()),
                level.getGameTime()), "The sentence did not bind");
        return sentence;
    }

    private static JailAnchor anchor(GameTestHelper helper) {
        return new JailAnchor(helper.absolutePos(new BlockPos(6, 1, 6)), helper.getLevel().dimension().location(), 3);
    }

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 13; x++) {
            for (int z = 0; z < 13; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            }
        }
    }

    /** A committed, station-bound Thief, as the occupation transaction makes one. */
    private static Mob thief(GameTestHelper helper) {
        floor(helper);
        helper.setBlock(STATION, CrimeBlocks.MASK_STATION.get().defaultBlockState());
        Mob villager = villagerAt(helper, new BlockPos(5, 1, 5));
        ServerLevel level = helper.getLevel();
        OccupationTransitionResult result = WorldCriminalJobService.of(level.getServer()).requestThiefOccupation(
                OccupationRequest.station(villager.getUUID(), OccupationSource.STATION_RECRUITMENT,
                        WorksiteRef.of(level, helper.absolutePos(STATION)), false, false));
        helper.assertTrue(result.committed(), "The Thief fixture did not commit: " + result.reason());
        helper.assertTrue(CriminalProfessions.THIEF_ID.equals(McaCompat.getProfessionId(villager).orElse(null)),
                "The Thief fixture is not a Thief");
        return villager;
    }

    @SuppressWarnings("unchecked")
    private static Mob villagerAt(GameTestHelper helper, BlockPos at) {
        EntityType<? extends Mob> type = (EntityType<? extends Mob>) BuiltInRegistries.ENTITY_TYPE
                .getOptional(ResourceLocation.fromNamespaceAndPath("mca", "male_villager")).orElseThrow();
        Mob mob = helper.spawn(type, at);
        ((Villager) mob).setAge(0);
        mob.setNoAi(true);
        mob.setOnGround(true);
        helper.assertTrue(McaCompat.isMcaVillager(mob), "The fixture is not an MCA villager");
        return mob;
    }

    /** A real MCA guard, standing still until the test lets its brain run. */
    private static Mob guardAt(GameTestHelper helper, BlockPos at) {
        Mob guard = villagerAt(helper, at);
        helper.assertTrue(McaCompat.setVillagerProfession(guard, ResourceLocation.fromNamespaceAndPath("mca", "guard")),
                "The guard fixture could not be made a guard");
        helper.assertTrue(McaCompat.isGuard(guard), "The guard fixture is not a guard");
        return guard;
    }

    /**
     * A server player built rather than logged in: {@code placeNewPlayer} fires the login event, and MCA
     * answers it by opening its destiny screen on a connection this player does not have. Outgoing
     * packets are recorded in {@code sent} instead of written anywhere.
     */
    private static ServerPlayer player(GameTestHelper helper, List<Packet<?>> sent, int x) {
        ServerLevel level = helper.getLevel();
        GameProfile profile = new GameProfile(UUID.randomUUID(), "crime-077-test");
        ServerPlayer player = new ServerPlayer(level.getServer(), level, profile, ClientInformation.createDefault());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        // These fixtures stay online across ticks, and NeoForge's time sync asks every online player's
        // connection for its channel once a second. Nothing is ever written to this one.
        new EmbeddedChannel(connection);
        player.connection = new ServerGamePacketListenerImpl(level.getServer(), connection,
                player, CommonListenerCookie.createInitial(profile, false)) {
            @Override public void send(Packet<?> packet) { sent.add(packet); }
            @Override public void send(Packet<?> packet, PacketSendListener listener) { sent.add(packet); }
        };
        BlockPos pos = helper.absolutePos(new BlockPos(x, 1, 5));
        player.setPos(pos.getX() + .5, pos.getY(), pos.getZ() + .5);
        player.setGameMode(GameType.SURVIVAL);
        return player;
    }

    /** Only the fixture touches the login indexes; getPlayers() is an unmodifiable view in NeoForge. */
    @SuppressWarnings("unchecked")
    private static AutoCloseable online(GameTestHelper helper, ServerPlayer player) {
        try {
            PlayerList playerList = helper.getLevel().getServer().getPlayerList();
            var listField = PlayerList.class.getDeclaredField("players");
            var mapField = PlayerList.class.getDeclaredField("playersByUUID");
            listField.setAccessible(true);
            mapField.setAccessible(true);
            List<ServerPlayer> players = (List<ServerPlayer>) listField.get(playerList);
            Map<UUID, ServerPlayer> byId = (Map<UUID, ServerPlayer>) mapField.get(playerList);
            players.add(player);
            byId.put(player.getUUID(), player);
            return () -> {
                byId.remove(player.getUUID(), player);
                players.remove(player);
            };
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void close(AutoCloseable online) {
        try {
            online.close();
        } catch (Exception ignored) {
            // only the fixture's own bookkeeping
        }
    }

    private static void cleanUp(ServerLevel level, Mob villager, AutoCloseable online) {
        NpcMuggingService.clearAll();
        MuggingThreats.clear();
        WorldCriminalJobService.of(level.getServer()).retireOccupation(villager.getUUID(), OccupationSource.OPERATOR);
        CrimeWorldData.get(level.getServer()).removeCriminalVillager(villager.getUUID());
        villager.discard();
        close(online);
    }
}
