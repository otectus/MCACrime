package dev.otectus.mcacrime.action;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.action.handler.ApologizeActionHandler;
import dev.otectus.mcacrime.action.handler.EscapeActionHandler;
import dev.otectus.mcacrime.action.handler.FenceTradeActionHandler;
import dev.otectus.mcacrime.action.handler.MugActionHandler;
import dev.otectus.mcacrime.action.handler.PayRansomActionHandler;
import dev.otectus.mcacrime.action.handler.RansomActionHandler;
import dev.otectus.mcacrime.action.handler.ReleaseCaptiveActionHandler;
import dev.otectus.mcacrime.action.handler.RescueActionHandler;
import dev.otectus.mcacrime.action.handler.BailActionHandler;
import dev.otectus.mcacrime.action.handler.RestrainActionHandler;
import dev.otectus.mcacrime.action.handler.SettleCaseActionHandler;
import dev.otectus.mcacrime.action.handler.SurrenderActionHandler;
import dev.otectus.mcacrime.captivity.CustodyRecord;
import dev.otectus.mcacrime.captivity.CustodyRegistry;
import dev.otectus.mcacrime.captivity.CustodyReleaseReason;
import dev.otectus.mcacrime.captivity.CustodyService;
import dev.otectus.mcacrime.item.weapon.WeaponDetector;
import dev.otectus.mcacrime.job.CriminalJob;
import dev.otectus.mcacrime.job.WorldCriminalJobService;
import dev.otectus.mcacrime.ransom.RansomService;
import dev.otectus.mcacrime.network.ActionMenuS2CPacket;
import dev.otectus.mcacrime.network.ActionMenuEntry;
import dev.otectus.mcacrime.network.CrimeNetwork;
import dev.otectus.mcacrime.network.StartActionC2SPacket;
import dev.otectus.mcacrime.util.CrimeDebug;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import org.jetbrains.annotations.Nullable;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The single server-authoritative entry point for every gameplay action, whether it arrived from the
 * MCA interaction screen, a world interaction, the captive panel, or a {@code /crime} fallback.
 *
 * <p>Spec §26's Phase 1 gate is that mug, restrain, escape, fine, surrender and ransom all enter here
 * and that no direct command-only mutation remains. The command adapters below therefore do no work of
 * their own: they resolve a target and hand over, so a command can never gain a bonus, skip a lock, or
 * miss a cooldown that the UI path applies.
 */
public final class CrimeActionService {
    private static boolean bootstrapped;
    private static final Map<UUID, ActionMenuSession> MENUS = new ConcurrentHashMap<>();
    private static final long MENU_TTL = 200L;

    /** Actions offered against a villager, in the order the screen groups them. */
    private static final List<ResourceLocation> VILLAGER_MENU = List.of(
            CrimeActionIds.MUG,
            CrimeActionIds.RESTRAIN,
            CrimeActionIds.RANSOM,
            CrimeActionIds.RELEASE_CAPTIVE,
            CrimeActionIds.RESCUE,
            CrimeActionIds.FENCE_TRADE,
            CrimeActionIds.APOLOGIZE);

    /** Actions offered to a player about their own situation — the captive panel and the player card. */
    private static final List<ResourceLocation> SELF_MENU = List.of(
            CrimeActionIds.ESCAPE,
            CrimeActionIds.SURRENDER,
            CrimeActionIds.SETTLE_CASE,
            CrimeActionIds.BAIL,
            CrimeActionIds.PAY_RANSOM);

    private CrimeActionService() {}

    public static synchronized void bootstrap() {
        if (bootstrapped) return;
        ActionHandlerRegistry.register(CrimeActionIds.MUG, new MugActionHandler());
        ActionHandlerRegistry.register(CrimeActionIds.RESTRAIN, new RestrainActionHandler());
        ActionHandlerRegistry.register(CrimeActionIds.APOLOGIZE, new ApologizeActionHandler());
        ActionHandlerRegistry.register(CrimeActionIds.RANSOM, new RansomActionHandler());
        ActionHandlerRegistry.register(CrimeActionIds.RELEASE_CAPTIVE, new ReleaseCaptiveActionHandler());
        ActionHandlerRegistry.register(CrimeActionIds.ESCAPE, new EscapeActionHandler());
        ActionHandlerRegistry.register(CrimeActionIds.SURRENDER, new SurrenderActionHandler());
        ActionHandlerRegistry.register(CrimeActionIds.SETTLE_CASE, new SettleCaseActionHandler());
        ActionHandlerRegistry.register(CrimeActionIds.PAY_RANSOM, new PayRansomActionHandler());
        ActionHandlerRegistry.register(CrimeActionIds.RESCUE, new RescueActionHandler());
        ActionHandlerRegistry.register(CrimeActionIds.BAIL, new BailActionHandler());
        ActionHandlerRegistry.register(CrimeActionIds.FENCE_TRADE, new FenceTradeActionHandler());
        bootstrapped = true;
    }

