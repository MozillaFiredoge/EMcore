package com.firedoge.emcore.examples.circuit;

import java.util.List;
import java.util.Objects;

import com.firedoge.emcore.api.circuit.AcCircuitEquationBuilder;
import com.firedoge.emcore.api.circuit.AcLinearCircuitElement;
import com.firedoge.emcore.api.circuit.CircuitPhasor;
import com.firedoge.emcore.api.circuit.CircuitPort;
import net.minecraft.resources.ResourceLocation;

/**
 * Minimal AC phasor voltage source example.
 */
public record ExampleAcVoltageSourceElement(
        ResourceLocation id,
        CircuitPort positivePort,
        CircuitPort negativePort,
        CircuitPhasor voltageVolts
) implements AcLinearCircuitElement {
    public ExampleAcVoltageSourceElement {
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
    public void stamp(AcCircuitEquationBuilder builder) {
        builder.addVoltageSource(positivePort, negativePort, voltageVolts);
    }
}
