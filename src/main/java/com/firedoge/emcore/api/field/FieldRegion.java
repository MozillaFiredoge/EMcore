package com.firedoge.emcore.api.field;

import java.util.Objects;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public record FieldRegion(
        ResourceLocation id,
        AABB bounds,
        double cellSizeMeters
) {
    public FieldRegion {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(bounds, "bounds");
        if (!Double.isFinite(cellSizeMeters) || cellSizeMeters <= 0.0) {
            throw new IllegalArgumentException("cellSizeMeters must be finite and positive");
        }
        if (bounds.maxX <= bounds.minX || bounds.maxY <= bounds.minY || bounds.maxZ <= bounds.minZ) {
            throw new IllegalArgumentException("bounds must have positive volume");
        }
    }

    public boolean contains(Vec3 position) {
        return bounds.contains(Objects.requireNonNull(position, "position"));
    }

    public long estimatedCellCount() {
        long xCells = cellsForAxis(bounds.maxX - bounds.minX);
        long yCells = cellsForAxis(bounds.maxY - bounds.minY);
        long zCells = cellsForAxis(bounds.maxZ - bounds.minZ);
        return xCells * yCells * zCells;
    }

    private long cellsForAxis(double sizeMeters) {
        return Math.max(1L, (long) Math.ceil(sizeMeters / cellSizeMeters));
    }
}
