package com.firedoge.emcore.api.circuit;

import java.util.List;
import java.util.Objects;

/**
 * Immutable circuit description for offline solver entry points.
 */
public record CircuitNetlist(
        List<CircuitPort> ports,
        List<CircuitTerminal> terminals,
        List<CircuitElement> elements
) {
    public CircuitNetlist {
        ports = copyChecked(ports, "ports");
        terminals = copyChecked(terminals, "terminals");
        elements = copyChecked(elements, "elements");
    }

    public CircuitNetlist(List<CircuitElement> elements) {
        this(List.of(), List.of(), elements);
    }

    public static CircuitNetlist fromSnapshot(CircuitSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        return new CircuitNetlist(snapshot.ports(), snapshot.terminals(), snapshot.elements());
    }

    private static <T> List<T> copyChecked(List<T> values, String name) {
        Objects.requireNonNull(values, name);
        for (T value : values) {
            Objects.requireNonNull(value, name + " contains null");
        }
        return List.copyOf(values);
    }
}
