package com.firedoge.emcore.api.circuit;

import java.util.List;
import java.util.Objects;

/**
 * Fixed-step offline transient solve request.
 */
public record BatchTransientRequest(
        CircuitNetlist netlist,
        double timeStepSeconds,
        int steps,
        double startTimeSeconds,
        List<CircuitPort> probes
) {
    public BatchTransientRequest {
        Objects.requireNonNull(netlist, "netlist");
        if (!Double.isFinite(timeStepSeconds) || timeStepSeconds <= 0.0) {
            throw new IllegalArgumentException("timeStepSeconds must be finite and greater than zero");
        }
        if (steps <= 0) {
            throw new IllegalArgumentException("steps must be greater than zero");
        }
        if (!Double.isFinite(startTimeSeconds)) {
            throw new IllegalArgumentException("startTimeSeconds must be finite");
        }
        Objects.requireNonNull(probes, "probes");
        for (CircuitPort probe : probes) {
            Objects.requireNonNull(probe, "probes contains null");
        }
        probes = List.copyOf(probes);
    }

    public BatchTransientRequest(
            CircuitNetlist netlist,
            double timeStepSeconds,
            int steps,
            List<CircuitPort> probes
    ) {
        this(netlist, timeStepSeconds, steps, 0.0, probes);
    }
}
