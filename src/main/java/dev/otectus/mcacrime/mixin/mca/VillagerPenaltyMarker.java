package dev.otectus.mcacrime.mixin.mca;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
/** Target selected and descriptor-verified by McaJusticeMixinPlugin without loading MCA types. */
@Pseudo
@Mixin(targets = {"forge.net.mca.entity.VillagerEntityMCA", "forge.net.conczin.mca.entity.VillagerEntityMCA", "net.mca.entity.VillagerEntityMCA", "net.conczin.mca.entity.VillagerEntityMCA"}, remap = false)
public abstract class VillagerPenaltyMarker {}
