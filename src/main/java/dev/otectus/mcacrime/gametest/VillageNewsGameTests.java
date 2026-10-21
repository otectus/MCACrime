package dev.otectus.mcacrime.gametest;

import com.mojang.authlib.GameProfile;
import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.compat.McaMailBridge;
import dev.otectus.mcacrime.compat.mca.McaHandles;
import dev.otectus.mcacrime.news.CrimeNewsData;
import dev.otectus.mcacrime.news.CrimeNewsService;
import net.minecraft.core.HolderLookup;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Village news reaches MCA's real mailbox (0.7.9, port-only).
 *
 * <p>The mailbox hooks are applied to MCA's own {@code PlayerSaveData} when it loads, so this is the
 * runtime half of {@code McaJusticeTargetTest}: a letter is appended once and marked by its title, its
 * receipt rides MCA's own save and comes back through MCA's own load, and collecting a letter marks the
 * mailbox dirty. The Forge build checks the same journey in its {@code tools/justice} harness. MCA is
 * named only through the binding's resolved package root.
 */
@GameTestHolder(McaCrime.MOD_ID)
@PrefixGameTestTemplate(false)
public final class VillageNewsGameTests {

    private VillageNewsGameTests() {
    }

    @GameTest(template = "platform", batch = "village_news")
    public static void aNewsLetterReachesMcasMailboxOnceAndItsReceiptSurvivesMcasSave(GameTestHelper helper)
            throws Exception {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        helper.assertTrue("ready".equals(McaMailBridge.status()),
                "MCA's mailbox is not ready for news: " + McaMailBridge.status());
        ServerPlayer player = mockPlayer(helper);
        Class<?> type = Class.forName(McaHandles.resolution().root() + "server.world.data.PlayerSaveData");
        Object mailbox = type.getMethod("get", ServerLevel.class, UUID.class)
                .invoke(null, server.overworld(), player.getUUID());
        SavedData saved = (SavedData) mailbox;
        try {
            long now = level.getGameTime();
            CrimeNewsData.Fact fact = new CrimeNewsData.Fact(UUID.randomUUID(), UUID.randomUUID(),
                    player.getUUID(), "", "reported", now, 1L);
            CompoundTag letter = CrimeNewsService.compose(server, List.of(fact), now);
            helper.assertTrue(CrimeNewsData.validLetter(letter), "the composed letter is not deliverable");
            UUID edition = UUID.randomUUID();
            McaMailBridge.Outcome first = McaMailBridge.enqueue(player, edition, letter, now);
            helper.assertTrue(first == McaMailBridge.Outcome.ENQUEUED, "the letter was not appended: " + first);
            McaMailBridge.Outcome replay = McaMailBridge.enqueue(player, edition, letter, now);
            helper.assertTrue(replay == McaMailBridge.Outcome.ALREADY_DELIVERED,
                    "a replayed edition was not recognised: " + replay);

            java.lang.reflect.Field inboxField = type.getDeclaredField("inbox");
            inboxField.setAccessible(true);
            List<?> inbox = (List<?>) inboxField.get(mailbox);
            long ours = inbox.stream().filter(entry -> titleOf(entry).startsWith(McaMailBridge.TITLE_PREFIX)).count();
            helper.assertTrue(ours == 1, "the inbox holds " + ours + " Crime letters, not one");
            helper.assertTrue((McaMailBridge.TITLE_PREFIX + edition).equals(titleOf(inbox.get(inbox.size() - 1))),
                    "the letter is not marked with its edition");

            CompoundTag written = (CompoundTag) type.getMethod("save", CompoundTag.class, HolderLookup.Provider.class)
                    .invoke(mailbox, new CompoundTag(), server.registryAccess());
            ListTag receipts = written.getList("mcacrime:news_receipts", Tag.TAG_COMPOUND);
            helper.assertTrue(receipts.size() == 1 && edition.equals(receipts.getCompound(0).getUUID("edition")),
                    "MCA's own save did not carry the receipt: " + receipts);
            var load = type.getDeclaredConstructor(ServerLevel.class, UUID.class, CompoundTag.class);
            load.setAccessible(true);
            Object reloaded = load.newInstance(server.overworld(), player.getUUID(), written);
            helper.assertTrue(McaMailBridge.saveReceipts(reloaded, new CompoundTag())
                            .getList("mcacrime:news_receipts", Tag.TAG_COMPOUND).equals(receipts),
                    "MCA's own load did not read the receipt back");

            saved.setDirty(false);
            var collect = type.getDeclaredMethod("getMail");
            collect.setAccessible(true);
            collect.invoke(mailbox);
            helper.assertTrue(saved.isDirty(), "collecting a letter left MCA's mailbox clean");
        } finally {
            // A fixture's mailbox is never written to the test world.
            saved.setDirty(false);
            player.discard();
        }
        helper.succeed();
    }

    private static String titleOf(Object letter) {
        try {
            return String.valueOf(letter.getClass().getMethod("title").invoke(letter));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("MCA's letter has no title", e);
        }
    }

    private static ServerPlayer mockPlayer(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        GameProfile profile = new GameProfile(UUID.randomUUID(), "mcacrime-news");
        ServerPlayer player = new ServerPlayer(level.getServer(), level, profile, ClientInformation.createDefault());
        player.connection = new ServerGamePacketListenerImpl(level.getServer(), new Connection(PacketFlow.SERVERBOUND),
                player, CommonListenerCookie.createInitial(profile, false)) {
            @Override public void send(Packet<?> packet) { }
            @Override public void send(Packet<?> packet, PacketSendListener listener) { }
        };
        return player;
    }
}
