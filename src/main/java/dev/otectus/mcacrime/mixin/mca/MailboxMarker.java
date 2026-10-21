package dev.otectus.mcacrime.mixin.mca;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
/** Target selected and descriptor-verified by McaJusticeMixinPlugin without loading MCA types. */
@Pseudo
@Mixin(targets = {"forge.net.mca.server.world.data.PlayerSaveData", "forge.net.conczin.mca.server.world.data.PlayerSaveData", "net.mca.server.world.data.PlayerSaveData", "net.conczin.mca.server.world.data.PlayerSaveData"}, remap = false)
public abstract class MailboxMarker {}
