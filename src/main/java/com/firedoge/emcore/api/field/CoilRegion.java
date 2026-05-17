package com.firedoge.emcore.api.field;

import java.util.Objects;

import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

public record CoilRegion(
        ResourceLocation id,
        ResourceLocation regionId,
        Vec3 center,
        Direction normal,
        double areaSquareMeters,
        int turns
) {
    public CoilRegion {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(regionId, "regionId");
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(normal, "normal");
        if (!Double.isFinite(center.x) || !Double.isFinite(center.y) || !Double.isFinite(center.z)) {
            throw new IllegalArgumentException("center must be finite");
        }
        if (!Double.isFinite(areaSquareMeters) || areaSquareMeters <= 0.0) {
            throw new IllegalArgumentException("areaSquareMeters must be finite and positive");
        }
        if (turns <= 0) {
            throw new IllegalArgumentException("turns must be positive");
        }
    }

    public FluxProbe asFluxProbe() {
        return new FluxProbe(id, regionId, center, normal, areaSquareMeters, turns);
    }
}
