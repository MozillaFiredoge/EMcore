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
import com.firedoge.emcore.api.field.CoilRegion;
import com.firedoge.emcore.api.field.ElectricFieldSample;
import com.firedoge.emcore.api.field.FieldRegion;
import com.firedoge.emcore.api.field.FieldSample;
import com.firedoge.emcore.api.field.FieldSnapshot;
import com.firedoge.emcore.api.field.FieldSolveResult;
import com.firedoge.emcore.api.field.FieldSource;
import com.firedoge.emcore.api.field.FluxProbe;
import com.firedoge.emcore.api.field.FluxSample;
import com.firedoge.emcore.api.field.MagneticFieldSample;
import com.firedoge.emcore.api.signal.SignalSample;
import com.firedoge.emcore.api.signal.SignalSource;
import com.firedoge.emcore.internal.circuit.CircuitNetwork;
import com.firedoge.emcore.internal.field.FieldNetwork;
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
    private final FieldNetwork fieldNetwork = new FieldNetwork();
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
        fieldNetwork.tick();
        circuitNetwork.tick();
    }

    public long gameTicks() {
        return gameTicks;
    }

    public double simulatedTimeSeconds() {
        return simulatedTimeSeconds;
    }

    public ElectricFieldSample sampleElectricField(Vec3 position) {
        return fieldNetwork.sampleElectricField(position);
    }

    public MagneticFieldSample sampleMagneticField(Vec3 position) {
        return fieldNetwork.sampleMagneticField(position);
    }

    public double samplePotential(Vec3 position) {
        return fieldNetwork.samplePotential(position);
    }

    public double sampleMagneticFlux(BlockPos position, Direction normal) {
        return fieldNetwork.sampleMagneticFlux(position, normal);
    }

    public Optional<FluxSample> sampleFieldFlux(FluxProbe probe) {
        return fieldNetwork.sampleFlux(probe);
    }

    public Optional<FluxSample> sampleFieldCoil(ResourceLocation coilId) {
        return fieldNetwork.sampleCoil(coilId, simulatedTimeSeconds);
    }

    public void registerFieldRegion(FieldRegion region) {
        fieldNetwork.registerRegion(region);
    }

    public void unregisterFieldRegion(ResourceLocation regionId) {
        fieldNetwork.unregisterRegion(regionId);
    }

    public void registerFieldSource(FieldSource source) {
        fieldNetwork.registerSource(source);
    }

    public void unregisterFieldSource(ResourceLocation sourceId) {
        fieldNetwork.unregisterSource(sourceId);
    }

    public void registerFieldCoil(CoilRegion coil) {
        fieldNetwork.registerCoil(coil);
    }

    public void unregisterFieldCoil(ResourceLocation coilId) {
        fieldNetwork.unregisterCoil(coilId);
    }

    public List<FieldRegion> fieldRegions() {
        return fieldNetwork.regions();
    }

    public List<FieldSource> fieldSources() {
        return fieldNetwork.sources();
    }

    public List<CoilRegion> fieldCoils() {
        return fieldNetwork.coils();
    }

    public FieldSnapshot fieldSnapshot() {
        return fieldNetwork.snapshot();
    }

    public Optional<FieldSample> sampleField(Vec3 position) {
        return fieldNetwork.sample(position);
    }

    public Optional<FieldSolveResult> solveField(ResourceLocation regionId) {
        return fieldNetwork.solve(regionId);
    }

    public boolean requestFieldSolve(ResourceLocation regionId) {
        return fieldNetwork.requestSolve(regionId);
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
