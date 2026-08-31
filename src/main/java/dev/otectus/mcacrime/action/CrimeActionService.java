package dev.otectus.mcacrime.action;

import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.action.handler.ApologizeActionHandler;
import dev.otectus.mcacrime.action.handler.MugActionHandler;
import dev.otectus.mcacrime.action.handler.RansomActionHandler;
import dev.otectus.mcacrime.action.handler.ReleaseCaptiveActionHandler;
import dev.otectus.mcacrime.action.handler.RestrainActionHandler;
import dev.otectus.mcacrime.captivity.CustodyRecord;
import dev.otectus.mcacrime.captivity.CustodyRegistry;
import dev.otectus.mcacrime.captivity.CustodyReleaseReason;
import dev.otectus.mcacrime.captivity.CustodyService;
import dev.otectus.mcacrime.economy.FineService;
import dev.otectus.mcacrime.economy.SurrenderService;
import dev.otectus.mcacrime.ransom.RansomService;
import dev.otectus.mcacrime.network.ActionMenuS2CPacket;
import dev.otectus.mcacrime.network.ActionMenuEntry;
import dev.otectus.mcacrime.network.CrimeNetwork;
import dev.otectus.mcacrime.network.StartActionC2SPacket;
import net.minecraftforge.network.PacketDistributor;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Shared server-authoritative entry point used by commands now and UI packets later. */
public final class CrimeActionService {
    private static boolean bootstrapped;
    private static final Map<UUID, ActionMenuSession> MENUS = new ConcurrentHashMap<>();
    private static final long MENU_TTL = 200L;

    private CrimeActionService() {}

    public static synchronized void bootstrap() {
        if (bootstrapped) return;
        ActionHandlerRegistry.register(CrimeActionIds.MUG, new MugActionHandler());
        ActionHandlerRegistry.register(CrimeActionIds.RESTRAIN, new RestrainActionHandler());
        ActionHandlerRegistry.register(CrimeActionIds.APOLOGIZE, new ApologizeActionHandler());
        ActionHandlerRegistry.register(CrimeActionIds.RANSOM, new RansomActionHandler());
        ActionHandlerRegistry.register(CrimeActionIds.RELEASE_CAPTIVE, new ReleaseCaptiveActionHandler());
        bootstrapped = true;
    }

    public static ActionResult startTargeted(ServerPlayer actor, net.minecraft.resources.ResourceLocation action,
                                             UUID targetId, UUID nonce) {
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
        ActionResult result = handler.start(actor, target, level, nonce);
        // Timed actions remember their nonce when their session finishes. Immediate and capture-channel
        // actions do not own an ActionSession, so remember their result here as the replay backstop.
        if (ActionSessionManager.forActor(actor.getUUID()).isEmpty()) {
            ActionSessionManager.remember(actor.getUUID(), nonce, result);
        }
        return result;
    }

    public static int startMugFromCommand(ServerPlayer actor) {
        if (!McaCrimeConfig.COMMON.allowGameplayCommandFallback.get()) {
            actor.sendSystemMessage(net.minecraft.network.chat.Component.translatable("mcacrime.action.commands_disabled"));
            return 0;
        }
        LivingEntity target = rayTraceLiving(actor, 4.0D);
        if (target == null) {
            actor.sendSystemMessage(net.minecraft.network.chat.Component.translatable("mcacrime.mug.notarget"));
            return 0;
        }
        ActionResult result = startTargeted(actor, CrimeActionIds.MUG, target.getUUID(), UUID.randomUUID());
        if (!result.accepted()) actor.sendSystemMessage(net.minecraft.network.chat.Component.translatable(result.code()));
        return result.accepted() ? 1 : 0;
    }

    public static boolean openMenu(ServerPlayer actor, UUID targetId) {
        bootstrap();
        if (!(actor.level() instanceof ServerLevel level)) return false;
        Entity entity = level.getEntity(targetId);
        if (!(entity instanceof LivingEntity target) || actor.distanceToSqr(target) > 16.0D
                || !actor.hasLineOfSight(target)) return false;
        long now = level.getGameTime();
        List<ActionMenuEntry> actions = new ArrayList<>();
        addMenuEntry(actions, CrimeActionIds.MUG, "gui.mcacrime.action.mug", actor, target, level, now, true);
        addMenuEntry(actions, CrimeActionIds.RESTRAIN, "gui.mcacrime.action.restrain", actor, target, level, now, true);
        addMenuEntry(actions, CrimeActionIds.APOLOGIZE, "gui.mcacrime.action.apologize", actor, target, level, now, true);
        addMenuEntry(actions, CrimeActionIds.RANSOM, "gui.mcacrime.action.ransom", actor, target, level, now, false);
        addMenuEntry(actions, CrimeActionIds.RELEASE_CAPTIVE, "gui.mcacrime.action.release_captive",
                actor, target, level, now, false);
        ActionMenuSession menu = new ActionMenuSession(UUID.randomUUID(), actor.getUUID(), targetId,
                level.dimension().location(), 1, now + MENU_TTL);
        MENUS.put(actor.getUUID(), menu);
        CrimeNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> actor), new ActionMenuS2CPacket(
                menu.id(), menu.revision(), targetId, actions));
        return true;
    }

    private static void addMenuEntry(List<ActionMenuEntry> entries, net.minecraft.resources.ResourceLocation actionId,
                                     String labelKey, ServerPlayer actor, LivingEntity target, ServerLevel level,
                                     long now, boolean showWhenHidden) {
        CrimeActionHandler handler = ActionHandlerRegistry.get(actionId);
        if (handler == null) return;
        ActionAvailability availability = handler.evaluate(actor, target, level, now);
        if (availability.status() == ActionAvailability.Status.HIDDEN && !showWhenHidden) return;
        entries.add(new ActionMenuEntry(actionId, labelKey, availability.isAvailable(), availability.reason()));
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
            actor.sendSystemMessage(net.minecraft.network.chat.Component.translatable("mcacrime.action.stale_menu"));
            return ActionResult.rejected("mcacrime.action.stale_menu");
        }
        ActionResult result = startTargeted(actor, request.actionId(), request.targetId(), request.nonce());
        if (!result.accepted() && !"mcacrime.action.feedback_sent".equals(result.code())) {
            actor.sendSystemMessage(net.minecraft.network.chat.Component.translatable(result.code()));
        }
        return result;
    }

    public static int settleCase(ServerPlayer player) { return FineService.payFine(player); }
    public static int surrender(ServerPlayer player) { return SurrenderService.surrender(player); }
    public static int demandRansom(ServerPlayer player) { return RansomService.demand(player); }
    public static int payRansom(ServerPlayer player) { return RansomService.pay(player); }

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
        player.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                released == 0 ? "mcacrime.release.none" : "mcacrime.release.all", released));
        return released;
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
