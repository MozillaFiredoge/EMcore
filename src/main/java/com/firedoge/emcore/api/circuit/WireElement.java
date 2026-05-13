package com.firedoge.emcore.api.circuit;

import java.util.List;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

public record WireElement(
        ResourceLocation id,
        CircuitPort firstPort,
        CircuitPort secondPort
) implements CircuitElement {
    public WireElement {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(firstPort, "firstPort");
        Objects.requireNonNull(secondPort, "secondPort");
    }

    @Override
    public List<CircuitPort> ports() {
        return List.of(firstPort, secondPort);
    }
}
