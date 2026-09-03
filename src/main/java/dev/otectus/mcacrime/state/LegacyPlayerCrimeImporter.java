package dev.otectus.mcacrime.state;

import dev.otectus.mcacrime.McaCrime;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.attachment.AttachmentHolder;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads a 1.20.1 Forge player file and lifts its capability blob into the NeoForge attachment
 * (spec §7.3).
 *
 * <p>NeoForge's entity loader only knows {@code neoforge:attachments}; a world upgraded from Forge
 * still keeps its crime state under {@code ForgeCaps}, where it would be silently dropped. This
 * listener is the only thing that closes that gap, and it is strictly read-only: it never writes,
 * renames or cleans up the {@code .dat}. The next ordinary player save re-emits the data in the
 * attachment shape by itself.
 *
 * <p>Precedence is decided from the file contents, not from {@link Player#hasData}: {@code hasData}
 * turns true for every player the moment anything calls {@code getData}, so it cannot tell a real
 * loaded attachment from a freshly created default. If the file already carries a native
 * attachment, the new data wins and this class does nothing.
 */
@EventBusSubscriber(modid = McaCrime.MOD_ID)
public final class LegacyPlayerCrimeImporter {

    /** The Forge 1.20.1 root key. Vanilla never writes it, so its presence means "upgraded world". */
    static final String FORGE_CAPS_KEY = "ForgeCaps";
    /** The capability / attachment ID, identical on both sides by design. */
    static final String CRIME_KEY = McaCrime.MOD_ID + ":player_crime";

    /** A player file is a few kilobytes; 16 MiB is generous and still bounds a corrupt one. */
    private static final long MAX_LEGACY_PLAYER_NBT_BYTES = 16L * 1024L * 1024L;

    private LegacyPlayerCrimeImporter() {
    }

    /**
     * Fires after the player entity has been loaded from disk, so any native attachment is already
     * deserialised and the raw file is safe to re-read.
     */
    @SubscribeEvent
    public static void onLoadFromFile(PlayerEvent.LoadFromFile event) {
        Player player = event.getEntity();
        if (!(player instanceof ServerPlayer)) {
            return;
        }

        Path directory = event.getPlayerDirectory().toPath();
        String uuid = event.getPlayerUUID();
        CompoundTag root = readPlayerFile(directory.resolve(uuid + ".dat"), uuid);
        if (root == null) {
            // The live file was unreadable; vanilla's own backup is the last remaining copy.
            root = readPlayerFile(directory.resolve(uuid + ".dat_old"), uuid);
        }
        if (root == null) {
            McaCrime.LOGGER.warn("[MCA: Crime] Could not read the player file for {}; skipping the "
                    + "1.20.1 crime-data import. The player keeps whatever state loaded normally.", uuid);
            return;
        }

        if (hasNativeAttachment(root)) {
            return; // Already migrated (or born on 1.21.1): the attachment is authoritative.
        }

        PlayerCrimeData imported = new PlayerCrimeData();
        if (!importFromForgeCaps(root, imported)) {
            return;
        }
        player.setData(CrimeAttachments.PLAYER_CRIME, imported);
        McaCrime.LOGGER.info("[MCA: Crime] Imported 1.20.1 Forge crime data for player {}.", uuid);
    }

    /** Reads one raw player file, or null when it is absent, unreadable or not a compound. */
    private static CompoundTag readPlayerFile(Path file, String uuid) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return NbtIo.readCompressed(file, NbtAccounter.create(MAX_LEGACY_PLAYER_NBT_BYTES));
        } catch (IOException | RuntimeException ex) {
            McaCrime.LOGGER.warn("[MCA: Crime] Failed to read {} while looking for 1.20.1 crime data "
                    + "for {}.", file.getFileName(), uuid, ex);
            return null;
        }
    }

    /**
     * True when the raw player-file root already stores this mod's data as a NeoForge attachment.
     * Package-visible so the precedence rule can be tested without a server.
     */
    static boolean hasNativeAttachment(CompoundTag playerFileRoot) {
        if (!playerFileRoot.contains(AttachmentHolder.ATTACHMENTS_NBT_KEY, Tag.TAG_COMPOUND)) {
            return false;
        }
        return playerFileRoot.getCompound(AttachmentHolder.ATTACHMENTS_NBT_KEY)
                .contains(CRIME_KEY, Tag.TAG_COMPOUND);
    }

    /**
     * Loads the legacy {@code ForgeCaps → mcacrime:player_crime} blob from a raw player-file root
     * into {@code into}. Package-visible and free of any side effect beyond {@code into} so the unit
     * test can drive it with a canned 1.20.1 root.
     *
     * @return true when legacy data was found and read
     */
    static boolean importFromForgeCaps(CompoundTag playerFileRoot, PlayerCrimeData into) {
        if (!playerFileRoot.contains(FORGE_CAPS_KEY, Tag.TAG_COMPOUND)) {
            return false;
        }
        CompoundTag forgeCaps = playerFileRoot.getCompound(FORGE_CAPS_KEY);
        if (!forgeCaps.contains(CRIME_KEY, Tag.TAG_COMPOUND)) {
            return false;
        }
        // load() is total over any compound: wrong-typed keys read as their zero value, so a
        // mangled blob degrades to defaults rather than throwing mid-import.
        into.load(forgeCaps.getCompound(CRIME_KEY));
        return true;
    }
}
