package dev.otectus.mcacrime.usability;

import com.mojang.authlib.GameProfile;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Shared fixture plumbing: players whose outgoing chat is recorded by translation key, a console that
 * records its output, flat stone arenas inside the spawn chunks, and MCA or vanilla entities that hold
 * still (NoAI) so a sight line or a distance is exactly what the check says it is.
 */
final class Fixtures {

    /** One line a fixture player received: its translation key (or plain text), its args, overlay flag. */
    record Heard(String key, String text, boolean overlay) {
        @Override
        public String toString() {
            return (overlay ? "[bar]" : "") + key;
        }
    }

    final MinecraftServer server;
    final ServerLevel level;
    private final Map<UUID, List<Heard>> told = new HashMap<>();

    Fixtures(MinecraftServer server) {
        this.server = server;
        this.level = server.overworld();
    }

    // --- players ---------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    ServerPlayer player(String name, double x, double y, double z) {
        UUID id = UUID.nameUUIDFromBytes(("usability:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ServerPlayer p = new ServerPlayer(server, level, new GameProfile(id, name));
        p.connection = new ServerGamePacketListenerImpl(server, new Connection(PacketFlow.SERVERBOUND) {
            @Override
            public void send(Packet<?> packet) {
                heard(id, packet);
            }

            @Override
            public void send(Packet<?> packet, net.minecraft.network.PacketSendListener listener) {
                heard(id, packet);
            }
        }, p);
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
            told.computeIfAbsent(id, unused -> new ArrayList<>())
                    .add(new Heard(keyOf(chat.content()), chat.content().getString(), chat.overlay()));
        }
    }

    /** The first translation key anywhere in a component tree, or its plain text when there is none. */
    static String keyOf(Component component) {
        String key = findKey(component);
        return key != null ? key : component.getString();
    }

    @Nullable
    private static String findKey(Component component) {
        if (component.getContents() instanceof TranslatableContents translatable) {
            return translatable.getKey();
        }
        for (Component sibling : component.getSiblings()) {
            String key = findKey(sibling);
            if (key != null) {
                return key;
            }
        }
        return null;
    }

    List<Heard> heard(ServerPlayer player) {
        return List.copyOf(told.getOrDefault(player.getUUID(), List.of()));
    }

    void forget(ServerPlayer player) {
        told.remove(player.getUUID());
    }

    /** Every key a player has been told, in order, for a failure message. */
    String toldTo(ServerPlayer player) {
        return heard(player).toString();
    }

    /** The full text of every line a player received, for a case where the words themselves matter. */
    String textTo(ServerPlayer player) {
        StringBuilder out = new StringBuilder();
        for (Heard heard : heard(player)) {
            if (out.length() > 0) {
                out.append(" | ");
            }
            out.append(heard.text());
        }
        return out.toString();
    }

    boolean wasTold(ServerPlayer player, String key) {
        return heard(player).stream().anyMatch(h -> h.key().equals(key));
    }

    void remove(ServerPlayer player) {
        try {
            server.getPlayerList().remove(player);
        } catch (RuntimeException ignored) {
            player.discard();
        }
    }

    // --- commands --------------------------------------------------------------------------------

    /** Runs a command as this player at the given permission level and returns its result code. */
    int run(ServerPlayer player, int permission, String command) {
        return server.getCommands().performPrefixedCommand(
                player.createCommandSourceStack().withPermission(permission), command);
    }

    /** A console-like source that records every line it is sent. */
    static final class Console implements CommandSource {
        final List<Component> lines = new ArrayList<>();

        @Override
        public void sendSystemMessage(Component component) {
            lines.add(component);
        }

        @Override
        public boolean acceptsSuccess() {
            return true;
        }

        @Override
        public boolean acceptsFailure() {
            return true;
        }

        @Override
        public boolean shouldInformAdmins() {
            return false;
        }

        List<String> keys() {
            return lines.stream().map(Fixtures::keyOf).toList();
        }

        String text() {
            StringBuilder out = new StringBuilder();
            for (Component line : lines) {
                if (out.length() > 0) {
                    out.append(" | ");
                }
                out.append(line.getString());
            }
            return out.toString();
        }
    }

    int runConsole(Console console, String command) {
        CommandSourceStack source = new CommandSourceStack(console, Vec3.atCenterOf(BlockPos.ZERO), Vec2.ZERO,
                level, 4, "Console", Component.literal("Console"), server, null);
        return server.getCommands().performPrefixedCommand(source, command);
    }

    // --- the world -------------------------------------------------------------------------------

    /** A stone floor under {@code y} and four blocks of air above it, over the given rectangle. */
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

    void set(int x, int y, int z, BlockState state) {
        level.setBlockAndUpdate(new BlockPos(x, y, z), state);
    }

    /** An MCA human villager standing still: {@code male_villager}, adult or child. */
    LivingEntity mca(double x, double y, double z, boolean child) {
        return mcaOfType("male_villager", x, y, z, child);
    }

    LivingEntity mcaOfType(String path, double x, double y, double z, boolean child) {
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("mca", path));
        if (type == null) {
            throw new AssertionError("MCA entity type mca:" + path + " is not registered");
        }
        LivingEntity entity = (LivingEntity) type.create(level);
        if (entity == null) {
            throw new AssertionError("could not create mca:" + path);
        }
        if (entity instanceof Villager villager) {
            villager.setAge(child ? -24000 : 0);
        }
        return place(entity, x, y, z);
    }

    LivingEntity vanilla(EntityType<? extends LivingEntity> type, double x, double y, double z) {
        LivingEntity entity = type.create(level);
        if (entity == null) {
            throw new AssertionError("could not create " + type);
        }
        return place(entity, x, y, z);
    }

    private LivingEntity place(LivingEntity entity, double x, double y, double z) {
        if (entity instanceof Mob mob) {
            mob.setNoAi(true);
            mob.setPersistenceRequired();
        }
        entity.setPos(x, y, z);
        entity.setYRot(0.0F);
        entity.setYHeadRot(0.0F);
        level.addFreshEntity(entity);
        return entity;
    }

    /** Points the player's eyes at the centre of the entity's hitbox, the way a player aims. */
    static void aim(ServerPlayer player, Entity target) {
        player.lookAt(EntityAnchorArgument.Anchor.EYES, target.getBoundingBox().getCenter());
    }

    /** Points the player's eyes at a world position. */
    static void aimAt(ServerPlayer player, Vec3 point) {
        player.lookAt(EntityAnchorArgument.Anchor.EYES, point);
    }

    static void discard(Entity... entities) {
        for (Entity entity : entities) {
            if (entity != null && !entity.isRemoved()) {
                entity.discard();
            }
        }
    }
}
