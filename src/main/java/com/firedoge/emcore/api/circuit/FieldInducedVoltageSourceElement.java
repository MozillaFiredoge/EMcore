package com.firedoge.emcore.api.circuit;

import java.util.List;
import java.util.Objects;

import net.minecraft.resources.ResourceLocation;

/**
 * Ideal voltage source driven by the induced voltage of a registered field coil.
 */
public record FieldInducedVoltageSourceElement(
        ResourceLocation id,
        CircuitPort positivePort,
        CircuitPort negativePort,
        ResourceLocation coilId,
        double voltageScale
) implements CircuitElement {
    public FieldInducedVoltageSourceElement(
            ResourceLocation id,
            CircuitPort positivePort,
            CircuitPort negativePort,
            ResourceLocation coilId
    ) {
        this(id, positivePort, negativePort, coilId, 1.0);
    }

    public FieldInducedVoltageSourceElement {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(positivePort, "positivePort");
        Objects.requireNonNull(negativePort, "negativePort");
        Objects.requireNonNull(coilId, "coilId");

        if (!Double.isFinite(voltageScale)) {
            throw new IllegalArgumentException("voltageScale must be finite");
        }
    }

    @Override
    public List<CircuitPort> ports() {
        return List.of(positivePort, negativePort);
    }
}
