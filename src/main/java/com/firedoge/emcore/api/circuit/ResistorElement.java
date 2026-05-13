package com.firedoge.emcore.api.circuit;

import java.util.List;
import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

public record ResistorElement(
        ResourceLocation id,
        CircuitPort positivePort,
        CircuitPort negativePort,
        double resistanceOhms
) implements CircuitElement {
    public ResistorElement {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(positivePort, "positivePort");
        Objects.requireNonNull(negativePort, "negativePort");

        if (!Double.isFinite(resistanceOhms) || resistanceOhms <= 0.0) {
            throw new IllegalArgumentException("resistanceOhms must be finite and greater than zero");
        }
    }

    @Override
    public List<CircuitPort> ports() {
        return List.of(positivePort, negativePort);
    }
}