    public static ActionResult startTargeted(ServerPlayer actor, ResourceLocation action, UUID targetId, UUID nonce) {
        bootstrap();
        if (!(actor.level() instanceof ServerLevel level)) return ActionResult.rejected("mcacrime.action.server_only");
        ActionResult replay = ActionSessionManager.replay(actor.getUUID(), nonce).orElse(null);
        if (replay != null) return replay;
        ActionSession active = ActionSessionManager.forActor(actor.getUUID()).orElse(null);
        if (active != null && active.requestNonce().equals(nonce)) {
            return ActionResult.accepted("mcacrime.action.in_progress");
        }
        Entity entity = level.getEntity(targetId);
        if (!(entity instanceof LivingEntity target)) return ActionResult.rejected("mcacrime.action.target_gone");
        CrimeActionHandler handler = ActionHandlerRegistry.get(action);
        if (handler == null) return ActionResult.rejected("mcacrime.action.unknown");
        ActionResult result = handler.start(new PlayerActor(actor), target, level, nonce);
        // Timed actions remember their nonce when their session finishes. Immediate and capture-channel
        // actions do not own an ActionSession, so remember their result here as the replay backstop.
        if (ActionSessionManager.forActor(actor.getUUID()).isEmpty()) {
            ActionSessionManager.remember(actor.getUUID(), nonce, result);
        }
        return result;
    }

    /**
     * Starts an action the player performs on themself. Routed through {@link #startTargeted} with the
     * actor as its own target so escape, surrender, fines and ransom payment inherit the same replay
     * cache, the same conflict check and the same handler contract as a targeted action — which is the
     * whole point of the Phase 1 gate.
     */
    public static ActionResult startSelf(ServerPlayer actor, ResourceLocation action, UUID nonce) {
        return startTargeted(actor, action, actor.getUUID(), nonce);
    }

    public static int startMugFromCommand(ServerPlayer actor) {
        if (!commandFallbackAllowed(actor)) return 0;
        LivingEntity target = rayTraceLiving(actor, 4.0D);
        if (target == null) {
            actor.sendSystemMessage(Component.translatable("mcacrime.mug.notarget"));
            return 0;
        }
        return reportCommandResult(actor, startTargeted(actor, CrimeActionIds.MUG, target.getUUID(), UUID.randomUUID()));
    }

    /** Command adapter for every self action. Identical validation, timing, locks, and cooldowns. */
    public static int startSelfFromCommand(ServerPlayer actor, ResourceLocation action) {
        if (!commandFallbackAllowed(actor)) return 0;
        return reportCommandResult(actor, startSelf(actor, action, UUID.randomUUID()));
    }

    private static boolean commandFallbackAllowed(ServerPlayer actor) {
        if (McaCrimeConfig.COMMON.allowGameplayCommandFallback.get()) return true;
        actor.sendSystemMessage(Component.translatable("mcacrime.action.commands_disabled"));
        return false;
    }

    /**
     * Reports a command result, staying silent when the handler already explained itself. A service
     * that has messaged the player returns {@code feedback_sent} precisely so the command layer does
     * not print a second, vaguer line on top of the specific one.
     */
    private static int reportCommandResult(ServerPlayer actor, ActionResult result) {
        if (!result.accepted() && !"mcacrime.action.feedback_sent".equals(result.code())) {
            actor.sendSystemMessage(Component.translatable(result.code()));
        }
        return result.accepted() ? 1 : 0;
    }

