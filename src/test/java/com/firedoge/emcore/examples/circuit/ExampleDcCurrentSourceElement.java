package com.firedoge.emcore.examples.circuit;

import java.util.List;
import java.util.Objects;

import com.firedoge.emcore.api.circuit.CircuitEquationBuilder;
import com.firedoge.emcore.api.circuit.CircuitPort;
import com.firedoge.emcore.api.circuit.LinearCircuitElement;
import net.minecraft.resources.ResourceLocation;

/**
 * Minimal DC current source example for addon authors.
 */
public record ExampleDcCurrentSourceElement(
        ResourceLocation id,
        CircuitPort positivePort,
        CircuitPort negativePort,
        double currentAmps
) implements LinearCircuitElement {
    public ExampleDcCurrentSourceElement {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(positivePort, "positivePort");
        Objects.requireNonNull(negativePort, "negativePort");
        if (!Double.isFinite(currentAmps)) {
            throw new IllegalArgumentException("currentAmps must be finite");
        }
    }

    @Override
    public List<CircuitPort> ports() {
        return List.of(positivePort, negativePort);
    }

    @Override
    public void stamp(CircuitEquationBuilder builder) {
        builder.addCurrentSource(positivePort, negativePort, currentAmps);
    }
}
