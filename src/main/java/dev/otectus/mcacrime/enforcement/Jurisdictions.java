package dev.otectus.mcacrime.enforcement;

import dev.otectus.mcacrime.api.model.CrimeCommunityKey;
import dev.otectus.mcacrime.compat.McaCompat;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;

/**
 * Turns a {@link CrimeCommunityKey} into something a player can read.
 *
 * <p>The key itself is {@code minecraft:overworld/0}. Its own javadoc calls that "the stable string
 * form used for NBT map keys, command suggestions, and logs", and it is exactly right for those — but
 * both player-facing screens were rendering it verbatim, so a guard announced their authority as a
 * dimension id and an array index. A jurisdiction is a <em>place</em>, and MCA already knows what that
 * place is called.
 *
 * <p>Three outcomes, in order of preference: the village's MCA name; a generic "unnamed village" when
 * MCA knows the village but nobody has named it; and "wilderness" when there is no village at all.
 * The key is never shown. If MCA cannot answer, the honest label is that the place has no name — not
 * a storage identifier that happens to be printable.
 *
 * <p>Server-side resolution only. The result travels as a {@link Component}, so the two fallbacks
 * localise on the client like every other string this mod sends.
 */
public final class Jurisdictions {

    private Jurisdictions() {
    }

    /** The label for a jurisdiction, or the wilderness label when there is no village. */
    public static Component label(@Nullable MinecraftServer server, @Nullable CrimeCommunityKey key) {
        if (server == null || key == null) {
            return Component.translatable("gui.mcacrime.jurisdiction.wilderness");
        }
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, key.dimension()));
        if (level == null) {
            // The dimension the crime was recorded in is gone (a removed datapack, usually). The case
            // is still real, so it still needs a label; what it cannot have is a name.
            return Component.translatable("gui.mcacrime.jurisdiction.unnamed");
        }
        return McaCompat.getVillageName(level, key.villageId())
                .<Component>map(Component::literal)
                .orElseGet(() -> Component.translatable("gui.mcacrime.jurisdiction.unnamed"));
    }

    /** As {@link #label}, resolving the level directly when the caller already has one. */
    public static Component label(@Nullable ServerLevel level, @Nullable CrimeCommunityKey key) {
        if (level == null || key == null) {
            return Component.translatable("gui.mcacrime.jurisdiction.wilderness");
        }
        ResourceKey<Level> dimension = level.dimension();
        return dimension.location().equals(key.dimension())
                ? McaCompat.getVillageName(level, key.villageId())
                        .<Component>map(Component::literal)
                        .orElseGet(() -> Component.translatable("gui.mcacrime.jurisdiction.unnamed"))
                : label(level.getServer(), key);
    }
}
