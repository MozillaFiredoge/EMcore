package com.firedoge.emcore.api.event;

import java.util.Objects;
import com.firedoge.emcore.api.signal.SignalSample;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

public record SignalReceivedEvent(
        ServerLevel level,
        SignalSample signal,
        Vec3 receiverPosition
) {
    public SignalReceivedEvent {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(signal, "signal");
        Objects.requireNonNull(receiverPosition, "receiverPosition");
    }
}
