package com.firedoge.emcore.api.circuit;

import java.util.List;

public record CircuitSnapshot(
        List<CircuitNode> nodes,
        List<CircuitPort> ports,
        List<CircuitTerminal> terminals,
        List<CircuitElement> elements,
        List<CircuitSample> samples,
        double simulatedTimeSeconds
) {
    public CircuitSnapshot {
        nodes = List.copyOf(nodes);
        ports = List.copyOf(ports);
        terminals = List.copyOf(terminals);
        elements = List.copyOf(elements);
        samples = List.copyOf(samples);
    }
}
