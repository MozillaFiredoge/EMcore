package com.firedoge.emcore.internal.world;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.firedoge.emcore.api.circuit.CircuitPort;
import com.firedoge.emcore.api.circuit.CircuitSample;
import com.firedoge.emcore.api.circuit.CircuitSnapshot;
import com.firedoge.emcore.api.field.ElectricFieldSample;
import com.firedoge.emcore.api.field.MagneticFieldSample;
import com.firedoge.emcore.api.signal.SignalSample;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public final class EmWorldState {
    private static final double GAME_TICK_SECONDS = 1.0 / 20.0;

    private final ResourceKey<Level> dimension;
    private long gameTicks;
    private double simulatedTimeSeconds;

    public EmWorldState(ResourceKey<Level> dimension) {
        this.dimension = Objects.requireNonNull(dimension, "dimension");
    }

    public ResourceKey<Level> dimension() {
        return dimension;
    }

    public void tick() {
        gameTicks++;
        simulatedTimeSeconds += GAME_TICK_SECONDS;
    }

    public long gameTicks() {
        return gameTicks;
    }

    public double simulatedTimeSeconds() {
        return simulatedTimeSeconds;
    }

    public ElectricFieldSample sampleElectricField(Vec3 position) {
        Objects.requireNonNull(position, "position");
        return new ElectricFieldSample(Vec3.ZERO, 0.0, 0.0, 0.0);
    }

    public MagneticFieldSample sampleMagneticField(Vec3 position) {
        Objects.requireNonNull(position, "position");
        return new MagneticFieldSample(Vec3.ZERO, 0.0, Vec3.ZERO, 0.0);
    }

    public double samplePotential(Vec3 position) {
        Objects.requireNonNull(position, "position");
        return 0.0;
    }

    public double sampleMagneticFlux(BlockPos position, Direction normal) {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(normal, "normal");
        return 0.0;
    }

    public CircuitSnapshot circuitSnapshot() {
        return new CircuitSnapshot(List.of(), List.of(), List.of(), simulatedTimeSeconds);
    }

    public Optional<CircuitSample> samplePort(CircuitPort port) {
        Objects.requireNonNull(port, "port");
        return Optional.empty();
    }

    public Optional<SignalSample> sampleSignal(ResourceLocation channelId, Vec3 receiverPosition) {
        Objects.requireNonNull(channelId, "channelId");
        Objects.requireNonNull(receiverPosition, "receiverPosition");
        return Optional.empty();
    }
}
