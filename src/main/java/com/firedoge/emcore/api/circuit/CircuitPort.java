package com.firedoge.emcore.api.circuit;

import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

public record CircuitPort(
        ResourceLocation ownerId,
        ResourceLocation portId,
        BlockPos position,
        Direction side
) {
    public CircuitPort {
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(portId, "portId");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(side, "side");
    }
}