    public static boolean openMenu(ServerPlayer actor, UUID targetId) {
        bootstrap();
        if (!(actor.level() instanceof ServerLevel level)) return false;
        Entity entity = level.getEntity(targetId);
        if (!(entity instanceof LivingEntity target) || actor.distanceToSqr(target) > 16.0D
                || !actor.hasLineOfSight(target)) return false;
        // The same gate the Crime button greys itself out on, re-decided here because the button is
        // the client's opinion and this is the server's. An unarmed sender reaches a fence and nobody
        // else, and then only for the trade half of the menu.
        boolean unarmed = McaCrimeConfig.COMMON.requireWeaponForCrimeMenu.get()
                && WeaponDetector.drawnWeapon(actor).isEmpty();
        if (unarmed && !isFence(actor, targetId)) {
            // Silently: a reply would tell a probing client exactly which villager is a fence.
            CrimeDebug.crime("Menu request from {} refused: no weapon drawn and {} is not a fence",
                    actor.getGameProfile().getName(), targetId);
            return false;
        }
        return sendMenu(actor, level, target, targetId, VILLAGER_MENU, ActionMenuKind.VILLAGER, unarmed);
    }

    /**
     * Whether the persisted criminal job of this villager is FENCE.
     *
     * <p>Read from the world data rather than from anything the client sent: the client is told which
     * villagers are fences so the button can enable, and being told is not the same as being believed.
     */
    private static boolean isFence(ServerPlayer actor, UUID targetId) {
        MinecraftServer server = actor.getServer();
        if (server == null) return false;
        return WorldCriminalJobService.of(server).get(targetId) == CriminalJob.FENCE;
    }

    /**
     * Opens the menu a player has about their own situation. The target is the player, so the captive
     * panel and the player card go through the identical validation order as the villager menu.
     */
    public static boolean openSelfMenu(ServerPlayer actor, ActionMenuKind kind) {
        bootstrap();
        if (!(actor.level() instanceof ServerLevel level)) return false;
        return sendMenu(actor, level, actor, actor.getUUID(), SELF_MENU, kind, false);
    }

    /**
     * @param nonCoerciveOnly drops every coercive entry from the menu. Set for the unarmed sender the
     *         gate above let through to a fence: they may trade, and a mug row they could not start
     *         would only be a row that says no.
     */
    private static boolean sendMenu(ServerPlayer actor, ServerLevel level, LivingEntity target, UUID targetId,
                                    List<ResourceLocation> actionIds, ActionMenuKind kind,
                                    boolean nonCoerciveOnly) {
        long now = level.getGameTime();
        CrimeActor crimeActor = new PlayerActor(actor);
        List<ActionMenuEntry> actions = new ArrayList<>(actionIds.size());
        for (ResourceLocation actionId : actionIds) {
            if (nonCoerciveOnly && isCoercive(actionId)) continue;
            ActionMenuEntry entry = buildEntry(actionId, crimeActor, target, level, now);
            if (entry != null) actions.add(entry);
        }
        ActionMenuSession menu = new ActionMenuSession(UUID.randomUUID(), actor.getUUID(), targetId,
                level.dimension().location(), 1, now + MENU_TTL);
        MENUS.put(actor.getUUID(), menu);
        PacketDistributor.sendToPlayer(actor, new ActionMenuS2CPacket(
                menu.id(), menu.revision(), targetId, kind, describe(target), actions));
        return true;
    }

    /**
     * Builds one presentation row, or {@code null} when the action should not be shown at all.
     *
     * <p>A {@code HIDDEN} action is one the player has no business knowing exists for this target —
     * "Demand Ransom" against somebody you are not holding names a captive relationship that is not
     * yours. A {@code BLOCKED} action is one they can see but cannot use yet, and it carries the
     * reason, because "you cannot do this and here is why" teaches the rules and an absent row does not.
     */
    @Nullable
    private static ActionMenuEntry buildEntry(ResourceLocation actionId, CrimeActor actor, LivingEntity target,
                                              ServerLevel level, long now) {
        CrimeActionHandler handler = ActionHandlerRegistry.get(actionId);
        if (handler == null) return null;
        ActionAvailability availability = handler.evaluate(actor, target, level, now);
        if (availability.status() == ActionAvailability.Status.HIDDEN) return null;
        ActionDescriptor descriptor = handler.descriptor();
        return new ActionMenuEntry(actionId, descriptor.labelKey(), descriptor.descriptionKey(),
                descriptor.category(), descriptor.legality(), descriptor.duration(),
                descriptor.requirementKeys(), descriptor.hostile(),
                availability.isAvailable(), availability.reason());
    }

