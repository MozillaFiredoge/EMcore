package com.firedoge.emcore.examples.circuit;

import java.util.List;
import java.util.Objects;

import com.firedoge.emcore.api.circuit.AcCircuitEquationBuilder;
import com.firedoge.emcore.api.circuit.AcLinearCircuitElement;
import com.firedoge.emcore.api.circuit.CircuitPhasor;
import com.firedoge.emcore.api.circuit.CircuitPort;
import net.minecraft.resources.ResourceLocation;

/**
 * Minimal ideal capacitor example. It stamps Y = j * omega * C.
 */
public record ExampleAcCapacitorElement(
        ResourceLocation id,
        CircuitPort positivePort,
        CircuitPort negativePort,
        double capacitanceFarads
) implements AcLinearCircuitElement {
    public ExampleAcCapacitorElement {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(positivePort, "positivePort");
        Objects.requireNonNull(negativePort, "negativePort");
        if (!Double.isFinite(capacitanceFarads) || capacitanceFarads <= 0.0) {
            throw new IllegalArgumentException("capacitanceFarads must be finite and greater than zero");
        }
    }

    @Override
    public List<CircuitPort> ports() {
        return List.of(positivePort, negativePort);
    }

    @Override
    public void stamp(AcCircuitEquationBuilder builder) {
        double susceptanceSiemens = builder.angularFrequencyRadiansPerSecond() * capacitanceFarads;
        builder.addAdmittance(positivePort, negativePort, CircuitPhasor.of(0.0, susceptanceSiemens));
    }
}
