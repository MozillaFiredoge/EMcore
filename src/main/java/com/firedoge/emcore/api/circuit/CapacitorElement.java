package com.firedoge.emcore.api.circuit;

import java.util.List;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/**
 * Ideal capacitor for AC phasor and fixed-step transient solves.
 */
public record CapacitorElement(
        ResourceLocation id,
        CircuitPort positivePort,
        CircuitPort negativePort,
        double capacitanceFarads
) implements AcLinearCircuitElement, TransientCircuitElement {
    public CapacitorElement {
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

    @Override
    public void stamp(TransientCircuitEquationBuilder builder) {
        builder.addCapacitance(positivePort, negativePort, capacitanceFarads);
    }
}