    /** Whether this action is one a weapon is the price of admission for. Unknown ids are not. */
    private static boolean isCoercive(ResourceLocation actionId) {
        CrimeActionHandler handler = ActionHandlerRegistry.get(actionId);
        return handler != null && handler.coercive();
    }

    /** The target header shown above the rows: who this is, and nothing quantitative about them. */
    private static Component describe(LivingEntity target) {
        return target.getDisplayName() == null ? Component.empty() : target.getDisplayName().copy();
    }

    public static ActionResult startFromMenu(ServerPlayer actor, StartActionC2SPacket request) {
        if (!(actor.level() instanceof ServerLevel level)) return ActionResult.rejected("mcacrime.action.server_only");
        ActionSession active = ActionSessionManager.forActor(actor.getUUID()).orElse(null);
        if (active != null && active.requestNonce().equals(request.nonce())) {
            return ActionResult.accepted("mcacrime.action.in_progress");
        }
        ActionMenuSession menu = MENUS.get(actor.getUUID());
        if (menu == null || !menu.valid(actor.getUUID(), request.targetId(), level.dimension().location(),
                request.menuId(), request.menuRevision(), level.getGameTime())) {
            actor.sendSystemMessage(Component.translatable("mcacrime.action.stale_menu"));
            return ActionResult.rejected("mcacrime.action.stale_menu");
        }
        // The weapon gate again, on the action rather than on the menu: a menu opened with a sword out
        // and started after it was put away is exactly the sequence a stale panel produces on its own.
        if (isCoercive(request.actionId()) && McaCrimeConfig.COMMON.requireWeaponForCrimeMenu.get()
                && WeaponDetector.drawnWeapon(actor).isEmpty()) {
            CrimeDebug.crime("Coercive action {} from {} refused: no weapon drawn",
                    request.actionId(), actor.getGameProfile().getName());
            actor.sendSystemMessage(Component.translatable("mcacrime.action.requires_weapon"));
            return ActionResult.rejected("mcacrime.action.requires_weapon");
        }
        ActionResult result = startTargeted(actor, request.actionId(), request.targetId(), request.nonce());
        if (!result.accepted() && !"mcacrime.action.feedback_sent".equals(result.code())) {
            actor.sendSystemMessage(Component.translatable(result.code()));
        }
        return result;
    }

    /**
     * Drops a player's open menu session. Called on logout: without it {@code MENUS} grows by one
     * dead entry per player who ever opened a menu and is never emptied for the life of the server.
     */
    public static void forgetMenu(UUID actor) {
        MENUS.remove(actor);
    }

    /** Player-facing recovery valve for any authoritative custody record, including legacy orphan records. */
    public static int releaseOwnedCaptives(ServerPlayer player) {
        if (player.getServer() == null) return 0;
        List<CustodyRecord> owned = List.copyOf(CustodyRegistry.byOwner(player.getServer(), player.getUUID()));
        int released = 0;
        for (CustodyRecord record : owned) {
            if (!record.isLawful()) {
                CustodyService.release(player.getServer(), record.getCaptive(), CustodyReleaseReason.RELEASED_BY_CAPTOR);
                released++;
            }
        }
        player.sendSystemMessage(Component.translatable(
                released == 0 ? "mcacrime.release.none" : "mcacrime.release.all", released));
        return released;
    }

    /**
     * Demanding a ransom names a specific captive, so it stays a targeted action. The command form
     * resolves the captive the caller actually owns rather than asking them to type a UUID.
     */
    public static int demandRansomFromCommand(ServerPlayer player) {
        if (!commandFallbackAllowed(player)) return 0;
        return RansomService.demand(player);
    }

    @Nullable
    public static LivingEntity rayTraceLiving(ServerPlayer player, double reach) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getViewVector(1.0F).scale(reach));
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(player.level(), player, eye, end,
                player.getBoundingBox().expandTowards(player.getViewVector(1.0F).scale(reach)).inflate(1.0D),
                entity -> entity instanceof LivingEntity && entity.isPickable() && entity != player,
                (float) (reach * reach));
        return hit != null && hit.getEntity() instanceof LivingEntity living ? living : null;
    }
}
