package dev.otectus.mcacrime.mixin.mca;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
/** Target selected and descriptor-verified by McaJusticeMixinPlugin without loading MCA types. */
@Pseudo
@Mixin(targets = {"forge.net.mca.entity.ai.Relationship", "forge.net.conczin.mca.entity.ai.Relationship", "net.mca.entity.ai.Relationship", "net.conczin.mca.entity.ai.Relationship"}, remap = false)
public abstract class TragedyPenaltyMarker {}
