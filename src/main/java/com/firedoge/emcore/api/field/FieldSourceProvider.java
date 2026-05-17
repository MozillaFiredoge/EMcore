package com.firedoge.emcore.api.field;

import java.util.List;

import com.firedoge.emcore.api.Electromagnetics;
import net.minecraft.server.level.ServerLevel;

public interface FieldSourceProvider {
    List<FieldRegion> fieldRegions();

    List<FieldSource> fieldSources();

    default void registerFieldSources(ServerLevel level) {
        FieldAccess fields = Electromagnetics.api().fields();
        fieldRegions().forEach(region -> fields.registerRegion(level, region));
        fieldSources().forEach(source -> fields.registerSource(level, source));
    }

    default void unregisterFieldSources(ServerLevel level) {
        FieldAccess fields = Electromagnetics.api().fields();
        fieldSources().forEach(source -> fields.unregisterSource(level, source.id()));
        fieldRegions().forEach(region -> fields.unregisterRegion(level, region.id()));
    }
}
