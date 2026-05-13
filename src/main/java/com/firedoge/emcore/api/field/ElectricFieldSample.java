package com.firedoge.emcore.api.field;

import java.util.Objects;
import net.minecraft.world.phys.Vec3;

public record ElectricFieldSample(
        Vec3 fieldVoltsPerMeter,
        double potentialVolts,
        double chargeDensityCoulombsPerCubicMeter,
        double energyDensityJoulesPerCubicMeter
) {
    public ElectricFieldSample {
        Objects.requireNonNull(fieldVoltsPerMeter, "fieldVoltsPerMeter");
    }
}
