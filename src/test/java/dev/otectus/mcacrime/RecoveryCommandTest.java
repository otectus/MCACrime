package dev.otectus.mcacrime;

import com.mojang.brigadier.CommandDispatcher;
import dev.otectus.mcacrime.command.RecoveryCommand;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryCommandTest {
    private static CommandSourceStack source(int permission) {
        return new CommandSourceStack(CommandSource.NULL, Vec3.ZERO, Vec2.ZERO, null, permission,
                "test", Component.literal("test"), null, null);
    }
    @Test void financialDiagnosticsRequirePermissionThree() {
        var tree = RecoveryCommand.tree().build();
        assertFalse(tree.canUse(source(0)));
        assertFalse(tree.canUse(source(2)));
        assertTrue(tree.canUse(source(3)));
        assertTrue(tree.canUse(source(4)));
    }
    @Test void resolutionSyntaxRequiresReceiptRevisionDecisionAndNote() {
        var dispatcher = new CommandDispatcher<CommandSourceStack>();
        dispatcher.register(Commands.literal("crime").then(RecoveryCommand.tree()));
        String command = "crime recovery resolve " + UUID.randomUUID() + " " + UUID.randomUUID() + " delivered";
        assertNull(dispatcher.parse(command, source(3)).getContext().getCommand());
        var complete = dispatcher.parse(command + " Bank confirmed the transfer", source(3));
        assertNotNull(complete.getContext().getCommand());
        assertFalse(complete.getReader().canRead());
        assertNull(dispatcher.parse(command + " Bank confirmed the transfer", source(2)).getContext().getCommand());
    }
}
