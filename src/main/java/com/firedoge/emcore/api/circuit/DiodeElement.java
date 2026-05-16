package com.firedoge.emcore.api.circuit;

import java.util.List;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/**
 * Shockley diode model for nonlinear operating-point and transient solves.
 */
public record DiodeElement(
        ResourceLocation id,
        CircuitPort anodePort,
        CircuitPort cathodePort,
        double saturationCurrentAmps,
        double emissionCoefficient,
        double thermalVoltageVolts
) implements NonlinearCircuitElement {
    public static final double DEFAULT_SATURATION_CURRENT_AMPS = 1.0e-12;
    public static final double DEFAULT_EMISSION_COEFFICIENT = 1.0;
    public static final double DEFAULT_THERMAL_VOLTAGE_VOLTS = 0.02585;
    private static final double MAX_EXPONENT = 40.0;
    private static final double MIN_EXPONENT = -40.0;

    public DiodeElement(ResourceLocation id, CircuitPort anodePort, CircuitPort cathodePort) {
        this(
                id,
                anodePort,
                cathodePort,
                DEFAULT_SATURATION_CURRENT_AMPS,
                DEFAULT_EMISSION_COEFFICIENT,
                DEFAULT_THERMAL_VOLTAGE_VOLTS
        );
    }

    public DiodeElement {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(anodePort, "anodePort");
        Objects.requireNonNull(cathodePort, "cathodePort");
        requireFinitePositive("saturationCurrentAmps", saturationCurrentAmps);
        requireFinitePositive("emissionCoefficient", emissionCoefficient);
        requireFinitePositive("thermalVoltageVolts", thermalVoltageVolts);
    }

    @Override
    public List<CircuitPort> ports() {
        return List.of(anodePort, cathodePort);
    }

    @Override
    public void stamp(NonlinearCircuitEquationBuilder builder) {
        double junctionVoltage = builder.voltage(anodePort, cathodePort);
        double scaleVoltage = emissionCoefficient * thermalVoltageVolts;
        double exponent = clamp(junctionVoltage / scaleVoltage, MIN_EXPONENT, MAX_EXPONENT);
        double exponential = Math.exp(exponent);
        double currentAmps = saturationCurrentAmps * Math.expm1(exponent);
        double conductanceSiemens = saturationCurrentAmps * exponential / scaleVoltage;

        builder.addLinearizedCurrent(anodePort, cathodePort, currentAmps, conductanceSiemens);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static void requireFinitePositive(String name, double value) {
        if (!Double.isFinite(value) || value <= 0.0) {
            throw new IllegalArgumentException(name + " must be finite and greater than zero");
        }
    }
}
