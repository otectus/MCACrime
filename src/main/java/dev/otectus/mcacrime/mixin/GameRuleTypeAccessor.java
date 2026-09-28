package dev.otectus.mcacrime.mixin;

import com.mojang.brigadier.arguments.ArgumentType;
import net.minecraft.world.level.GameRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.function.Supplier;

/** The one vanilla visibility bridge needed to give 1.20.1 integer game rules bounded arguments. */
@Mixin(GameRules.Type.class)
public interface GameRuleTypeAccessor {
    @Mutable
    @Accessor("argument")
    void mcacrime$setArgument(Supplier<ArgumentType<?>> argument);
}
