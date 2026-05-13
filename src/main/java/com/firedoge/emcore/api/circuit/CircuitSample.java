package com.firedoge.emcore.api.circuit;

import java.util.Objects;

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
