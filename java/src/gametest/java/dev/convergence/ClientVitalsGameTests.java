package dev.convergence;

import dev.convergence.mixin.LivingVitalsAccess;
import dev.convergence.mixin.PlayerVitalsAccess;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.storage.TagValueOutput;

public class ClientVitalsGameTests {
    private static class Fixture implements AutoCloseable {
        final ServerPlayer player;
        final EmbeddedChannel channel;
        Fixture(GameTestHelper c, String name) {
            var server = c.getLevel().getServer();
            var profile = new com.mojang.authlib.GameProfile(UUID.randomUUID(), name);
            var data = CommonListenerCookie.createInitial(profile, false);
            player = new ServerPlayer(server, c.getLevel(), profile, data.clientInformation());
            var connection = new Connection(PacketFlow.SERVERBOUND);
            channel = new EmbeddedChannel(connection);
            server.getPlayerList().placeNewPlayer(connection, player, data);
            OperatorGameTests.level(player, LevelBasedPermissionSet.OWNER);
            drain();
        }
        List<Packet<?>> drain() {
            channel.runPendingTasks();
            var result = new ArrayList<Packet<?>>();
            Object value;
            while ((value = channel.readOutbound()) != null) {
                if (value instanceof ClientboundBundlePacket bundle) bundle.subPackets().forEach(result::add);
                else if (value instanceof Packet<?> packet) result.add(packet);
            }
            return result;
        }
        void set(GameTestHelper c, String id, double value) {
            var result = AdminStats.set(player.createCommandSourceStack(), player, id, value);
            c.assertTrue(result.success(), result.message());
        }
        @Override public void close() {
            OperatorGameTests.deop(player);
            player.level().getServer().getPlayerList().remove(player);
            channel.finishAndReleaseAll();
        }
    }

