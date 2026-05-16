package com.firedoge.emcore.api.circuit;

import java.util.List;

/**
 * Probe samples and diagnostics for one fixed-step transient frame.
 */
public record BatchTransientStep(
        int stepIndex,
        double timeSeconds,
        List<CircuitNode> nodes,
        List<CircuitSample> probeSamples,
        List<CircuitDiagnostic> diagnostics
) {
    public BatchTransientStep {
        nodes = List.copyOf(nodes);
        probeSamples = List.copyOf(probeSamples);
        diagnostics = List.copyOf(diagnostics);
    }
}
