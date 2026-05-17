package com.firedoge.emcore.api.field;

import java.util.List;
import java.util.Optional;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

public interface FieldAccess extends FieldSampler {
    void registerRegion(ServerLevel level, FieldRegion region);

    void unregisterRegion(ServerLevel level, ResourceLocation regionId);

    void registerSource(ServerLevel level, FieldSource source);

    void unregisterSource(ServerLevel level, ResourceLocation sourceId);

    void registerCoil(ServerLevel level, CoilRegion coil);

    void unregisterCoil(ServerLevel level, ResourceLocation coilId);

    void registerCircuitDrivenSource(ServerLevel level, CircuitDrivenFieldSource source);

    void unregisterCircuitDrivenSource(ServerLevel level, ResourceLocation sourceId);

    List<FieldRegion> regions(ServerLevel level);

    List<FieldSource> sources(ServerLevel level);

    List<CoilRegion> coils(ServerLevel level);

    List<CircuitDrivenFieldSource> circuitDrivenSources(ServerLevel level);

    FieldSnapshot snapshot(ServerLevel level);

    Optional<FieldSample> sample(ServerLevel level, Vec3 position);

    Optional<FieldEnergySample> sampleEnergy(ServerLevel level, Vec3 position);

    Optional<FieldForceSample> sampleForce(ServerLevel level, ChargedFieldProbe probe);

    Optional<FieldForceSample> sampleForce(ServerLevel level, CurrentSegmentProbe probe);

    Optional<FieldTorqueSample> sampleTorque(ServerLevel level, CoilTorqueProbe probe);

    Optional<FieldTorqueSample> sampleCoilTorque(ServerLevel level, ResourceLocation coilId, double currentAmps);

    Optional<FluxSample> sampleFlux(ServerLevel level, FluxProbe probe);

    Optional<FluxSample> sampleCoil(ServerLevel level, ResourceLocation coilId);

    Optional<FieldSolveResult> solve(ServerLevel level, ResourceLocation regionId);

    boolean requestSolve(ServerLevel level, ResourceLocation regionId);
}
