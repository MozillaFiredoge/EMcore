package com.firedoge.emcore.examples.circuit;

import java.util.List;
import java.util.Objects;

import com.firedoge.emcore.api.circuit.AcCircuitEquationBuilder;
import com.firedoge.emcore.api.circuit.AcLinearCircuitElement;
import com.firedoge.emcore.api.circuit.CircuitPhasor;
import com.firedoge.emcore.api.circuit.CircuitPort;
import net.minecraft.resources.ResourceLocation;

/**
 * Minimal ideal inductor example. It stamps Y = 1 / (j * omega * L).
 */
public record ExampleAcInductorElement(
        ResourceLocation id,
        CircuitPort positivePort,
        CircuitPort negativePort,
        double inductanceHenries
) implements AcLinearCircuitElement {
    public ExampleAcInductorElement {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(positivePort, "positivePort");
        Objects.requireNonNull(negativePort, "negativePort");
        if (!Double.isFinite(inductanceHenries) || inductanceHenries <= 0.0) {
            throw new IllegalArgumentException("inductanceHenries must be finite and greater than zero");
        }
    }

    @Override
    public List<CircuitPort> ports() {
        return List.of(positivePort, negativePort);
    }

    @Override
    public void stamp(AcCircuitEquationBuilder builder) {
        double omega = builder.angularFrequencyRadiansPerSecond();
        if (omega == 0.0) {
            throw new IllegalArgumentException("ideal AC inductor requires positive frequency");
        }

        builder.addAdmittance(positivePort, negativePort, CircuitPhasor.of(0.0, -1.0 / (omega * inductanceHenries)));
    }
}
