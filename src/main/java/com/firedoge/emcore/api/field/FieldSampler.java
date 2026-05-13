package com.firedoge.emcore.api.field;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

public interface FieldSampler {
    ElectricFieldSample sampleElectricField(ServerLevel level, Vec3 position);

    MagneticFieldSample sampleMagneticField(ServerLevel level, Vec3 position);

    double samplePotential(ServerLevel level, Vec3 position);

    double sampleMagneticFlux(ServerLevel level, BlockPos position, Direction normal);
}
