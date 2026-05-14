package com.firedoge.emcore.examples.circuit;

import java.util.List;
import java.util.Objects;

import com.firedoge.emcore.api.circuit.CircuitPort;
import com.firedoge.emcore.api.circuit.CircuitTopologyBuilder;
import com.firedoge.emcore.api.circuit.CircuitTopologyElement;
import net.minecraft.resources.ResourceLocation;

/**
 * Minimal topology-only switch example. Closed switches collapse both ports into one node.
 */
public record ExampleClosedSwitchElement(
        ResourceLocation id,
        CircuitPort firstPort,
        CircuitPort secondPort,
        boolean closed
) implements CircuitTopologyElement {
    public ExampleClosedSwitchElement {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(firstPort, "firstPort");
        Objects.requireNonNull(secondPort, "secondPort");
    }

    @Override
    public List<CircuitPort> ports() {
        return List.of(firstPort, secondPort);
    }

    @Override
    public void buildTopology(CircuitTopologyBuilder builder) {
        if (closed) {
            builder.connectIdeal(firstPort, secondPort);
        }
    }
}
