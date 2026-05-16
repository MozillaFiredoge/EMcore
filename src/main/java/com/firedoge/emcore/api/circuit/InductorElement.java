package com.firedoge.emcore.api.circuit;

import java.util.List;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/**
 * Ideal inductor for AC phasor and fixed-step transient solves.
 */
public record InductorElement(
        ResourceLocation id,
        CircuitPort positivePort,
        CircuitPort negativePort,
        double inductanceHenries
) implements AcLinearCircuitElement, TransientCircuitElement {
    public InductorElement {
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

    @Override
    public void stamp(TransientCircuitEquationBuilder builder) {
        builder.addInductance(positivePort, negativePort, inductanceHenries);
    }
}