    @GameTest public void enormousHealthUsesBoundedRealNetworkPacketsWithoutChangingSavedStats(GameTestHelper c) {
        try (var f = new Fixture(c, "hud-giant")) {
            f.set(c, "health", 1e21);
            var before = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, f.player.registryAccess());
            f.player.saveWithoutId(before);
            f.player.connection.send(new ClientboundUpdateAttributesPacket(f.player.getId(),
                List.of(f.player.getAttribute(Attributes.MAX_HEALTH))));
            f.player.connection.send(new ClientboundSetHealthPacket(f.player.getHealth(), 20, 5));
            var packets = f.drain();
            var attrs = packets.stream().filter(p -> p instanceof ClientboundUpdateAttributesPacket).map(p -> (ClientboundUpdateAttributesPacket)p).toList();
            c.assertFalse(attrs.isEmpty(), "Real server network handler sends attributes");
            for (var packet : attrs) for (var entry : packet.getValues())
                c.assertValueEqual(entry.base(), 40d, "Client health capacity is bounded");
            var health = packets.stream().filter(p -> p instanceof ClientboundSetHealthPacket).map(p -> (ClientboundSetHealthPacket)p).toList();
            c.assertFalse(health.isEmpty(), "Real server network handler sends health");
            for (var packet : health) {
                c.assertValueEqual(packet.getHealth(), 40f, "Full enormous health displays a full compact bar");
                var buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
                try {
                    ClientboundSetHealthPacket.STREAM_CODEC.encode(buf, packet);
                    c.assertValueEqual(ClientboundSetHealthPacket.STREAM_CODEC.decode(buf).getHealth(), 40f, "Native codec carries the bounded value");
                } finally { buf.release(); }
            }
            var after = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, f.player.registryAccess());
            f.player.saveWithoutId(after);
            c.assertValueEqual(after.buildResult(), before.buildResult(), "Sending HUD packets preserves all saved player state");
            c.assertTrue(f.player.getHealth() > 1e20 && f.player.getMaxHealth() > 1e20, "Actual gigantic health stays intact");
        }
        c.succeed();
    }

    @GameTest public void capacityOnlyEditsRefreshTheScaledHudAndCanReturnToNormal(GameTestHelper c) {
        try (var f = new Fixture(c, "hud-capacity")) {
            f.set(c, "max_health", 400);
            f.player.setHealth(20);
            f.player.connection.send(new ClientboundUpdateAttributesPacket(f.player.getId(), List.of(f.player.getAttribute(Attributes.MAX_HEALTH))));
            c.assertTrue(f.drain().stream().anyMatch(p -> p instanceof ClientboundSetHealthPacket h && h.getHealth() == 2f), "Capacity-only packet resends the correct percentage");
            f.set(c, "max_health", 20);
            f.player.connection.send(new ClientboundUpdateAttributesPacket(f.player.getId(), List.of(f.player.getAttribute(Attributes.MAX_HEALTH))));
            c.assertTrue(f.drain().stream().anyMatch(p -> p instanceof ClientboundSetHealthPacket h && h.getHealth() == 20f), "Returning to ordinary capacity refreshes ordinary hearts");
        }
        c.succeed();
    }

    @GameTest public void largeAbsorptionAndHealthMetadataAreBoundedWithoutTouchingOtherFields(GameTestHelper c) {
        try (var f = new Fixture(c, "hud-metadata")) {
            f.set(c, "health", 1e21); f.set(c, "absorption", 1e21);
            var untouched = new SynchedEntityData.DataValue<>(250, EntityDataSerializers.FLOAT, 123f);
            var packet = new ClientboundSetEntityDataPacket(f.player.getId(), List.of(
                SynchedEntityData.DataValue.create(LivingVitalsAccess.infinity$health(), f.player.getHealth()),
                SynchedEntityData.DataValue.create(PlayerVitalsAccess.infinity$absorption(), f.player.getAbsorptionAmount()), untouched));
            f.player.connection.send(packet);
            var result = (ClientboundSetEntityDataPacket)f.drain().stream().filter(p -> p instanceof ClientboundSetEntityDataPacket).reduce((a,b) -> b).orElseThrow();
            c.assertValueEqual(result.packedItems().get(0).value(), 40f, "Tracked health is compact too");
            c.assertValueEqual(result.packedItems().get(1).value(), 40f, "Tracked absorption is bounded");
            c.assertValueEqual(result.packedItems().get(2), untouched, "Other metadata is untouched");
            c.assertTrue(f.player.getAbsorptionAmount() > 1e20, "Actual absorption stays intact");
            c.assertTrue(ClientVitals.rewrite(new ClientboundSetHealthPacket(0, 20, 5), f.player) instanceof ClientboundSetHealthPacket h && h.getHealth() == 0, "Death remains zero health");
            c.assertTrue(ClientVitals.health(1, Float.MAX_VALUE) > 0, "Tiny positive percentage still displays alive");
        }
        c.succeed();
    }

    @GameTest public void sharedBundlesAndRealModifiersAreNotMutatedByHudProjection(GameTestHelper c) {
        try (var f = new Fixture(c, "hud-bundle")) {
            f.set(c, "max_health", 100);
            var instance = f.player.getAttribute(Attributes.MAX_HEALTH);
            var modifier = new AttributeModifier(Identifier.fromNamespaceAndPath("convergence_tests", "hud-bonus"), 100, AttributeModifier.Operation.ADD_VALUE);
            instance.addPermanentModifier(modifier);
            f.player.setHealth(100);
            var attributes = new ClientboundUpdateAttributesPacket(f.player.getId(), List.of(instance));
            var original = attributes.getValues().getFirst();
            var unrelated = new ClientboundUpdateAttributesPacket(f.player.getId()+1000, List.of(instance));
            var ordinary = new ClientboundSetHealthPacket(20, 20, 5);
            f.player.connection.send(new ClientboundBundlePacket(List.of(attributes, unrelated)));
            var packets = f.drain();
            c.assertFalse(packets.stream().anyMatch(p -> p instanceof ClientboundBundlePacket), "Nested bundles are flattened");
            c.assertTrue(packets.contains(unrelated), "Another entity's shared packet is preserved");
            c.assertValueEqual(attributes.getValues().getFirst(), original, "Input packet and modifiers are unchanged for other viewers");
            c.assertValueEqual(instance.getValue(), 200d, "Real modifier still applies on the server");
            c.assertTrue(packets.stream().anyMatch(p -> p instanceof ClientboundSetHealthPacket h && h.getHealth() == 20f), "Display ratio includes the effective modifier");
            instance.removeModifier(modifier.id());
            f.set(c, "max_health", 20);
            c.assertTrue(ClientVitals.rewrite(ordinary, f.player) == ordinary, "Normal health packets pass through unchanged");
        }
        c.succeed();
    }
}
