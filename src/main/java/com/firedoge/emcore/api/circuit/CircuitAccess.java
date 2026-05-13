package com.firedoge.emcore.api.circuit;

import java.util.Optional;
import java.util.OptionalDouble;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

public interface CircuitAccess {
    void registerPort(ServerLevel level, CircuitPort port);

    void unregisterPort(ServerLevel level, CircuitPort port);

    void registerElement(ServerLevel level, CircuitElement element);

    void unregisterElement(ServerLevel level, ResourceLocation elementId);

    CircuitSnapshot snapshot(ServerLevel level);

    Optional<CircuitSample> samplePort(ServerLevel level, CircuitPort port);

    OptionalDouble getVoltage(ServerLevel level, CircuitPort port);

    OptionalDouble getCurrent(ServerLevel level, CircuitPort port);

    OptionalDouble getPower(ServerLevel level, CircuitPort port);

    OptionalDouble getStoredEnergy(ServerLevel level, CircuitPort port);
}
