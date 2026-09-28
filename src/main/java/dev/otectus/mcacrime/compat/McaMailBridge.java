package dev.otectus.mcacrime.compat;

import dev.otectus.mcacrime.McaCrime;
import dev.otectus.mcacrime.compat.mca.McaHandles;
import dev.otectus.mcacrime.mixin.mca.McaJusticeMixinPlugin;
import dev.otectus.mcacrime.state.world.ServerMutationGate;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import java.lang.reflect.*;
import java.util.*;

/** Native append-once inbox operation. Receipts are saved with MCA's inbox, not Crime's outbox. */
public final class McaMailBridge {
    public enum Outcome { ENQUEUED, ALREADY_DELIVERED, DISABLED, DEFERRED_FULL, UNAVAILABLE, RETRYABLE_FAILURE }
    public static final long RECEIPT_RETENTION = 30L * 24000;
    private static final String RECEIPTS = "mcacrime:news_receipts";
    private static final Map<Object, LinkedHashMap<UUID, Long>> receipts = new WeakHashMap<>();
    private record Binding(Method get, Method config, Field enabled, Field inbox, Method send, Method notifyPlayer) {}
    private static Binding binding;
    private static boolean attempted, warned;
    private McaMailBridge() {}
    private static Binding binding() {
        if (attempted) return binding;
        attempted = true;
        try {
            String root = McaHandles.resolution().root();
            if (root == null) return null;
            Class<?> data = Class.forName(root + "server.world.data.PlayerSaveData");
            Class<?> config = Class.forName(root + "Config");
            Field inbox = data.getDeclaredField("inbox"); inbox.setAccessible(true);
            binding = new Binding(data.getMethod("get", ServerLevel.class, UUID.class), config.getMethod("getInstance"),
                    config.getField("enableVillagerMailingPlayers"), inbox, data.getMethod("sendMail", CompoundTag.class),
                    data.getMethod("showMailNotification", ServerPlayer.class));
        } catch (ReflectiveOperationException | LinkageError e) { warn(e); }
        return binding;
    }
    public static String status() {
        Binding b = binding();
        if (b == null || !McaJusticeMixinPlugin.APPLIED.contains("PlayerSaveData")) return "unavailable";
        try { return b.enabled.getBoolean(b.config.invoke(null)) ? "ready" : "MCA mailing disabled"; }
        catch (ReflectiveOperationException e) { return "unavailable"; }
    }
    @SuppressWarnings("unchecked")
    public static Outcome enqueue(ServerPlayer player, UUID edition, CompoundTag letter, long now) {
        if (!ServerMutationGate.allows(player.getServer()) || !player.getServer().isSameThread()) return Outcome.UNAVAILABLE;
        Binding b = binding();
        if (b == null || !McaJusticeMixinPlugin.APPLIED.contains("PlayerSaveData")) return Outcome.UNAVAILABLE;
        try {
            if (!b.enabled.getBoolean(b.config.invoke(null))) return Outcome.DISABLED;
            Object data = b.get.invoke(null, player.getServer().overworld(), player.getUUID());
            var stored = receipts.computeIfAbsent(data, unused -> new LinkedHashMap<>());
            stored.values().removeIf(at -> now >= at && now - at > RECEIPT_RETENTION);
            if (stored.containsKey(edition)) return Outcome.ALREADY_DELIVERED;
            var inbox = (List<CompoundTag>) b.inbox.get(data);
            if (stored.size() >= 64 || inbox.stream().filter(t -> "mcacrime".equals(t.getString("mcacrime:origin"))).count() >= 7)
                return Outcome.DEFERRED_FULL;
            if (!dev.otectus.mcacrime.news.CrimeNewsData.validLetter(letter)) return Outcome.UNAVAILABLE;
            CompoundTag tagged = letter.copy(); tagged.putUUID("mcacrime:edition", edition); tagged.putString("mcacrime:origin", "mcacrime");
            int before = inbox.size(); b.send.invoke(data, tagged);
            if (inbox.size() != before + 1 || inbox.get(before) != tagged) return Outcome.RETRYABLE_FAILURE;
            stored.put(edition, now); ((SavedData) data).setDirty();
            // Append and receipt have committed. A toast failure must not turn success into a retry.
            try { b.notifyPlayer.invoke(null, player); } catch (ReflectiveOperationException e) { warn(e); }
            return Outcome.ENQUEUED;
        } catch (ReflectiveOperationException | RuntimeException e) { warn(e); return Outcome.RETRYABLE_FAILURE; }
    }
    public static void loadReceipts(Object data, CompoundTag tag) {
        LinkedHashMap<UUID, Long> loaded = new LinkedHashMap<>();
        ListTag list = tag.getList(RECEIPTS, Tag.TAG_COMPOUND);
        for (int i = 0; i < Math.min(64, list.size()); i++) {
            CompoundTag row = list.getCompound(i);
            if (row.hasUUID("edition") && row.getLong("at") >= 0) loaded.put(row.getUUID("edition"), row.getLong("at"));
        }
        receipts.put(data, loaded);
    }
    public static CompoundTag saveReceipts(Object data, CompoundTag tag) {
        ListTag list = new ListTag();
        receipts.getOrDefault(data, new LinkedHashMap<>()).forEach((id, at) -> {
            CompoundTag row = new CompoundTag(); row.putUUID("edition", id); row.putLong("at", at); list.add(row);
        });
        tag.put(RECEIPTS, list); return tag;
    }
    public static void collected(Object item, Object data) {
        if (item != null && data instanceof SavedData saved) saved.setDirty();
    }
    private static void warn(Throwable e) {
        if (!warned) { warned = true; McaCrime.LOGGER.warn("Native Crime news mail capability failed; optional news remains pending", e); }
    }
}
