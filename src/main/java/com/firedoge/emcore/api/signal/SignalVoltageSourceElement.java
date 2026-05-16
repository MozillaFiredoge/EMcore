package com.firedoge.emcore.api.signal;

import java.util.List;
import java.util.Objects;

import com.firedoge.emcore.api.circuit.AcCircuitEquationBuilder;
import com.firedoge.emcore.api.circuit.AcLinearCircuitElement;
import com.firedoge.emcore.api.circuit.CircuitPort;
import net.minecraft.resources.ResourceLocation;

/**
 * AC adapter that stamps one sampled signal as an ideal phasor voltage source.
 */
public record SignalVoltageSourceElement(
        ResourceLocation id,
        CircuitPort positivePort,
        CircuitPort negativePort,
        SignalSample signal,
        double referenceResistanceOhms,
        double voltageGain,
        double frequencyToleranceHertz
) implements AcLinearCircuitElement {
    public static final double DEFAULT_FREQUENCY_TOLERANCE_HERTZ = 1.0e-6;

    public SignalVoltageSourceElement(
            ResourceLocation id,
            CircuitPort positivePort,
            CircuitPort negativePort,
            SignalSample signal,
            double referenceResistanceOhms
    ) {
        this(
                id,
                positivePort,
                negativePort,
                signal,
                referenceResistanceOhms,
                1.0,
                DEFAULT_FREQUENCY_TOLERANCE_HERTZ
        );
    }

    public SignalVoltageSourceElement {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(positivePort, "positivePort");
        Objects.requireNonNull(negativePort, "negativePort");
        Objects.requireNonNull(signal, "signal");
        requireFinitePositive("referenceResistanceOhms", referenceResistanceOhms);
        requireFinite("voltageGain", voltageGain);
        requireFiniteNonNegative("frequencyToleranceHertz", frequencyToleranceHertz);
    }

    @Override
    public List<CircuitPort> ports() {
        return List.of(positivePort, negativePort);
    }

    @Override
    public void stamp(AcCircuitEquationBuilder builder) {
        if (voltageGain == 0.0 || !matchesFrequency(builder.frequencyHertz())) {
            return;
        }

        builder.addVoltageSource(
                positivePort,
                negativePort,
                signal.voltagePhasor(referenceResistanceOhms).multiply(voltageGain)
        );
    }

    public boolean matchesFrequency(double frequencyHertz) {
        requireFiniteNonNegative("frequencyHertz", frequencyHertz);
        return Math.abs(frequencyHertz - signal.frequencyHz()) <= frequencyToleranceHertz;
    }

    private static void requireFinite(String name, double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private static void requireFinitePositive(String name, double value) {
        if (!Double.isFinite(value) || value <= 0.0) {
            throw new IllegalArgumentException(name + " must be finite and greater than zero");
        }
    }

    private static void requireFiniteNonNegative(String name, double value) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }
}
