package com.firedoge.emcore.api.circuit;

import java.util.List;
import java.util.Objects;
import java.util.function.DoubleUnaryOperator;
import net.minecraft.resources.ResourceLocation;

/**
 * Ideal time-domain voltage source for fixed-step transient solves.
 */
public record TransientVoltageSourceElement(
        ResourceLocation id,
        CircuitPort positivePort,
        CircuitPort negativePort,
        DoubleUnaryOperator voltageVolts
) implements TransientCircuitElement {
    public TransientVoltageSourceElement {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(positivePort, "positivePort");
        Objects.requireNonNull(negativePort, "negativePort");
        Objects.requireNonNull(voltageVolts, "voltageVolts");
    }

    @Override
    public List<CircuitPort> ports() {
        return List.of(positivePort, negativePort);
    }

    @Override
    public void stamp(TransientCircuitEquationBuilder builder) {
        builder.addVoltageSource(positivePort, negativePort, voltageVolts.applyAsDouble(builder.timeSeconds()));
    }
}
