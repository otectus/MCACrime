package dev.otectus.mcacrime.state;

import dev.otectus.mcacrime.McaCrime;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.Optional;

/**
 * The player crime data attachment (spec §7.1), successor to the 1.20.1 {@code CrimeCapabilities}.
 *
 * <p>Registered with {@link AttachmentType#serializable} rather than a Codec on purpose: the
 * attachment then persists through {@link PlayerCrimeData#save()} / {@link PlayerCrimeData#load},
 * so every NBT key stays byte-identical to the Forge capability blob and
 * {@link LegacyPlayerCrimeImporter} can feed a 1.20.1 {@code ForgeCaps} payload straight into it
 * (spec §2.1.3, §7.3). A Codec would rewrite the shape and break that.
 *
 * <p>{@code .copyOnDeath()} replaces the old {@code PlayerEvent.Clone} handler: jail and arrest
 * survive being killed (§7.1).
 */
public final class CrimeAttachments {

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, McaCrime.MOD_ID);

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<PlayerCrimeData>> PLAYER_CRIME =
            ATTACHMENTS.register("player_crime",
                    () -> AttachmentType.serializable(PlayerCrimeData::new)
                            .copyOnDeath()
                            .build());

    private CrimeAttachments() {
    }

    public static void register(IEventBus bus) {
        ATTACHMENTS.register(bus);
    }

    /** The player's crime data, created on demand. The one accessor normal gameplay should use. */
    public static PlayerCrimeData get(Player player) {
        return player.getData(PLAYER_CRIME);
    }

    /**
     * The player's crime data only if one already exists, without creating it. For the rare caller
     * that needs to tell "never touched" from "default" — the legacy import path (§7.2).
     */
    public static Optional<PlayerCrimeData> getIfPresent(Player player) {
        return player.getExistingData(PLAYER_CRIME);
    }
}
