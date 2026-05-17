package com.firedoge.emcore.api.field;

import java.util.List;
import java.util.Objects;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

public record FieldSample(
        Vec3 position,
        Vec3 electricFieldVoltsPerMeter,
        double potentialVolts,
        double chargeDensityCoulombsPerCubicMeter,
        double energyDensityJoulesPerCubicMeter,
        boolean stale,
        List<ResourceLocation> regionIds
) {
    public FieldSample {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(electricFieldVoltsPerMeter, "electricFieldVoltsPerMeter");
        regionIds = List.copyOf(regionIds);
    }

    public ElectricFieldSample toElectricFieldSample() {
        return new ElectricFieldSample(
                electricFieldVoltsPerMeter,
                potentialVolts,
                chargeDensityCoulombsPerCubicMeter,
                energyDensityJoulesPerCubicMeter
        );
    }
}
