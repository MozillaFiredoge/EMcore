package com.firedoge.emcore.internal.field;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.firedoge.emcore.api.field.ChargedFieldProbe;
import com.firedoge.emcore.api.field.CircuitDrivenFieldSource;
import com.firedoge.emcore.api.field.CoilRegion;
import com.firedoge.emcore.api.field.CoilTorqueProbe;
import com.firedoge.emcore.api.field.CurrentSegmentProbe;
import com.firedoge.emcore.api.field.ElectricFieldSample;
import com.firedoge.emcore.api.field.FieldAccess;
import com.firedoge.emcore.api.field.FieldEnergySample;
import com.firedoge.emcore.api.field.FieldForceSample;
import com.firedoge.emcore.api.field.FieldRegion;
import com.firedoge.emcore.api.field.FieldSample;
import com.firedoge.emcore.api.field.FieldSnapshot;
import com.firedoge.emcore.api.field.FieldSolveResult;
import com.firedoge.emcore.api.field.FieldSource;
import com.firedoge.emcore.api.field.FieldTorqueSample;
import com.firedoge.emcore.api.field.FluxProbe;
import com.firedoge.emcore.api.field.FluxSample;
import com.firedoge.emcore.api.field.MagneticFieldSample;
import com.firedoge.emcore.internal.world.EmWorldManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

public final class WorldFieldAccess implements FieldAccess {
    private final EmWorldManager worldManager;

    public WorldFieldAccess(EmWorldManager worldManager) {
        this.worldManager = Objects.requireNonNull(worldManager, "worldManager");
    }

    @Override
    public void registerRegion(ServerLevel level, FieldRegion region) {
        worldManager.getOrCreate(level).registerFieldRegion(region);
    }

    @Override
    public void unregisterRegion(ServerLevel level, ResourceLocation regionId) {
        worldManager.getOrCreate(level).unregisterFieldRegion(regionId);
    }

    @Override
    public void registerSource(ServerLevel level, FieldSource source) {
        worldManager.getOrCreate(level).registerFieldSource(source);
    }

    @Override
    public void unregisterSource(ServerLevel level, ResourceLocation sourceId) {
        worldManager.getOrCreate(level).unregisterFieldSource(sourceId);
    }

    @Override
    public void registerCoil(ServerLevel level, CoilRegion coil) {
        worldManager.getOrCreate(level).registerFieldCoil(coil);
    }

    @Override
    public void unregisterCoil(ServerLevel level, ResourceLocation coilId) {
        worldManager.getOrCreate(level).unregisterFieldCoil(coilId);
    }

    @Override
    public void registerCircuitDrivenSource(ServerLevel level, CircuitDrivenFieldSource source) {
        worldManager.getOrCreate(level).registerCircuitDrivenFieldSource(source);
    }

    @Override
    public void unregisterCircuitDrivenSource(ServerLevel level, ResourceLocation sourceId) {
        worldManager.getOrCreate(level).unregisterCircuitDrivenFieldSource(sourceId);
    }

    @Override
    public List<FieldRegion> regions(ServerLevel level) {
        return worldManager.getOrCreate(level).fieldRegions();
    }

    @Override
    public List<FieldSource> sources(ServerLevel level) {
        return worldManager.getOrCreate(level).fieldSources();
    }

    @Override
    public List<CoilRegion> coils(ServerLevel level) {
        return worldManager.getOrCreate(level).fieldCoils();
    }

    @Override
    public List<CircuitDrivenFieldSource> circuitDrivenSources(ServerLevel level) {
        return worldManager.getOrCreate(level).circuitDrivenFieldSources();
    }

    @Override
    public FieldSnapshot snapshot(ServerLevel level) {
        return worldManager.getOrCreate(level).fieldSnapshot();
    }

    @Override
    public Optional<FieldSample> sample(ServerLevel level, Vec3 position) {
        return worldManager.getOrCreate(level).sampleField(position);
    }

    @Override
    public Optional<FieldEnergySample> sampleEnergy(ServerLevel level, Vec3 position) {
        return worldManager.getOrCreate(level).sampleFieldEnergy(position);
    }

    @Override
    public Optional<FieldForceSample> sampleForce(ServerLevel level, ChargedFieldProbe probe) {
        return worldManager.getOrCreate(level).sampleFieldForce(probe);
    }

    @Override
    public Optional<FieldForceSample> sampleForce(ServerLevel level, CurrentSegmentProbe probe) {
        return worldManager.getOrCreate(level).sampleFieldForce(probe);
    }

    @Override
    public Optional<FieldTorqueSample> sampleTorque(ServerLevel level, CoilTorqueProbe probe) {
        return worldManager.getOrCreate(level).sampleFieldTorque(probe);
    }

    @Override
    public Optional<FieldTorqueSample> sampleCoilTorque(ServerLevel level, ResourceLocation coilId, double currentAmps) {
        return worldManager.getOrCreate(level).sampleFieldCoilTorque(coilId, currentAmps);
    }

    @Override
    public Optional<FluxSample> sampleFlux(ServerLevel level, FluxProbe probe) {
        return worldManager.getOrCreate(level).sampleFieldFlux(probe);
    }

    @Override
    public Optional<FluxSample> sampleCoil(ServerLevel level, ResourceLocation coilId) {
        return worldManager.getOrCreate(level).sampleFieldCoil(coilId);
    }

    @Override
    public Optional<FieldSolveResult> solve(ServerLevel level, ResourceLocation regionId) {
        return worldManager.getOrCreate(level).solveField(regionId);
    }

    @Override
    public boolean requestSolve(ServerLevel level, ResourceLocation regionId) {
        return worldManager.getOrCreate(level).requestFieldSolve(regionId);
    }

    @Override
    public ElectricFieldSample sampleElectricField(ServerLevel level, Vec3 position) {
        return worldManager.getOrCreate(level).sampleElectricField(position);
    }

    @Override
    public MagneticFieldSample sampleMagneticField(ServerLevel level, Vec3 position) {
        return worldManager.getOrCreate(level).sampleMagneticField(position);
    }

    @Override
    public double samplePotential(ServerLevel level, Vec3 position) {
        return worldManager.getOrCreate(level).samplePotential(position);
    }

    @Override
    public double sampleMagneticFlux(ServerLevel level, BlockPos position, Direction normal) {
        return worldManager.getOrCreate(level).sampleMagneticFlux(position, normal);
    }
}
