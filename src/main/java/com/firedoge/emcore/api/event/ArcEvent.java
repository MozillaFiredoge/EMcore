package com.firedoge.emcore.api.event;

import java.util.Objects;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

public record ArcEvent(
        ServerLevel level,
        ResourceLocation sourceId,
        Vec3 start,
        Vec3 end,
        double currentAmps,
        double energyJoules
) {
    public ArcEvent {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
    }
}
