package dev.otectus.mcacrime;

import com.mojang.logging.LogUtils;
import dev.otectus.mcacrime.compat.LocksReforgedBridge;
import dev.otectus.mcacrime.compat.McaQuestsBridge;
import dev.otectus.mcacrime.compat.ReputationBridge;
import dev.otectus.mcacrime.action.CrimeActionService;
import dev.otectus.mcacrime.compat.mca.McaBinding;
import dev.otectus.mcacrime.config.ConfigValidator;
import dev.otectus.mcacrime.crime.type.CrimeTypeRegistry;
import dev.otectus.mcacrime.economy.Currencies;
import dev.otectus.mcacrime.item.CrimeItems;
import dev.otectus.mcacrime.job.CriminalProfessions;
import dev.otectus.mcacrime.job.WorldCriminalJobService;
import dev.otectus.mcacrime.network.CrimeNetwork;
import dev.otectus.mcacrime.state.CrimeAttachments;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.event.config.ModConfigEvent;
import org.slf4j.Logger;

import java.util.List;

/**
 * Main entrypoint for MCA: Crime — a server-authoritative village law-and-crime framework for
 * Minecraft Comes Alive: Reborn (spec §18, §19 Phase 1). Every MCA Reborn call is isolated behind
 * {@code dev.otectus.mcacrime.compat.McaCompat} and every Karma/Heat write goes through
 * {@code dev.otectus.mcacrime.engine.CrimeState}, so MCA drift and state corruption are both contained.
 */
@Mod(McaCrime.MOD_ID)
public final class McaCrime {

    public static final String MOD_ID = "mcacrime";
    public static final Logger LOGGER = LogUtils.getLogger();

    /** A {@link ResourceLocation} in this mod's namespace. */
    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    public McaCrime(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, McaCrimeConfig.COMMON_SPEC);
        container.registerConfig(ModConfig.Type.CLIENT, McaCrimeConfig.CLIENT_SPEC);

        modBus.addListener(this::onCommonSetup);
        // Entity selectors cache the compiled protected/responder lists. The cache notices a new list
        // instance on its own, but a reload that mutates the list in place would not change identity,
        // so the reload event drops it explicitly rather than relying on that.
        modBus.addListener(this::onConfigReload);
        modBus.addListener(this::onConfigLoading);
        CrimeAttachments.register(modBus);
        // Blocks before items: the Mask Station's BlockItem resolves the block it wraps, so the block
        // registry has to be attached first even though DeferredRegister defers both.
        dev.otectus.mcacrime.block.CrimeBlocks.register(modBus); // mask station (0.7.2 §6.1)
        CrimeItems.register(modBus); // restraints + masks + station item + creative tab (spec §8.3)
        dev.otectus.mcacrime.effect.CrimeEffects.register(modBus); // mcacrime:sand_blinded (0.7.2 §13.4)
        dev.otectus.mcacrime.entity.CrimeEntities.register(modBus); // thrown sand bottle (0.7.2 §13.2)
        // POI before professions: the thief profession's predicates name the mask-station POI key.
        dev.otectus.mcacrime.job.CrimePoiTypes.register(modBus); // mcacrime:mask_station (0.7.2 §10.1)
        CriminalProfessions.register(modBus); // thief/fence, presentation only (0.5.1)
        // The station's data contract, then the menu that reads it.
        dev.otectus.mcacrime.recipe.CrimeRecipes.register(modBus); // mcacrime:mask_making (0.7.2 §7)
        dev.otectus.mcacrime.menu.CrimeMenus.register(modBus); // mask station menu (0.7.2 §8.1)
        // Payload registration is a mod-bus listener, never a common-setup call: the registrar is
        // only open for the duration of the event.
        modBus.addListener(CrimeNetwork::register);

