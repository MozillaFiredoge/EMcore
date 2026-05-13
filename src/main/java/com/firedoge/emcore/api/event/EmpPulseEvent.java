package com.firedoge.emcore.api.event;

import java.util.Objects;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

public record EmpPulseEvent(
        ServerLevel level,
        ResourceLocation sourceId,
        Vec3 origin,
        double radiusBlocks,
        double peakFieldVoltsPerMeter
) {
    public EmpPulseEvent {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(origin, "origin");
    }
}
