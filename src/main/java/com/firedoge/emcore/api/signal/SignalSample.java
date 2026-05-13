package com.firedoge.emcore.api.signal;

import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

public record SignalSample(
        ResourceLocation channelId,
        double frequencyHz,
        double receivedPowerWatts,
        double snrDecibels,
        double phaseRadians,
        double delaySeconds,
        double interferenceWatts,
        boolean shielded
) {
    public SignalSample {
        Objects.requireNonNull(channelId, "channelId");
    }
}
