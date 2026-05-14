package com.firedoge.emcore.api.circuit;

import java.util.List;
import java.util.Objects;

public record CircuitDiagnostic(
        CircuitDiagnosticType type,
        CircuitDiagnosticSeverity severity,
        List<CircuitPort> ports,
        String message
) {
    public CircuitDiagnostic {
        type = Objects.requireNonNull(type, "type");
        severity = Objects.requireNonNull(severity, "severity");
        ports = List.copyOf(ports);
        message = Objects.requireNonNull(message, "message");
    }
}
