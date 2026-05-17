package com.firedoge.emcore.api.field;

import java.util.Objects;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

public record CurrentSegmentProbe(
        ResourceLocation id,
        Vec3 center,
        Vec3 direction,
        double lengthMeters,
        double currentAmps
) {
    public CurrentSegmentProbe {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(direction, "direction");
        requireFinite(center, "center");
        requireFinite(direction, "direction");
        if (direction.length() <= 0.0) {
            throw new IllegalArgumentException("direction must be non-zero");
        }
        if (!Double.isFinite(lengthMeters) || lengthMeters <= 0.0) {
            throw new IllegalArgumentException("lengthMeters must be finite and positive");
        }
        if (!Double.isFinite(currentAmps)) {
            throw new IllegalArgumentException("currentAmps must be finite");
        }
    }

    public Vec3 lengthVectorMeters() {
        return direction.normalize().scale(lengthMeters);
    }

    private static void requireFinite(Vec3 vector, String name) {
        if (!Double.isFinite(vector.x) || !Double.isFinite(vector.y) || !Double.isFinite(vector.z)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
