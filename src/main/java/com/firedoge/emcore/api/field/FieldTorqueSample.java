package com.firedoge.emcore.api.field;

import java.util.List;
import java.util.Objects;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

public record FieldTorqueSample(
        ResourceLocation probeId,
        Vec3 position,
        Vec3 magneticMomentAmpereSquareMeters,
        Vec3 magneticFluxDensityTesla,
        Vec3 torqueNewtonMeters,
        boolean stale,
        List<ResourceLocation> regionIds
) {
    public FieldTorqueSample {
        Objects.requireNonNull(probeId, "probeId");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(magneticMomentAmpereSquareMeters, "magneticMomentAmpereSquareMeters");
        Objects.requireNonNull(magneticFluxDensityTesla, "magneticFluxDensityTesla");
        Objects.requireNonNull(torqueNewtonMeters, "torqueNewtonMeters");
        regionIds = List.copyOf(regionIds);
    }
}
