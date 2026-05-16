package com.firedoge.emcore.api.circuit;

import java.util.List;

/**
 * Fixed-step offline transient solve result.
 */
public record BatchTransientResult(
        double timeStepSeconds,
        double startTimeSeconds,
        List<CircuitPort> probes,
        List<BatchTransientStep> steps,
        List<CircuitDiagnostic> diagnostics
) {
    public BatchTransientResult {
        probes = List.copyOf(probes);
        steps = List.copyOf(steps);
        diagnostics = List.copyOf(diagnostics);
    }
}
