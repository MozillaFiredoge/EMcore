package com.firedoge.emcore.api.field;

import java.util.List;
import java.util.Objects;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

public record FieldForceSample(
        ResourceLocation probeId,
        Vec3 position,
        Vec3 electricFieldVoltsPerMeter,
        Vec3 magneticFluxDensityTesla,
        Vec3 electricForceNewtons,
        Vec3 magneticForceNewtons,
        Vec3 totalForceNewtons,
        boolean stale,
        List<ResourceLocation> regionIds
) {
    public FieldForceSample {
        Objects.requireNonNull(probeId, "probeId");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(electricFieldVoltsPerMeter, "electricFieldVoltsPerMeter");
        Objects.requireNonNull(magneticFluxDensityTesla, "magneticFluxDensityTesla");
        Objects.requireNonNull(electricForceNewtons, "electricForceNewtons");
        Objects.requireNonNull(magneticForceNewtons, "magneticForceNewtons");
        Objects.requireNonNull(totalForceNewtons, "totalForceNewtons");
        regionIds = List.copyOf(regionIds);
    }
}
