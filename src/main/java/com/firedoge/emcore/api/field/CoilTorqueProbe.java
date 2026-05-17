package com.firedoge.emcore.api.field;

import java.util.Objects;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

public record CoilTorqueProbe(
        ResourceLocation id,
        Vec3 center,
        Vec3 normal,
        double areaSquareMeters,
        int turns,
        double currentAmps
) {
    public CoilTorqueProbe {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(normal, "normal");
        requireFinite(center, "center");
        requireFinite(normal, "normal");
        if (normal.length() <= 0.0) {
            throw new IllegalArgumentException("normal must be non-zero");
        }
        if (!Double.isFinite(areaSquareMeters) || areaSquareMeters <= 0.0) {
            throw new IllegalArgumentException("areaSquareMeters must be finite and positive");
        }
        if (turns <= 0) {
            throw new IllegalArgumentException("turns must be positive");
        }
        if (!Double.isFinite(currentAmps)) {
            throw new IllegalArgumentException("currentAmps must be finite");
        }
    }

    public static CoilTorqueProbe fromCoil(CoilRegion coil, double currentAmps) {
        Objects.requireNonNull(coil, "coil");
        return new CoilTorqueProbe(
                coil.id(),
                coil.center(),
                coil.asFluxProbe().normalVector(),
                coil.areaSquareMeters(),
                coil.turns(),
                currentAmps
        );
    }

    public Vec3 magneticMomentAmpereSquareMeters() {
        return normal.normalize().scale(turns * currentAmps * areaSquareMeters);
    }

    private static void requireFinite(Vec3 vector, String name) {
        if (!Double.isFinite(vector.x) || !Double.isFinite(vector.y) || !Double.isFinite(vector.z)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
