package com.firedoge.emcore.api.circuit;

import java.util.List;

public record CircuitSnapshot(
        List<CircuitNode> nodes,
        List<CircuitPort> ports,
        List<CircuitSample> samples,
        double simulatedTimeSeconds
) {
    public CircuitSnapshot {
        nodes = List.copyOf(nodes);
        ports = List.copyOf(ports);
        samples = List.copyOf(samples);
    }
}
