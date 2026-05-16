package com.firedoge.emcore.api.signal;

import java.util.Objects;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

public record SignalSource(
        ResourceLocation id,
        ResourceLocation channelId,
        Vec3 position,
        double frequencyHz,
        double transmitPowerWatts,
        SignalPayload payload
) {
    public SignalSource {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(channelId, "channelId");
        Objects.requireNonNull(position, "position");
        requireFiniteVector("position", position);
        requireFiniteNonNegative("frequencyHz", frequencyHz);
        requireFiniteNonNegative("transmitPowerWatts", transmitPowerWatts);
        Objects.requireNonNull(payload, "payload");
    }

    private static void requireFiniteNonNegative(String name, double value) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }

    private static void requireFiniteVector(String name, Vec3 value) {
        if (!Double.isFinite(value.x) || !Double.isFinite(value.y) || !Double.isFinite(value.z)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
