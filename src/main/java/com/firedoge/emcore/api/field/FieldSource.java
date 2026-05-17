package com.firedoge.emcore.api.field;

import java.util.Objects;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

public record FieldSource(
        ResourceLocation id,
        ResourceLocation regionId,
        FieldSourceType type,
        Vec3 position,
        double radiusMeters,
        double value,
        Vec3 vectorValue
) {
    public FieldSource {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(regionId, "regionId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(vectorValue, "vectorValue");
        if (!Double.isFinite(radiusMeters) || radiusMeters < 0.0) {
            throw new IllegalArgumentException("radiusMeters must be finite and non-negative");
        }
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("value must be finite");
        }
        if (!Double.isFinite(vectorValue.x) || !Double.isFinite(vectorValue.y) || !Double.isFinite(vectorValue.z)) {
            throw new IllegalArgumentException("vectorValue must be finite");
        }
    }

    public static FieldSource pointCharge(
            ResourceLocation id,
            ResourceLocation regionId,
            Vec3 position,
            double chargeCoulombs
    ) {
        return new FieldSource(id, regionId, FieldSourceType.POINT_CHARGE, position, 0.0, chargeCoulombs, Vec3.ZERO);
    }

    public static FieldSource chargeDensity(
            ResourceLocation id,
            ResourceLocation regionId,
            Vec3 position,
            double radiusMeters,
            double chargeDensityCoulombsPerCubicMeter
    ) {
        return new FieldSource(
                id,
                regionId,
                FieldSourceType.CHARGE_DENSITY,
                position,
                radiusMeters,
                chargeDensityCoulombsPerCubicMeter,
                Vec3.ZERO
        );
    }

    public static FieldSource potentialBoundary(
            ResourceLocation id,
            ResourceLocation regionId,
            Vec3 position,
            double radiusMeters,
            double potentialVolts
    ) {
        return new FieldSource(
                id,
                regionId,
                FieldSourceType.POTENTIAL_BOUNDARY,
                position,
                radiusMeters,
                potentialVolts,
                Vec3.ZERO
        );
    }

    public static FieldSource relativePermittivity(
            ResourceLocation id,
            ResourceLocation regionId,
            Vec3 position,
            double radiusMeters,
            double epsilonRelative
    ) {
        return new FieldSource(
                id,
                regionId,
                FieldSourceType.RELATIVE_PERMITTIVITY,
                position,
                radiusMeters,
                epsilonRelative,
                Vec3.ZERO
        );
    }

    public static FieldSource currentDensity(
            ResourceLocation id,
            ResourceLocation regionId,
            Vec3 position,
            double radiusMeters,
            Vec3 currentDensityAmpsPerSquareMeter
    ) {
        return new FieldSource(
                id,
                regionId,
                FieldSourceType.CURRENT_DENSITY,
                position,
                radiusMeters,
                0.0,
                currentDensityAmpsPerSquareMeter
        );
    }
}
