package com.firedoge.emcore.api.circuit;

import java.util.List;

public record AcCircuitSnapshot(
        List<CircuitNode> nodes,
        List<CircuitPort> ports,
        List<CircuitTerminal> terminals,
        List<CircuitElement> elements,
        List<CircuitDiagnostic> diagnostics,
        List<AcCircuitSample> samples,
        double frequencyHertz,
        double simulatedTimeSeconds
) {
    public AcCircuitSnapshot {
        if (!Double.isFinite(frequencyHertz) || frequencyHertz < 0.0) {
            throw new IllegalArgumentException("frequencyHertz must be finite and non-negative");
        }

        nodes = List.copyOf(nodes);
        ports = List.copyOf(ports);
        terminals = List.copyOf(terminals);
        elements = List.copyOf(elements);
        diagnostics = List.copyOf(diagnostics);
        samples = List.copyOf(samples);
    }
}
