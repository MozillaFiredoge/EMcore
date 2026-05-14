package com.firedoge.emcore.examples.circuit;

import java.util.List;
import java.util.Objects;

import com.firedoge.emcore.api.circuit.CircuitEquationBuilder;
import com.firedoge.emcore.api.circuit.CircuitPort;
import com.firedoge.emcore.api.circuit.LinearCircuitElement;
import net.minecraft.resources.ResourceLocation;

/**
 * Minimal DC voltage-controlled current source example.
 */
public record ExampleDcVccsElement(
        ResourceLocation id,
        CircuitPort positivePort,
        CircuitPort negativePort,
        CircuitPort controlPositivePort,
        CircuitPort controlNegativePort,
        double transconductanceSiemens
) implements LinearCircuitElement {
    public ExampleDcVccsElement {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(positivePort, "positivePort");
        Objects.requireNonNull(negativePort, "negativePort");
        Objects.requireNonNull(controlPositivePort, "controlPositivePort");
        Objects.requireNonNull(controlNegativePort, "controlNegativePort");
        if (!Double.isFinite(transconductanceSiemens)) {
            throw new IllegalArgumentException("transconductanceSiemens must be finite");
        }
    }

    @Override
    public List<CircuitPort> ports() {
        return List.of(positivePort, negativePort, controlPositivePort, controlNegativePort);
    }

    @Override
    public void stamp(CircuitEquationBuilder builder) {
        builder.addVoltageControlledCurrentSource(
                positivePort,
                negativePort,
                controlPositivePort,
                controlNegativePort,
                transconductanceSiemens
        );
    }
}
