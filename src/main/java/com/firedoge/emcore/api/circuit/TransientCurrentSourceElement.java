package com.firedoge.emcore.api.circuit;

import java.util.List;
import java.util.Objects;
import java.util.function.DoubleUnaryOperator;
import net.minecraft.resources.ResourceLocation;

/**
 * Ideal time-domain current source for fixed-step transient solves.
 */
public record TransientCurrentSourceElement(
        ResourceLocation id,
        CircuitPort positivePort,
        CircuitPort negativePort,
        DoubleUnaryOperator currentAmps
) implements TransientCircuitElement {
    public TransientCurrentSourceElement {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(positivePort, "positivePort");
        Objects.requireNonNull(negativePort, "negativePort");
        Objects.requireNonNull(currentAmps, "currentAmps");
    }

    @Override
    public List<CircuitPort> ports() {
        return List.of(positivePort, negativePort);
    }

    @Override
    public void stamp(TransientCircuitEquationBuilder builder) {
        builder.addCurrentSource(positivePort, negativePort, currentAmps.applyAsDouble(builder.timeSeconds()));
    }
}
