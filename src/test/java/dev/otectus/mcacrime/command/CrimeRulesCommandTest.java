package dev.otectus.mcacrime.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrimeRulesCommandTest {
    private static CommandSourceStack source(int permission) {
        return new CommandSourceStack(CommandSource.NULL, Vec3.ZERO, Vec2.ZERO, null, permission,
                "test", Component.literal("test"), null, null);
    }

    private static boolean parses(String command, int permission) {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(CrimeRulesCommand.build());
        ParseResults<CommandSourceStack> result = dispatcher.parse(command, source(permission));
        return result.getContext().getCommand() != null
                && !result.getReader().canRead() && result.getExceptions().isEmpty();
    }

    @Test
    void statusIsReadableButMutationsRequireOrdinaryOperatorPermission() {
        assertTrue(parses("rules status", 0));
        for (String command : new String[]{"rules import", "rules defaults"}) {
            assertFalse(parses(command, 0), command);
            assertFalse(parses(command, 1), command);
            assertTrue(parses(command, 2), command);
        }
    }
}
