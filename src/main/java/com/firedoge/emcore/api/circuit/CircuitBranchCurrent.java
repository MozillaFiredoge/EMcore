package com.firedoge.emcore.api.circuit;

import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

/**
 * Identifies a branch-current unknown. Current direction is from the branch positive port to negative port.
 */
public record CircuitBranchCurrent(ResourceLocation id) {
    public CircuitBranchCurrent {
        Objects.requireNonNull(id, "id");
    }
}
