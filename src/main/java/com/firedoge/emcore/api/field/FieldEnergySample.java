package com.firedoge.emcore.api.field;

import java.util.List;
import java.util.Objects;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

public record FieldEnergySample(
        Vec3 position,
        Vec3 electricFieldVoltsPerMeter,
        Vec3 magneticFluxDensityTesla,
        double electricEnergyDensityJoulesPerCubicMeter,
        double magneticEnergyDensityJoulesPerCubicMeter,
        double totalEnergyDensityJoulesPerCubicMeter,
        boolean stale,
        List<ResourceLocation> regionIds
) {
    public FieldEnergySample {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(electricFieldVoltsPerMeter, "electricFieldVoltsPerMeter");
        Objects.requireNonNull(magneticFluxDensityTesla, "magneticFluxDensityTesla");
        regionIds = List.copyOf(regionIds);
    }
}
