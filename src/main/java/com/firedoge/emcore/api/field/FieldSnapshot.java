package com.firedoge.emcore.api.field;

import java.util.List;

import net.minecraft.resources.ResourceLocation;

public record FieldSnapshot(
        List<FieldRegion> regions,
        List<FieldSource> sources,
        List<ResourceLocation> dirtyRegionIds,
        List<FieldDiagnostic> diagnostics,
        long version,
        boolean stale
) {
    public FieldSnapshot {
        regions = List.copyOf(regions);
        sources = List.copyOf(sources);
        dirtyRegionIds = List.copyOf(dirtyRegionIds);
        diagnostics = List.copyOf(diagnostics);
    }
}
