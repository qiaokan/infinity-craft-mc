package dev.convergence;

import dev.convergence.mixin.LivingVitalsAccess;
import dev.convergence.mixin.PlayerVitalsAccess;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAttributesPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;

/** Bounded HUD packets only. The authoritative stats, damage and saved values stay untouched. */
public final class ClientVitals {
    static final float DISPLAY_MAXIMUM = 40;

    static float health(float value, float maximum) {
        if (!(value > 0)) return 0;
        if (maximum <= DISPLAY_MAXIMUM) return value;
        return Math.min(DISPLAY_MAXIMUM, Math.max(0.01f, (float)((double)value / maximum * DISPLAY_MAXIMUM)));
    }

    static float absorption(float value) { return Math.max(0, Math.min(DISPLAY_MAXIMUM, value)); }

    static ClientboundSetHealthPacket healthPacket(ServerPlayer player) {
        return new ClientboundSetHealthPacket(health(player.getHealth(), player.getMaxHealth()),
            Math.max(0, Math.min(20, player.getFoodData().getFoodLevel())),
            Math.max(0, Math.min(20, player.getFoodData().getSaturationLevel())));
    }

    public static Packet<?> rewrite(Packet<?> packet, ServerPlayer player) {
        if (player == null) return packet;
        if (packet instanceof ClientboundBundlePacket bundle) {
            var output = new ArrayList<Packet<? super ClientGamePacketListener>>();
            boolean changed = false;
            for (var child : bundle.subPackets()) {
                var replacement = rewrite(child, player);
                changed |= replacement != child;
                // Bundles cannot contain another bundle on the wire.
                if (replacement instanceof ClientboundBundlePacket nested) nested.subPackets().forEach(output::add);
                else output.add(playPacket(replacement));
            }
            return changed ? new ClientboundBundlePacket(output) : packet;
        }
        if (packet instanceof ClientboundSetHealthPacket update) {
            float value = health(update.getHealth(), player.getMaxHealth());
            int food = Math.max(0, Math.min(20, update.getFood()));
            float saturation = Math.max(0, Math.min(20, update.getSaturation()));
            return value == update.getHealth() && food == update.getFood() && saturation == update.getSaturation()
                ? packet : new ClientboundSetHealthPacket(value, food, saturation);
        }
        if (packet instanceof ClientboundUpdateAttributesPacket attributes && attributes.getEntityId() == player.getId()) {
            var copy = new ClientboundUpdateAttributesPacket(player.getId(), List.of());
            boolean changed = false;
            boolean capacity = false;
            for (var entry : attributes.getValues()) {
                capacity |= entry.attribute().equals(Attributes.MAX_HEALTH) || entry.attribute().equals(Attributes.MAX_ABSORPTION);
                double display = entry.attribute().equals(Attributes.MAX_HEALTH) ? player.getMaxHealth()
                    : entry.attribute().equals(Attributes.MAX_ABSORPTION) ? player.getMaxAbsorption() : -1;
                if (display > DISPLAY_MAXIMUM) {
                    copy.getValues().add(new ClientboundUpdateAttributesPacket.AttributeSnapshot(entry.attribute(), DISPLAY_MAXIMUM, List.of()));
                    changed = true;
                } else copy.getValues().add(entry);
            }
            // A capacity-only edit does not change native lastSentHealth. Refresh the HUD anyway.
            return capacity ? new ClientboundBundlePacket(List.of(changed ? copy : attributes, healthPacket(player), absorptionPacket(player))) : packet;
        }
        if (packet instanceof ClientboundSetEntityDataPacket tracker && tracker.id() == player.getId()) {
            var values = new ArrayList<SynchedEntityData.DataValue<?>>();
            boolean changed = false;
            for (var entry : tracker.packedItems()) {
                var replacement = entry;
                if (entry.id() == LivingVitalsAccess.infinity$health().id() && entry.value() instanceof Float value)
                    replacement = SynchedEntityData.DataValue.create(LivingVitalsAccess.infinity$health(), health(value, player.getMaxHealth()));
                else if (entry.id() == PlayerVitalsAccess.infinity$absorption().id() && entry.value() instanceof Float value)
                    replacement = SynchedEntityData.DataValue.create(PlayerVitalsAccess.infinity$absorption(), absorption(value));
                changed |= !replacement.equals(entry);
                values.add(replacement);
            }
            return changed ? new ClientboundSetEntityDataPacket(tracker.id(), values) : packet;
        }
        return packet;
    }

    private static ClientboundSetEntityDataPacket absorptionPacket(ServerPlayer player) {
        return new ClientboundSetEntityDataPacket(player.getId(), List.of(SynchedEntityData.DataValue.create(
            PlayerVitalsAccess.infinity$absorption(), absorption(player.getAbsorptionAmount()))));
    }

    @SuppressWarnings("unchecked")
    private static Packet<? super ClientGamePacketListener> playPacket(Packet<?> packet) {
        return (Packet<? super ClientGamePacketListener>)packet;
    }
}
