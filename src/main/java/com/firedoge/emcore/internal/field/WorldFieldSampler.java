package com.firedoge.emcore.internal.field;

import java.util.Objects;

import com.firedoge.emcore.api.field.ElectricFieldSample;
import com.firedoge.emcore.api.field.FieldSampler;
import com.firedoge.emcore.api.field.MagneticFieldSample;
import com.firedoge.emcore.internal.world.EmWorldManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

public final class WorldFieldSampler implements FieldSampler {
    private final EmWorldManager worldManager;

    public WorldFieldSampler(EmWorldManager worldManager) {
        this.worldManager = Objects.requireNonNull(worldManager, "worldManager");
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
