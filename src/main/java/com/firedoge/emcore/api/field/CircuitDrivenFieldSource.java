package com.firedoge.emcore.api.field;

import java.util.Objects;

import com.firedoge.emcore.api.circuit.CircuitPort;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

public record CircuitDrivenFieldSource(
        ResourceLocation id,
        ResourceLocation regionId,
        CircuitPort currentPort,
        Vec3 position,
        double radiusMeters,
        Vec3 currentDensityPerAmp
) {
    public CircuitDrivenFieldSource {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(regionId, "regionId");
        Objects.requireNonNull(currentPort, "currentPort");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(currentDensityPerAmp, "currentDensityPerAmp");
        if (!Double.isFinite(position.x) || !Double.isFinite(position.y) || !Double.isFinite(position.z)) {
            throw new IllegalArgumentException("position must be finite");
        }
        if (!Double.isFinite(radiusMeters) || radiusMeters < 0.0) {
            throw new IllegalArgumentException("radiusMeters must be finite and non-negative");
        }
        if (!Double.isFinite(currentDensityPerAmp.x)
                || !Double.isFinite(currentDensityPerAmp.y)
                || !Double.isFinite(currentDensityPerAmp.z)) {
            throw new IllegalArgumentException("currentDensityPerAmp must be finite");
        }
    }

    public static CircuitDrivenFieldSource currentDensityAlong(
            ResourceLocation id,
            ResourceLocation regionId,
            CircuitPort currentPort,
            Vec3 position,
            double radiusMeters,
            Vec3 direction,
            double crossSectionAreaSquareMeters
    ) {
        Objects.requireNonNull(direction, "direction");
        if (!Double.isFinite(direction.x) || !Double.isFinite(direction.y) || !Double.isFinite(direction.z)) {
            throw new IllegalArgumentException("direction must be finite");
        }
        double length = direction.length();
        if (length <= 0.0) {
            throw new IllegalArgumentException("direction must be non-zero");
        }
        if (!Double.isFinite(crossSectionAreaSquareMeters) || crossSectionAreaSquareMeters <= 0.0) {
            throw new IllegalArgumentException("crossSectionAreaSquareMeters must be finite and positive");
        }

        Vec3 currentDensityPerAmp = direction.scale(1.0 / (length * crossSectionAreaSquareMeters));
        return new CircuitDrivenFieldSource(
                id,
                regionId,
                currentPort,
                position,
                radiusMeters,
                currentDensityPerAmp
        );
    }

    public FieldSource fieldSource(double currentAmps) {
        if (!Double.isFinite(currentAmps)) {
            throw new IllegalArgumentException("currentAmps must be finite");
        }
        return FieldSource.currentDensity(
                id,
                regionId,
                position,
                radiusMeters,
                currentDensityPerAmp.scale(currentAmps)
        );
    }
}
