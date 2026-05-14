package com.firedoge.emcore.api.circuit;

import java.util.Objects;

/**
 * Solved AC phasor sample for one exact circuit port.
 */
public record AcCircuitSample(
        CircuitPort port,
        CircuitPhasor voltageVolts,
        CircuitPhasor currentAmps,
        CircuitPhasor complexPowerVoltAmps
) {
    public AcCircuitSample {
        Objects.requireNonNull(port, "port");
        Objects.requireNonNull(voltageVolts, "voltageVolts");
        Objects.requireNonNull(currentAmps, "currentAmps");
        Objects.requireNonNull(complexPowerVoltAmps, "complexPowerVoltAmps");
    }
}
