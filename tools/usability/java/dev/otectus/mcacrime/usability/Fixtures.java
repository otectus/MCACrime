package dev.otectus.mcacrime.usability;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fixture plumbing for the upgrade suite: players whose system chat is recorded by translation key, flat
 * stone arenas inside the spawn chunks, and MCA villagers that hold still. The NeoForge counterpart of the
 * Forge harness's {@code Fixtures}, trimmed to what the upgrade suite uses.
 */
final class Fixtures {

    final MinecraftServer server;
    final ServerLevel level;
    private final Map<UUID, List<String>> told = new HashMap<>();

    Fixtures(MinecraftServer server) {
        this.server = server;
        this.level = server.overworld();
    }

    @SuppressWarnings("unchecked")
    ServerPlayer player(String name, double x, double y, double z) {
        UUID id = UUID.nameUUIDFromBytes(("usability:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        GameProfile profile = new GameProfile(id, name);
        ServerPlayer p = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        p.connection = new ServerGamePacketListenerImpl(server, new Connection(PacketFlow.SERVERBOUND), p,
                CommonListenerCookie.createInitial(profile, false)) {
            @Override
            public void send(Packet<?> packet) {
                heard(id, packet);
            }

            @Override
            public void send(Packet<?> packet, PacketSendListener listener) {
                heard(id, packet);
            }
        };
        p.setPos(x, y, z);
        level.addNewPlayer(p);
        try {
            for (var field : net.minecraft.server.players.PlayerList.class.getDeclaredFields()) {
                if (field.getGenericType().getTypeName().equals("java.util.List<net.minecraft.server.level.ServerPlayer>")) {
                    field.setAccessible(true);
                    try {
                        ((List<ServerPlayer>) field.get(server.getPlayerList())).add(p);
                    } catch (UnsupportedOperationException immutableView) {
                        // a read-only view of the same list; the mutable field is found too
                    }
                }
                if (field.getGenericType().getTypeName().contains("java.util.UUID, net.minecraft.server.level.ServerPlayer")) {
                    field.setAccessible(true);
                    ((Map<UUID, ServerPlayer>) field.get(server.getPlayerList())).put(id, p);
                }
            }
        } catch (ReflectiveOperationException failure) {
            throw new RuntimeException(failure);
        }
        return p;
    }

    private void heard(UUID id, Packet<?> packet) {
        if (packet instanceof ClientboundSystemChatPacket chat) {
            told.computeIfAbsent(id, unused -> new ArrayList<>()).add(keyOf(chat.content()));
        }
    }

    static String keyOf(Component component) {
        if (component.getContents() instanceof TranslatableContents translatable) {
            return translatable.getKey();
        }
        for (Component sibling : component.getSiblings()) {
            String key = keyOf(sibling);
            if (!key.equals(sibling.getString())) {
                return key;
            }
        }
        return component.getString();
    }

    boolean wasTold(ServerPlayer player, String key) {
        return told.getOrDefault(player.getUUID(), List.of()).contains(key);
    }

    String toldTo(ServerPlayer player) {
        return told.getOrDefault(player.getUUID(), List.of()).toString();
    }

    void forget(ServerPlayer player) {
        told.remove(player.getUUID());
    }

    void remove(ServerPlayer player) {
        try {
            server.getPlayerList().remove(player);
        } catch (RuntimeException ignored) {
            player.discard();
        }
    }

    /** A stone floor under {@code y} and five blocks of air above it, over the given rectangle. */
    void arena(int x0, int x1, int z0, int z1, int y) {
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                level.setBlockAndUpdate(new BlockPos(x, y - 1, z), Blocks.STONE.defaultBlockState());
                for (int h = 0; h < 5; h++) {
                    level.setBlockAndUpdate(new BlockPos(x, y + h, z), Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    /** An adult MCA villager standing still. */
    LivingEntity mca(double x, double y, double z) {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(ResourceLocation.fromNamespaceAndPath("mca", "male_villager"))
                .orElseThrow(() -> new AssertionError("mca:male_villager is not registered"));
        LivingEntity entity = (LivingEntity) type.create(level);
        if (entity == null) {
            throw new AssertionError("could not create mca:male_villager");
        }
        if (entity instanceof Villager villager) {
            villager.setAge(0);
        }
        if (entity instanceof Mob mob) {
            mob.setNoAi(true);
            mob.setPersistenceRequired();
        }
        entity.setPos(x, y, z);
        level.addFreshEntity(entity);
        return entity;
    }
}
