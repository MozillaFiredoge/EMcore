package com.firedoge.emcore.api.circuit;

import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

public record CircuitTerminal(
        ResourceLocation ownerId,
        ResourceLocation terminalId,
        BlockPos nodePosition,
        CircuitPort port
) {
    public CircuitTerminal {
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(terminalId, "terminalId");
        Objects.requireNonNull(nodePosition, "nodePosition");
        Objects.requireNonNull(port, "port");

        nodePosition = nodePosition.immutable();
    }
}
