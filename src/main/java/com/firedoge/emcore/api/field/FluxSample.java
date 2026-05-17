package com.firedoge.emcore.api.field;

import java.util.Objects;
import java.util.OptionalDouble;

import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

public record FluxSample(
        ResourceLocation probeId,
        ResourceLocation regionId,
        Vec3 center,
        Direction normal,
        double areaSquareMeters,
        int turns,
        Vec3 fluxDensityTesla,
        double fluxWebers,
        double fluxLinkageWebers,
        OptionalDouble inducedVoltageVolts,
        boolean stale
) {
    public FluxSample {
        Objects.requireNonNull(probeId, "probeId");
        Objects.requireNonNull(regionId, "regionId");
        Objects.requireNonNull(center, "center");
        Objects.requireNonNull(normal, "normal");
        Objects.requireNonNull(fluxDensityTesla, "fluxDensityTesla");
        inducedVoltageVolts = Objects.requireNonNull(inducedVoltageVolts, "inducedVoltageVolts");
    }

    public double normalFluxDensityTesla() {
        return fluxWebers / areaSquareMeters;
    }

    public FluxSample withInducedVoltage(OptionalDouble inducedVoltageVolts) {
        return new FluxSample(
                probeId,
                regionId,
                center,
                normal,
                areaSquareMeters,
                turns,
                fluxDensityTesla,
                fluxWebers,
                fluxLinkageWebers,
                inducedVoltageVolts,
                stale
        );
    }
}
