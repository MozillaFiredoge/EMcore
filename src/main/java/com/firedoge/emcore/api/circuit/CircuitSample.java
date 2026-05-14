package com.firedoge.emcore.api.circuit;

import java.util.Objects;

/**
 * Solved DC sample for one exact circuit port.
 */
public record CircuitSample(
        CircuitPort port,
        double voltageVolts,
        double currentAmps,
        double powerWatts,
        double storedEnergyJoules,
        boolean overloaded
) {
    public CircuitSample {
        Objects.requireNonNull(port, "port");
    }
}
