package com.firedoge.emcore.api.field;

import java.util.List;
import java.util.Objects;

import net.minecraft.resources.ResourceLocation;

public record FieldDiagnostic(
        FieldDiagnosticType type,
        FieldDiagnosticSeverity severity,
        List<ResourceLocation> regionIds,
        String message
) {
    public FieldDiagnostic {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(severity, "severity");
        regionIds = List.copyOf(regionIds);
        Objects.requireNonNull(message, "message");
    }
}
