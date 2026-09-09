package dev.otectus.mcacrime.gametest;

import com.mojang.authlib.GameProfile;
import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.McaCrimeConfig;
import dev.otectus.mcacrime.captivity.*;
import dev.otectus.mcacrime.compat.LocksReforgedBridge;
import dev.otectus.mcacrime.state.world.CrimeWorldData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.ArrayList;
import java.util.UUID;

/** Uses the real optional menu and payloads; the mock connection records sends without a client. */
@GameTestHolder(McaCrime.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CuffParityGameTests {
    private CuffParityGameTests() {}

    @GameTest(template = "platform", timeoutTicks = 80)
    public static void nativeCuffEscapeRequiresSolvedPins(GameTestHelper helper) throws ReflectiveOperationException {
        if (!LocksReforgedBridge.installed()) {
            helper.assertTrue(!CuffEscapeService.usesMinigame(RestraintType.CUFFS), "Absent Locks forced a minigame");
            helper.succeed();
            return;
        }
        var level = helper.getLevel();
        var profile = new GameProfile(UUID.randomUUID(), "cuff-parity-test");
        var player = new ServerPlayer(level.getServer(), level, profile, ClientInformation.createDefault());
        var sent = new ArrayList<Packet<?>>();
        player.connection = new ServerGamePacketListenerImpl(level.getServer(), new Connection(PacketFlow.SERVERBOUND),
                player, CommonListenerCookie.createInitial(profile, false)) {
            @Override public void send(Packet<?> packet) { sent.add(packet); }
            @Override public void send(Packet<?> packet, PacketSendListener listener) { sent.add(packet); }
        };
        var pos = helper.absolutePos(new BlockPos(2, 1, 2));
        player.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        player.getInventory().setItem(0, new ItemStack(Items.DIAMOND));
        var custody = new CustodyRecord(player.getUUID(), true, false, CustodyOwner.none(),
                RestraintType.CUFFS, 0, pos, level.dimension().location());
        custody.setCuffCombination(new byte[] {0, 1, 2, 3, 4});
        var world = CrimeWorldData.get(level.getServer());
        world.putCustody(custody);
        var requirePick = McaCrimeConfig.COMMON.cuffEscapeRequiresLockpick;
        boolean prior = requirePick.get();
        try {
            requirePick.set(true);
            helper.assertTrue(!LocksReforgedBridge.openCuffs(player, custody), "Opened without a required pick");
            var pick = BuiltInRegistries.ITEM.getOptional(ResourceLocation.fromNamespaceAndPath("locks", "iron_lock_pick"))
                    .orElseThrow();
            player.getInventory().setItem(12, new ItemStack(pick));
            helper.assertTrue(LocksReforgedBridge.openCuffs(player, custody), "A pick in the inventory did not permit opening");
            helper.assertTrue(player.containerMenu.stillValid(player), "Inventory pick was not accepted throughout the attempt");
            player.getInventory().setItem(12, ItemStack.EMPTY);
            helper.assertTrue(!player.containerMenu.stillValid(player), "Removing a required pick left the attempt valid");
            player.closeContainer();
            helper.assertTrue(world.getCustody(player.getUUID()) == custody, "Closing the menu released custody");
            requirePick.set(false);
            helper.assertTrue(LocksReforgedBridge.openCuffs(player, custody), "Itemless mode did not open with an occupied hand");
        } finally { requirePick.set(prior); }
        helper.assertTrue(!prior, "Run this integration test with the default optional pick requirement");
        var menu = player.containerMenu;
        boolean sequenced = Class.forName("melonslise.locks.common.network.toclient.TryPinResultPayload")
                .getRecordComponents().length == 7;
        var tryPin = sequenced ? menu.getClass().getMethod("tryPin", int.class, int.class, int.class)
                : menu.getClass().getMethod("tryPin", int.class);
        if (sequenced) tryPin.invoke(menu, 4, menu.containerId, 1);
        else tryPin.invoke(menu, 4); // A miss must reset the client even when no pick can break.
        var miss = sent.stream().filter(p -> p instanceof ClientboundCustomPayloadPacket payload
                && payload.payload().type().id().equals(ResourceLocation.fromNamespaceAndPath("locks", "try_pin_result")))
                .map(p -> ((ClientboundCustomPayloadPacket) p).payload()).reduce((a, b) -> b).orElseThrow();
        helper.assertTrue((boolean) miss.getClass().getMethod("reset").invoke(miss), "Itemless miss did not reset client progress");
        helper.assertTrue(world.getCustody(player.getUUID()) == custody, "A wrong pin released the cuffs");
        for (int pin = 0; pin < 5; pin++) {
            int selected = pin;
            helper.runAfterDelay(3L * (pin + 1), () -> {
                try {
                    if (sequenced) tryPin.invoke(menu, selected, menu.containerId, selected + 2);
                    else tryPin.invoke(menu, selected);
                }
                catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
                if (selected < 4) helper.assertTrue(world.getCustody(player.getUUID()) == custody,
                        "Cuffs released before the final pin");
            });
        }
        helper.runAfterDelay(18, () -> {
            helper.assertTrue(world.getCustody(player.getUUID()) == null, "Solving the native pins did not release custody");
            helper.assertTrue(player.getInventory().getItem(0).is(Items.DIAMOND), "Attempt changed the held item");
            player.closeContainer(); player.discard();
            helper.succeed();
        });
    }
}
