package com.firedoge.emcore.api.field;

import java.util.Objects;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

public record ChargedFieldProbe(
        ResourceLocation id,
        Vec3 position,
        double chargeCoulombs,
        Vec3 velocityMetersPerSecond
) {
    public ChargedFieldProbe {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(velocityMetersPerSecond, "velocityMetersPerSecond");
        requireFinite(position, "position");
        requireFinite(velocityMetersPerSecond, "velocityMetersPerSecond");
        if (!Double.isFinite(chargeCoulombs)) {
            throw new IllegalArgumentException("chargeCoulombs must be finite");
        }
    }

    public static ChargedFieldProbe stationary(ResourceLocation id, Vec3 position, double chargeCoulombs) {
        return new ChargedFieldProbe(id, position, chargeCoulombs, Vec3.ZERO);
    }

    private static void requireFinite(Vec3 vector, String name) {
        if (!Double.isFinite(vector.x) || !Double.isFinite(vector.y) || !Double.isFinite(vector.z)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
