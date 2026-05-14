package com.firedoge.emcore.examples.circuit;

import java.util.List;
import java.util.Objects;

import com.firedoge.emcore.api.circuit.AcCircuitEquationBuilder;
import com.firedoge.emcore.api.circuit.AcLinearCircuitElement;
import com.firedoge.emcore.api.circuit.CircuitPhasor;
import com.firedoge.emcore.api.circuit.CircuitPort;
import net.minecraft.resources.ResourceLocation;

/**
 * Minimal AC voltage-controlled voltage source example.
 */
public record ExampleAcVcvsElement(
        ResourceLocation id,
        CircuitPort positivePort,
        CircuitPort negativePort,
        CircuitPort controlPositivePort,
        CircuitPort controlNegativePort,
        CircuitPhasor gain
) implements AcLinearCircuitElement {
    public ExampleAcVcvsElement {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(positivePort, "positivePort");
        Objects.requireNonNull(negativePort, "negativePort");
        Objects.requireNonNull(controlPositivePort, "controlPositivePort");
        Objects.requireNonNull(controlNegativePort, "controlNegativePort");
        Objects.requireNonNull(gain, "gain");
    }

    @Override
    public List<CircuitPort> ports() {
        return List.of(positivePort, negativePort, controlPositivePort, controlNegativePort);
    }

    @Override
    public void stamp(AcCircuitEquationBuilder builder) {
        builder.addVoltageControlledVoltageSource(
                positivePort,
                negativePort,
                controlPositivePort,
                controlNegativePort,
                gain
        );
    }
}
