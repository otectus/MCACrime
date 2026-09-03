package dev.otectus.mcacrime;

import com.mojang.logging.LogUtils;
import dev.otectus.mcacrime.compat.ReputationBridge;
import dev.otectus.mcacrime.action.CrimeActionService;
import dev.otectus.mcacrime.compat.mca.McaBinding;
import dev.otectus.mcacrime.config.ConfigValidator;
import dev.otectus.mcacrime.crime.type.CrimeTypeRegistry;
import dev.otectus.mcacrime.item.CrimeItems;
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
        CrimeAttachments.register(modBus);
        CrimeItems.register(modBus); // restraints + creative tab (spec §8.3)
        // Payload registration is a mod-bus listener, never a common-setup call: the registrar is
        // only open for the duration of the event.
        modBus.addListener(CrimeNetwork::register);

        LOGGER.info("MCA: Crime initialising (mod id '{}')", MOD_ID);
    }

    private void onConfigReload(ModConfigEvent.Reloading event) {
        if (event.getConfig().getType() == ModConfig.Type.COMMON) {
            dev.otectus.mcacrime.detect.EntitySelectors.invalidate();
            dev.otectus.mcacrime.item.weapon.WeaponDetector.invalidate();
        }
    }

    private void onCommonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            // First: report which MCA package root we bound to, before anything reads MCA. One line,
            // once, naming the root -- an unknown layout has to be visible in the log rather than
            // inferred from features quietly doing nothing.
            McaBinding.init();
            // Force the crime-type registry (and its built-in ids) to class-load before any datapack parse.
            CrimeTypeRegistry.bootstrap();
            CrimeActionService.bootstrap();
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
            // Last, and inside enqueueWork: every mod has finished loading by now, so ModList is
            // authoritative, and the bridge must not race our own registration.
            ReputationBridge.init();
        });
    }
}
