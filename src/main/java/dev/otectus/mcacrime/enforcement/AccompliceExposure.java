package dev.otectus.mcacrime.enforcement;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.api.event.PlayerJailedEvent;
import dev.otectus.mcacrime.compat.McaCompat;
import dev.otectus.mcacrime.crime.type.CrimeIds;
import dev.otectus.mcacrime.crime.type.CrimeTypeRegistry;
import dev.otectus.mcacrime.detect.WitnessChecker;
import dev.otectus.mcacrime.incident.IncidentService;
import dev.otectus.mcacrime.ledger.CrimeFlag;
import dev.otectus.mcacrime.state.world.AccompliceRecord;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import dev.otectus.mcacrime.state.world.ServerMutationGate;
import dev.otectus.mcacrime.util.CrimeDebug;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

/**
 * How a helpful relative becomes a wanted one.
 *
 * <p>Two ways in, and only two. Somebody saw them do it — the same perception rules that decide
 * whether a player was witnessed, run against the villagers who are neither loyal to the player nor
 * helping them. Or the player they were helping was taken, and {@code implicateOnPrincipalArrest}
 * says the help comes out under questioning. Both end in the same place: one charge of
 * {@code mcacrime:aiding_a_criminal} against the villager, and an open incident that the guards'
 * existing pursuit scan will find on its own.
 *
 * <p>Guards are not given a second way to notice criminals for this. {@code GuardEnforcement}
 * already walks {@link ActiveIncidentRegistry} every scan and sends the nearest responder after any
 * offender it can see, villager or player; opening the incident is what puts the accomplice into that
 * loop, and the pursuit, the arrest and the escort are then the code that already existed.
 */
@Mod.EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class AccompliceExposure {

    private AccompliceExposure() {
    }

    /**
     * The decision, with no world attached: is this agreement blown?
     *
     * <p>Pure so the rule can be asserted directly. The interesting case is the last one — a relative
     * who is already wanted is not exposed a second time, because a second warrant is not a second
     * consequence and would re-announce an arrest the player has already been told about.
     */
    public static boolean exposed(boolean alreadyWanted, boolean sawAct, boolean principalArrested,
                                  boolean implicateOnPrincipalArrest) {
        if (alreadyWanted) {
            return false;
        }
        return sawAct || (principalArrested && implicateOnPrincipalArrest);
    }

    /**
     * Runs the perception check after an accomplice has just done something, and charges them if
     * anybody unsympathetic saw it.
     *
     * @return true when this call is what made them wanted
     */
    public static boolean onEffect(ServerLevel level, ServerPlayer player, LivingEntity accomplice,
                                   AccompliceRecord record) {
        if (level == null || accomplice == null || record == null || record.wanted()) {
            return false;
        }
        if (!exposed(false, sawAct(level, player, accomplice), false, false)) {
            return false;
        }
        return charge(level, accomplice, record, "witness");
    }

    /**
     * The principal went to jail while a relative was still helping them. Everyone with an active
     * agreement to help this player is implicated at once.
     */
    @SubscribeEvent
    public static void onPlayerJailed(PlayerJailedEvent event) {
        ServerPlayer player = event.getPlayer();
        if (player == null || !(player.level() instanceof ServerLevel level)
                || !McaCrimeConfig.COMMON.enableAccomplices.get()
                || !McaCrimeConfig.COMMON.implicateOnPrincipalArrest.get()) {
            return;
        }
        implicate(level, player.getUUID());
    }

    /** Makes every relative currently helping {@code playerId} wanted. Returns how many were charged. */
    public static int implicate(ServerLevel level, UUID playerId) {
        MinecraftServer server = level == null ? null : level.getServer();
        if (server == null || !ServerMutationGate.allows(server)) {
            return 0;
        }
        long now = level.getGameTime();
        int charged = 0;
        for (AccompliceRecord record : CrimeWorldData.get(server).accomplices()) {
            if (!record.player().equals(playerId) || !record.active(now)) {
                continue;
            }
            if (!exposed(record.wanted(), false, true,
                    McaCrimeConfig.COMMON.implicateOnPrincipalArrest.get())) {
                continue;
            }
            if (level.getEntity(record.villager()) instanceof LivingEntity accomplice
                    && charge(level, accomplice, record, "principal_arrest")) {
                charged++;
            } else {
                // Not loaded: the warrant still stands, there is simply nobody to open an incident
                // against yet. It is opened when a guard next finds them through the ordinary path.
                CrimeWorldData.get(server).putAccomplice(record.asWanted());
            }
        }
        return charged;
    }

    /** Whether any villager who is neither loyal nor an accomplice saw what this one just did. */
    private static boolean sawAct(ServerLevel level, ServerPlayer player, LivingEntity accomplice) {
        var type = CrimeTypeRegistry.getOrBuiltin(CrimeIds.AIDING_A_CRIMINAL).orElse(null);
        if (type == null) {
            return false;
        }
        double radius = type.awareness().visualRadius()
                * McaCrimeConfig.COMMON.visualWitnessRadiusMultiplier.get();
        AABB box = accomplice.getBoundingBox().inflate(radius);
        CrimeWorldData data = CrimeWorldData.get(level.getServer());
        // The offender's own loyal relatives are excluded by the same predicate that keeps them out of
        // the witness set for the crime itself (no victim: aiding has none); another accomplice is
        // excluded because they are in on it.
        java.util.function.Predicate<UUID> loyal = WitnessChecker.loyaltyPredicate(level, player, null);
        List<LivingEntity> nearby = level.getEntitiesOfClass(LivingEntity.class, box,
                e -> e != accomplice && e != player && McaCompat.isMcaVillager(e)
                        && data.accomplice(e.getUUID()) == null
                        && (loyal == null || !loyal.test(e.getUUID())));
        for (LivingEntity observer : nearby) {
            if (WitnessChecker.perceive(observer, accomplice, accomplice, type.awareness()).sawAct()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Charges the accomplice and opens the incident the guards read.
     *
     * <p>There is no victim: aiding is an offence against the community's rules rather than against a
     * person, and naming the player as the victim of their own accomplice would be a lie the ledger
     * then reports back to them.
     */
    private static boolean charge(ServerLevel level, LivingEntity accomplice, AccompliceRecord record,
                                  String detection) {
        MinecraftServer server = level.getServer();
        if (server == null || !ServerMutationGate.allows(server)) {
            return false;
        }
        UUID incidentId = UUID.randomUUID();
        EnumSet<CrimeFlag> flags = EnumSet.of(CrimeFlag.NPC_OFFENDER, CrimeFlag.CAUGHT_IN_ACT);
        if (IncidentService.commitNpc(incidentId, accomplice, CrimeIds.AIDING_A_CRIMINAL, null, level,
                detection, flags).isEmpty()) {
            return false;
        }
        CrimeWorldData.get(server).putAccomplice(record.asWanted());
        ActiveIncidentRegistry.open(new ActiveIncidentRegistry.ActiveIncident(incidentId,
                accomplice.getUUID(), null, level.dimension(), level.getGameTime(), flags,
                ActiveIncidentRegistry.Phase.COMMITTED));
        CrimeDebug.crime("accomplice {} is wanted for aiding {} ({})", accomplice.getUUID(),
                record.player(), detection);
        ServerPlayer principal = server.getPlayerList().getPlayer(record.player());
        if (principal != null) {
            principal.sendSystemMessage(Component.translatable("mcacrime.msg.accomplice.exposed",
                    McaCompat.getVillagerDisplayName(accomplice)));
        }
        return true;
    }
}
