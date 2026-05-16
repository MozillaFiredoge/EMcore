package com.firedoge.emcore.api.signal;

import java.util.Objects;

import com.firedoge.emcore.api.circuit.CircuitPhasor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

public record SignalSample(
        ResourceLocation channelId,
        ResourceLocation sourceId,
        Vec3 sourcePosition,
        Vec3 receiverPosition,
        SignalPayload payload,
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
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(sourcePosition, "sourcePosition");
        Objects.requireNonNull(receiverPosition, "receiverPosition");
        Objects.requireNonNull(payload, "payload");
        requireFiniteVector("sourcePosition", sourcePosition);
        requireFiniteVector("receiverPosition", receiverPosition);
        requireFiniteNonNegative("frequencyHz", frequencyHz);
        requireFiniteNonNegative("receivedPowerWatts", receivedPowerWatts);
        requireFinite("snrDecibels", snrDecibels);
        requireFinite("phaseRadians", phaseRadians);
        requireFiniteNonNegative("delaySeconds", delaySeconds);
        requireFiniteNonNegative("interferenceWatts", interferenceWatts);
    }

    public CircuitPhasor phasor(double rmsMagnitude) {
        requireFiniteNonNegative("rmsMagnitude", rmsMagnitude);
        return CircuitPhasor.of(
                rmsMagnitude * Math.cos(phaseRadians),
                rmsMagnitude * Math.sin(phaseRadians)
        );
    }

    public CircuitPhasor voltagePhasor(double referenceResistanceOhms) {
        requireFinitePositive("referenceResistanceOhms", referenceResistanceOhms);
        return phasor(Math.sqrt(receivedPowerWatts * referenceResistanceOhms));
    }

    public CircuitPhasor currentPhasor(double referenceResistanceOhms) {
        requireFinitePositive("referenceResistanceOhms", referenceResistanceOhms);
        return phasor(Math.sqrt(receivedPowerWatts / referenceResistanceOhms));
    }

    private static void requireFinite(String name, double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private static void requireFiniteNonNegative(String name, double value) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }

    private static void requireFinitePositive(String name, double value) {
        if (!Double.isFinite(value) || value <= 0.0) {
            throw new IllegalArgumentException(name + " must be finite and greater than zero");
        }
    }

    private static void requireFiniteVector(String name, Vec3 value) {
        if (!Double.isFinite(value.x) || !Double.isFinite(value.y) || !Double.isFinite(value.z)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }
}
