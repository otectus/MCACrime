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
import dev.otectus.mcacrime.state.CrimeCapabilities;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
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
        return new ResourceLocation(MOD_ID, path);
    }

    public McaCrime() {
        dev.otectus.mcacrime.config.CrimeGameRules.register();
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, McaCrimeConfig.COMMON_SPEC);
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, McaCrimeConfig.CLIENT_SPEC);

        final IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(this::onCommonSetup);
        // Entity selectors cache the compiled protected/responder lists. The cache notices a new list
        // instance on its own, but a reload that mutates the list in place would not change identity,
        // so the reload event drops it explicitly rather than relying on that.
        modBus.addListener(this::onConfigReload);
        modBus.addListener(this::onConfigLoading);
        modBus.addListener(CrimeCapabilities::onRegisterCapabilities);
        // Blocks before items: the Mask Station's BlockItem resolves the block it wraps, so the block
        // registry has to be attached first even though DeferredRegister defers both.
        dev.otectus.mcacrime.block.CrimeBlocks.register(modBus); // mask station (0.7.2 §6.1)
        // Block entities after the blocks they are valid for: a BlockEntityType names its blocks.
        dev.otectus.mcacrime.block.entity.CrimeBlockEntities.register(modBus); // cell door, safe (0.7.5 M3)
        CrimeItems.register(modBus); // restraints + masks + station item + creative tab (spec §8.3)
        // POI before professions: the thief profession's predicates name the mask-station POI key.
        dev.otectus.mcacrime.job.CrimePoiTypes.register(modBus); // mcacrime:mask_station (0.7.2 §10.1)
        CriminalProfessions.register(modBus); // thief (a real occupation as of 0.7.2) and fence
        // Entity types before the item that throws them: the Sand Bottle's projectile names its own
        // EntityType from a static initialiser the item never reaches first, but keeping the order
        // explicit is cheaper than discovering the day that stops being true.
        dev.otectus.mcacrime.entity.CrimeEntities.register(modBus); // sand bottle projectile (0.7.2 §13)
        dev.otectus.mcacrime.effect.CrimeEffects.register(modBus); // sand_blinded (0.7.2), restrained (0.7.5)
        // Enchantments after the items they go on: the category names CrimeItems' restraint items
        // in its predicate, and a category is built when this class initialises.
        dev.otectus.mcacrime.enchantment.CrimeEnchantments.register(modBus); // the five of §3.10 (M6.1)
        // Sound ids before anything that plays one. Every id here is registered and stable even while
        // its audio file is still a vanilla fallback (§3.11).
        dev.otectus.mcacrime.audio.CrimeSoundEvents.register(modBus); // restraint and device moments
        dev.otectus.mcacrime.stat.CrimeStats.register(modBus); // successful_lockpicks, lockpicks_broken
        // The station's data contract and its screen's server half. Recipes before menus only for
        // readability: DeferredRegister orders both by registry, not by this line.
        dev.otectus.mcacrime.recipe.CrimeRecipes.register(modBus); // mcacrime:mask_making (0.7.2 §7)
        dev.otectus.mcacrime.menu.CrimeMenus.register(modBus); // mask station menu (0.7.2 §8.1)

        LOGGER.info("MCA: Crime initialising (mod id '{}')", MOD_ID);
    }

    private void onConfigReload(net.minecraftforge.fml.event.config.ModConfigEvent.Reloading event) {
        migrateClientHud(event.getConfig());
        if (!MOD_ID.equals(event.getConfig().getModId())
                || event.getConfig().getSpec() != McaCrimeConfig.COMMON_SPEC) return;
        applyRestraintPreset(event.getConfig());
        net.minecraft.server.MinecraftServer running =
                net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        Runnable refresh = () -> {
            // Before anything else reads the new numbers: no session may straddle a reload. A struggle
            // priced against the old durability, a lockpick metered by the old divisor or a frisk
            // paced by the old transfer interval would finish under rules that no longer exist and that
            // nobody could look up. Cancelling is the honest answer -- every session is transient, its
            // owner is told, and starting again costs one interaction (0.7.5 M7.1).
            dev.otectus.mcacrime.restraint.SessionReloadPolicy.cancelAll(running);
            if (running != null) {
                dev.otectus.mcacrime.compat.TownsteadBridge.reload();
            }
            ConfigValidator.validateCurrentConfig().forEach(problem ->
                    LOGGER.warn("MCA: Crime config reload: {}", problem));
            dev.otectus.mcacrime.detect.EntitySelectors.invalidate();
            dev.otectus.mcacrime.item.weapon.WeaponDetector.invalidate();
            // The contraband list is compiled once and read per search pass, so a reload has to drop it.
            // Tag entries are re-checked at the next TagsUpdatedEvent, which is the only time they can be.
            dev.otectus.mcacrime.enforcement.ContrabandPolicy.invalidate();
            // The active currency is chosen by an id, so a reload that renames it must take effect
            // before the next fine is charged rather than at the next restart.
            dev.otectus.mcacrime.economy.Currencies.reload();
            // Weapon lists are gated on server-side but shown client-side, so every connected client
            // is told the new policy now rather than at their next login.
            net.minecraft.server.MinecraftServer server =
                    net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
            if (server != null) {
                dev.otectus.mcacrime.network.CrimeNetwork.broadcastWeaponPolicy(server);
                // Both criminal-job presentation keys are answered once per villager, so a reload that
                // flips one has to revisit the criminals that already exist rather than only the next.
                WorldCriminalJobService.of(server).refreshPresentation();
                // refreshBehaviors re-asks the role question for every recorded criminal, which is why
                // it follows the EntitySelectors.invalidate() above rather than preceding it: a reload
                // that added a modded guard to responderEntities drops that villager's thief
                // controller here instead of at the next restart. No role result is cached (0.7.2).
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

    private void onConfigLoading(net.minecraftforge.fml.event.config.ModConfigEvent.Loading event) {
        migrateClientHud(event.getConfig());
        if (MOD_ID.equals(event.getConfig().getModId())
                && event.getConfig().getSpec() == McaCrimeConfig.COMMON_SPEC) {
            applyRestraintPreset(event.getConfig());
        }
    }

    /**
     * Writes {@code restraints.preset} into the file, once, when it names a tuning the file was not
     * written from (0.7.5 M7.2).
     *
     * <p>Never silent and never repeated: the keys it rewrote are named in the log with their old and
     * new values, and {@code appliedPreset} then makes a second pass a no-op, so an operator who tunes
     * one of those keys by hand afterwards keeps their edit.
     */
    private void applyRestraintPreset(ModConfig config) {
        try {
            var common = McaCrimeConfig.COMMON;
            var requested = common.preset.get();
            if (!dev.otectus.mcacrime.config.RestraintPresets.shouldApply(requested,
                    common.appliedPreset.get())) {
                return;
            }
            dev.otectus.mcacrime.config.RestraintPresets.apply(common, requested).forEach(LOGGER::info);
            config.save();
        } catch (Throwable t) {
            // A preset is a convenience. If the spec is not readable yet, or the file is read-only,
            // the server starts on the values it already has rather than not starting at all.
            LOGGER.warn("MCA: Crime could not apply restraints.preset; the config is unchanged", t);
        }
    }

    private void migrateClientHud(ModConfig config) {
        if (!MOD_ID.equals(config.getModId()) || config.getSpec() != McaCrimeConfig.CLIENT_SPEC
                || McaCrimeConfig.CLIENT.hudLayoutVersion.get() >= 1) return;
        var client = McaCrimeConfig.CLIENT;
        client.hudAnchor.set(dev.otectus.mcacrime.client.hud.CrimeHudLayout.migratedAnchor(
                client.hudAnchor.get(), client.hudOffsetX.get(), client.hudOffsetY.get()));
        client.hudLayoutVersion.set(1);
        config.save();
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            // First: report which MCA package root we bound to, before anything reads MCA. One line,
            // once, naming the root -- an unknown layout has to be visible in the log rather than
            // inferred from features quietly doing nothing.
            McaBinding.init();
            // Second: say, once, what another mod has already taken away. efmca's mixin removes
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
            // Settings 0.7.5 retired, named one by one rather than silently ignored. Never a failure:
            // a key that no longer does anything must not stop a server that still sets it (§10.5).
            ConfigValidator.retiredKeyReport().forEach(LOGGER::warn);
            // Says once, plainly, what lock protection covers and what no adapter in this release
            // covers, rather than letting a padlock imply a promise it cannot keep (§10.3).
            dev.otectus.mcacrime.locks.LockProtectionHandlers.reportCoverage();
            // One line, once, if a config still asks for a hidden thief (0.7.2 §9.2).
            WorldCriminalJobService.warnAboutDeprecatedThiefPresentation();
            CrimeNetwork.register();
            // Dispenser behaviours, here because DispenserBlock's behaviour map is a plain static map
            // with no synchronisation: writing to it off this thread races every other mod doing the
            // same thing.
            dev.otectus.mcacrime.restraint.DispenserRestraintBehavior.register();
            // The custom statistics' display formatters (M5.11). Here rather than inside the
            // DeferredRegister supplier because a Stat builds its key from the registry the id is
            // about to be added to: the supplier runs before the registration, so a formatter asked
            // for there would name a null id and throw out of RegisterEvent.
            dev.otectus.mcacrime.stat.CrimeStats.registerFormatters();
            // Last, and inside enqueueWork: every mod has finished loading by now, so ModList is
            // authoritative, and the bridge must not race our own registration.
            dev.otectus.mcacrime.compat.mca.NativeJusticeCapabilities.initialize();
            ReputationBridge.init();
            dev.otectus.mcacrime.compat.ReputationExemptionBridge.init();
            // Same discipline, one optional mod later: a fence stocks locks and picks when Locks
            // Reforged is installed, and stocks vanilla contraband only when it is not.
            LocksReforgedBridge.init();
            // And one more: open bounties are offered as contracts when MCA: Quests is installed.
            // Inside enqueueWork because the adapter registers an objective type with it, which its
            // own API asks add-ons to do here.
            McaQuestsBridge.init();
            // The third-party registration window closes here, after every other mod's own setup has
            // run (M6.4): a definition that arrived later would be absent from every row already
            // written and present in every row written next.
            dev.otectus.mcacrime.restraint.RestraintDefinitions.closeRegistration();
            // And the optional adapters M6.2 owns, each of which reports what it found rather than
            // claiming support it cannot prove.
            if (McaCrimeConfig.COMMON.optionalAdaptersEnabled.get()) {
                dev.otectus.mcacrime.compat.ManaCompat.init();       // Silence drains a real pool
                dev.otectus.mcacrime.compat.InventoryCompat.init();  // Curios, Cosmetic Armor
                dev.otectus.mcacrime.compat.ReviveCompat.init();     // downed is not dead
            } else {
                LOGGER.info("MCA: Crime - optional-mod adapters are switched off; nothing is bound.");
            }
            // One line per installed optional mod, naming what this mod actually does about it --
            // including the ones it deliberately claims nothing for (§15.1).
            if (McaCrimeConfig.COMMON.reportAdapterVersions.get()) {
                dev.otectus.mcacrime.compat.OptionalMods.report().forEach(LOGGER::info);
            }
            // And, if the mod this release absorbed is installed beside it, exactly one warning.
            dev.otectus.mcacrime.compat.CuffedCoexistence.logStartupNotice();
            // The legal system's answer to "is this subject condemned?" (§3.19, M6.6). Until this
            // runs, ExecutionAuthorization's default source says nobody is, and a guillotine can
            // detain and release and nothing else.
            dev.otectus.mcacrime.ledger.CapitalSentenceService.install();
        });
    }
}
