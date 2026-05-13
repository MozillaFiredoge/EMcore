package com.firedoge.emcore.api.circuit;

import java.util.Collection;
import java.util.List;

import com.firedoge.emcore.api.Electromagnetics;
import net.minecraft.server.level.ServerLevel;

public interface CircuitElementProvider {
    Collection<CircuitPort> circuitPorts();

    Collection<CircuitElement> circuitElements();

    default Collection<CircuitTerminal> circuitTerminals() {
        return List.of();
    }

    default void registerCircuitElements(ServerLevel level) {
        CircuitAccess circuits = Electromagnetics.api().circuits();

        circuitPorts().forEach(port -> circuits.registerPort(level, port));
        circuitTerminals().forEach(terminal -> circuits.registerTerminal(level, terminal));
        circuitElements().forEach(element -> circuits.registerElement(level, element));
    }

    default void unregisterCircuitElements(ServerLevel level) {
        CircuitAccess circuits = Electromagnetics.api().circuits();

        circuitElements().forEach(element -> circuits.unregisterElement(level, element.id()));
        circuitTerminals().forEach(terminal -> circuits.unregisterTerminal(level, terminal));
        circuitPorts().forEach(port -> circuits.unregisterPort(level, port));
    }
}
