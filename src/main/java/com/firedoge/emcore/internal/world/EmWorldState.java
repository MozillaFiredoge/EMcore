package com.firedoge.emcore.internal.world;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.firedoge.emcore.api.circuit.AcCircuitSample;
import com.firedoge.emcore.api.circuit.AcCircuitSnapshot;
import com.firedoge.emcore.api.circuit.CircuitElement;
import com.firedoge.emcore.api.circuit.CircuitPort;
import com.firedoge.emcore.api.circuit.CircuitSample;
import com.firedoge.emcore.api.circuit.CircuitSnapshot;
import com.firedoge.emcore.api.circuit.CircuitTerminal;
import com.firedoge.emcore.api.field.ElectricFieldSample;
import com.firedoge.emcore.api.field.MagneticFieldSample;
import com.firedoge.emcore.api.signal.SignalSample;
import com.firedoge.emcore.api.signal.SignalSource;
import com.firedoge.emcore.internal.circuit.CircuitNetwork;
import com.firedoge.emcore.internal.signal.SignalNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

public final class EmWorldState {
    private static final double GAME_TICK_SECONDS = 1.0 / 20.0;

    private final ResourceKey<Level> dimension;
    private final CircuitNetwork circuitNetwork = new CircuitNetwork();
    private final SignalNetwork signalNetwork = new SignalNetwork();
    private long gameTicks;
    private double simulatedTimeSeconds;
    private double transientTimeSeconds;

    public EmWorldState(ResourceKey<Level> dimension) {
        this.dimension = Objects.requireNonNull(dimension, "dimension");
    }

    public ResourceKey<Level> dimension() {
        return dimension;
    }

    public void tick() {
        gameTicks++;
        simulatedTimeSeconds += GAME_TICK_SECONDS;
        circuitNetwork.tick();
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
        return circuitNetwork.snapshot(simulatedTimeSeconds);
    }

    public AcCircuitSnapshot acCircuitSnapshot(double frequencyHertz) {
        return circuitNetwork.acSnapshot(frequencyHertz, simulatedTimeSeconds);
    }

    public CircuitSnapshot stepTransientCircuit(double timeStepSeconds) {
        transientTimeSeconds += timeStepSeconds;
        return circuitNetwork.stepTransient(timeStepSeconds, transientTimeSeconds);
    }

    public Optional<CircuitSample> samplePort(CircuitPort port) {
        return circuitNetwork.samplePort(port);
    }

    public Optional<AcCircuitSample> sampleAcPort(CircuitPort port, double frequencyHertz) {
        return circuitNetwork.sampleAcPort(port, frequencyHertz, simulatedTimeSeconds);
    }

    public void registerCircuitPort(CircuitPort port) {
        circuitNetwork.registerPort(port);
    }

    public void unregisterCircuitPort(CircuitPort port) {
        circuitNetwork.unregisterPort(port);
    }

    public void registerCircuitTerminal(CircuitTerminal terminal) {
        circuitNetwork.registerTerminal(terminal);
    }

    public void unregisterCircuitTerminal(CircuitTerminal terminal) {
        circuitNetwork.unregisterTerminal(terminal);
    }

    public void registerCircuitElement(CircuitElement element) {
        circuitNetwork.registerElement(element);
    }

    public void unregisterCircuitElement(ResourceLocation elementId) {
        circuitNetwork.unregisterElement(elementId);
    }

    public void registerSignalSource(SignalSource source) {
        signalNetwork.registerSource(source);
    }

    public void unregisterSignalSource(ResourceLocation sourceId) {
        signalNetwork.unregisterSource(sourceId);
    }

    public List<SignalSource> signalSources() {
        return signalNetwork.sources();
    }

    public Optional<SignalSample> sampleSignal(ResourceLocation channelId, Vec3 receiverPosition) {
        return signalNetwork.sample(channelId, receiverPosition, simulatedTimeSeconds);
    }
}
