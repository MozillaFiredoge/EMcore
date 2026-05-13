package com.firedoge.emcore.api.circuit;

import java.util.Objects;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

public record CircuitNode(ResourceLocation id, Vec3 position) {
    public CircuitNode {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(position, "position");
    }
}
