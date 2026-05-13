package com.firedoge.emcore.api.event;

import java.util.Objects;
import com.firedoge.emcore.api.circuit.CircuitPort;
import net.minecraft.server.level.ServerLevel;

public record OverloadEvent(
        ServerLevel level,
        CircuitPort port,
        double powerWatts,
        double limitWatts
) {
    public OverloadEvent {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(port, "port");
    }
}
