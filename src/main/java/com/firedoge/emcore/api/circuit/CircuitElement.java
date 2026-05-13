package com.firedoge.emcore.api.circuit;

import java.util.List;
import net.minecraft.resources.ResourceLocation;

public interface CircuitElement {
    ResourceLocation id();

    List<CircuitPort> ports();
}
