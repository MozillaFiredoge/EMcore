package com.firedoge.emcore.internal.signal;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.firedoge.emcore.api.signal.SignalPayload;
import com.firedoge.emcore.api.signal.SignalSample;
import com.firedoge.emcore.api.signal.SignalSource;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

public final class SignalNetwork {
    private static final double TWO_PI = Math.PI * 2.0;
    private static final double MIN_PATH_DISTANCE_BLOCKS = 1.0;
    private static final double PROPAGATION_SPEED_BLOCKS_PER_SECOND = 299_792_458.0;
    private static final double AMBIENT_NOISE_WATTS = 1.0e-12;

    private final Map<ResourceLocation, SignalSource> sources = new LinkedHashMap<>();

    public void registerSource(SignalSource source) {
        SignalSource checkedSource = Objects.requireNonNull(source, "source");
        sources.put(checkedSource.id(), checkedSource);
    }

    public void unregisterSource(ResourceLocation sourceId) {
        sources.remove(Objects.requireNonNull(sourceId, "sourceId"));
    }

    public List<SignalSource> sources() {
        return List.copyOf(sources.values());
    }

    public Optional<SignalSample> sample(
            ResourceLocation channelId,
            Vec3 receiverPosition,
            double simulatedTimeSeconds
    ) {
        Objects.requireNonNull(channelId, "channelId");
        Objects.requireNonNull(receiverPosition, "receiverPosition");
        requireFiniteVector("receiverPosition", receiverPosition);
        requireFinite("simulatedTimeSeconds", simulatedTimeSeconds);

        Candidate strongest = null;
        double interferenceWatts = 0.0;

        for (SignalSource source : sources.values()) {
            if (!source.channelId().equals(channelId)) {
                continue;
            }

            Candidate candidate = candidate(source, receiverPosition);
            if (candidate.receivedPowerWatts() <= 0.0) {
                continue;
            }

            if (strongest == null || candidate.receivedPowerWatts() > strongest.receivedPowerWatts()) {
                if (strongest != null) {
                    interferenceWatts += strongest.receivedPowerWatts();
                }
                strongest = candidate;
            } else {
                interferenceWatts += candidate.receivedPowerWatts();
            }
        }

        if (strongest == null) {
            return Optional.empty();
        }

        double delaySeconds = strongest.distanceBlocks() / PROPAGATION_SPEED_BLOCKS_PER_SECOND;
        double phaseRadians = normalizeRadians(TWO_PI * strongest.source().frequencyHz()
                * (simulatedTimeSeconds - delaySeconds));
        double snrDecibels = 10.0 * Math.log10(strongest.receivedPowerWatts()
                / (AMBIENT_NOISE_WATTS + interferenceWatts));

        SignalSource source = strongest.source();
        SignalPayload payload = source.payload();
        return Optional.of(new SignalSample(
                source.channelId(),
                source.id(),
                source.position(),
                receiverPosition,
                payload,
                source.frequencyHz(),
                strongest.receivedPowerWatts(),
                snrDecibels,
                phaseRadians,
                delaySeconds,
                interferenceWatts,
                false
        ));
    }

    private static Candidate candidate(SignalSource source, Vec3 receiverPosition) {
        double distanceBlocks = source.position().distanceTo(receiverPosition);
        double pathDistanceBlocks = Math.max(MIN_PATH_DISTANCE_BLOCKS, distanceBlocks);
        double receivedPowerWatts = source.transmitPowerWatts() / (pathDistanceBlocks * pathDistanceBlocks);
        return new Candidate(source, distanceBlocks, receivedPowerWatts);
    }

    private static double normalizeRadians(double radians) {
        double normalized = radians % TWO_PI;
        if (normalized < 0.0) {
            return normalized + TWO_PI;
        }
        return normalized;
    }

    private static void requireFinite(String name, double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private static void requireFiniteVector(String name, Vec3 value) {
        if (!Double.isFinite(value.x) || !Double.isFinite(value.y) || !Double.isFinite(value.z)) {
            throw new IllegalArgumentException(name + " must be finite");
        }
    }

    private record Candidate(
            SignalSource source,
            double distanceBlocks,
            double receivedPowerWatts
    ) {
    }
}
