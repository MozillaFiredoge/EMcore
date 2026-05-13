package com.firedoge.emcore.api.field;

import java.util.Objects;
import net.minecraft.world.phys.Vec3;

public record MagneticFieldSample(
        Vec3 fluxDensityTesla,
        double fluxWebers,
        Vec3 gradientTeslaPerMeter,
        double shieldingCoefficient
) {
    public MagneticFieldSample {
        Objects.requireNonNull(fluxDensityTesla, "fluxDensityTesla");
        Objects.requireNonNull(gradientTeslaPerMeter, "gradientTeslaPerMeter");
    }
}
