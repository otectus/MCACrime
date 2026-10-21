package dev.otectus.mcacrime.mixin;

import com.mojang.brigadier.arguments.ArgumentType;
import net.minecraft.world.level.GameRules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.function.Supplier;

/**
 * The one vanilla visibility bridge needed to give integer game rules bounded arguments.
 *
 * <p>1.21.1 has a bounded {@code IntegerValue.create(int, int, int, BiConsumer)}, but it is
 * package-private; the public factory builds an unbounded argument, as 1.20.1's did. Setting the
 * argument supplier on the type is the same narrow change the Forge 1.20.1 baseline makes.
 */
@Mixin(GameRules.Type.class)
public interface GameRuleTypeAccessor {
    @Mutable
    @Accessor("argument")
    void mcacrime$setArgument(Supplier<ArgumentType<?>> argument);
}
