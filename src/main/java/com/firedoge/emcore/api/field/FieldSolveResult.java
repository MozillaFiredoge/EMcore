package com.firedoge.emcore.api.field;

import java.util.Objects;

import net.minecraft.resources.ResourceLocation;

public record FieldSolveResult(
        ResourceLocation regionId,
        int xSize,
        int ySize,
        int zSize,
        int cellCount,
        int sourceCount,
        int iterations,
        boolean converged,
        double toleranceVolts,
        double maxDeltaVolts,
        double maxResidual,
        long elapsedNanos
) {
    public FieldSolveResult {
        Objects.requireNonNull(regionId, "regionId");
    }

    public double elapsedMillis() {
        return elapsedNanos / 1_000_000.0;
    }
}
