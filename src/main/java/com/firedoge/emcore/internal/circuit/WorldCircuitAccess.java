package com.firedoge.emcore.internal.circuit;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.ToDoubleFunction;

import com.firedoge.emcore.api.circuit.CircuitAccess;
import com.firedoge.emcore.api.circuit.CircuitElement;
import com.firedoge.emcore.api.circuit.CircuitPort;
import com.firedoge.emcore.api.circuit.CircuitSample;
import com.firedoge.emcore.api.circuit.CircuitSnapshot;
import com.firedoge.emcore.internal.world.EmWorldManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

public final class WorldCircuitAccess implements CircuitAccess {
    private final EmWorldManager worldManager;

    public WorldCircuitAccess(EmWorldManager worldManager) {
        this.worldManager = Objects.requireNonNull(worldManager, "worldManager");
    }

    @Override
    public void registerPort(ServerLevel level, CircuitPort port) {
        worldManager.getOrCreate(level).registerCircuitPort(port);
    }

    @Override
    public void unregisterPort(ServerLevel level, CircuitPort port) {
        worldManager.getOrCreate(level).unregisterCircuitPort(port);
    }

    @Override
    public void registerElement(ServerLevel level, CircuitElement element) {
        worldManager.getOrCreate(level).registerCircuitElement(element);
    }

    @Override
    public void unregisterElement(ServerLevel level, ResourceLocation elementId) {
        worldManager.getOrCreate(level).unregisterCircuitElement(elementId);
    }

    @Override
    public CircuitSnapshot snapshot(ServerLevel level) {
        return worldManager.getOrCreate(level).circuitSnapshot();
    }

    @Override
    public Optional<CircuitSample> samplePort(ServerLevel level, CircuitPort port) {
        return worldManager.getOrCreate(level).samplePort(port);
    }

    @Override
    public OptionalDouble getVoltage(ServerLevel level, CircuitPort port) {
        return sampleDouble(level, port, CircuitSample::voltageVolts);
    }

    @Override
    public OptionalDouble getCurrent(ServerLevel level, CircuitPort port) {
        return sampleDouble(level, port, CircuitSample::currentAmps);
    }

    @Override
    public OptionalDouble getPower(ServerLevel level, CircuitPort port) {
        return sampleDouble(level, port, CircuitSample::powerWatts);
    }

    @Override
    public OptionalDouble getStoredEnergy(ServerLevel level, CircuitPort port) {
        return sampleDouble(level, port, CircuitSample::storedEnergyJoules);
    }

    private OptionalDouble sampleDouble(ServerLevel level, CircuitPort port, ToDoubleFunction<CircuitSample> mapper) {
        return samplePort(level, port)
                .map(sample -> OptionalDouble.of(mapper.applyAsDouble(sample)))
                .orElseGet(OptionalDouble::empty);
    }
}