        LOGGER.info("MCA: Crime initialising (mod id '{}')", MOD_ID);
    }

    private void onConfigReload(net.neoforged.fml.event.config.ModConfigEvent.Reloading event) {
        migrateClientHud(event.getConfig());
        if (!MOD_ID.equals(event.getConfig().getModId())
                || event.getConfig().getSpec() != McaCrimeConfig.COMMON_SPEC) return;
        net.minecraft.server.MinecraftServer running =
                net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        Runnable refresh = () -> {
            ConfigValidator.validateCurrentConfig().forEach(problem ->
                    LOGGER.warn("MCA: Crime config reload: {}", problem));
            dev.otectus.mcacrime.detect.EntitySelectors.invalidate();
            dev.otectus.mcacrime.item.weapon.WeaponDetector.invalidate();
            // The contraband list is compiled once and read per search pass, so a reload has to drop it.
            // Tag entries are re-checked at the next TagsUpdatedEvent, which is the only time they can be.
            dev.otectus.mcacrime.enforcement.ContrabandPolicy.invalidate();
            // The active currency is chosen by an id, so a reload that renames it must take effect
            // before the next fine is charged rather than at the next restart.
            Currencies.reload();
            // Weapon lists are gated on server-side but shown client-side, so every connected client
            // is told the new policy now rather than at their next login.
            net.minecraft.server.MinecraftServer server =
                    net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                CrimeNetwork.broadcastWeaponPolicy(server);
                // Both criminal-job presentation keys are answered once per villager, so a reload that
                // flips one has to revisit the criminals that already exist rather than only the next.
                WorldCriminalJobService.of(server).refreshPresentation();
                WorldCriminalJobService.of(server).refreshBehaviors();
                // Fence stock is priced from the config and assembled from tags, so a reload that
                // retuned the default price has to reach the next screen that opens. Only with a
                // server running: tags do not exist before one does, and rebuilding against none
                // would empty every fence.
                dev.otectus.mcacrime.economy.fence.FenceGoodsRegistry.rebuild();
            }
        };
        if (running != null) running.execute(refresh);
        else refresh.run();
    }

    private void onConfigLoading(net.neoforged.fml.event.config.ModConfigEvent.Loading event) {
        migrateClientHud(event.getConfig());
    }

    private void migrateClientHud(ModConfig config) {
        if (!MOD_ID.equals(config.getModId()) || config.getSpec() != McaCrimeConfig.CLIENT_SPEC
                || McaCrimeConfig.CLIENT.hudLayoutVersion.get() >= 1) return;
        var client = McaCrimeConfig.CLIENT;
        client.hudAnchor.set(dev.otectus.mcacrime.client.hud.CrimeHudLayout.migratedAnchor(
                client.hudAnchor.get(), client.hudOffsetX.get(), client.hudOffsetY.get()));
        client.hudLayoutVersion.set(1);
        McaCrimeConfig.CLIENT_SPEC.save();
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            // First: report which MCA package root we bound to, before anything reads MCA. One line,
            // once, naming the root -- an unknown layout has to be visible in the log rather than
            // inferred from features quietly doing nothing.
            McaBinding.init();
            // Second: say, once, what another mod has already taken away. mcea's mixin removes
            // violent crime entirely, and that has to be in the log before the first punch that
            // does nothing rather than inferred from it.
            dev.otectus.mcacrime.compat.EpicFightCompat.logStartupNotices(LOGGER);
            // Force the crime-type registry (and its built-in ids) to class-load before any datapack parse.
            CrimeTypeRegistry.bootstrap();
            CrimeActionService.bootstrap();
            // Before anything can charge anybody: pick the currency named by integrations.currencyId.
            Currencies.reload();
            // Load-time config validation: surface a broken config in the log (and /crime validate).
            try {
                List<String> problems = ConfigValidator.validateCurrentConfig();
                if (!problems.isEmpty()) {
                    LOGGER.warn("MCA: Crime config has {} problem(s) — run /crime validate:", problems.size());
                    problems.forEach(p -> LOGGER.warn("  - {}", p));
                }
            } catch (Throwable t) {
                LOGGER.debug("Config validation skipped at setup (config not ready)", t);
            }
            // One line, once, if a config still asks for a hidden thief (0.7.2 §9.2).
            WorldCriminalJobService.warnAboutDeprecatedThiefPresentation();
            // Last, and inside enqueueWork: every mod has finished loading by now, so ModList is
            // authoritative, and the bridge must not race our own registration.
            ReputationBridge.init();
            // Same discipline, one optional mod later: a fence stocks locks and picks when Locks
            // Reforged is installed, and stocks vanilla contraband only when it is not.
            LocksReforgedBridge.init();
            // And one more: open bounties become guard-offered contracts when MCA: Quests is present.
            // MCA: Quests asks add-ons to register objective types from setup, which is where this is.
            McaQuestsBridge.init();
        });
    }
}
