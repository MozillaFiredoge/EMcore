package com.firedoge.emcore.api.signal;

import java.util.Objects;
import net.minecraft.resources.ResourceLocation;

public record SignalPayload(
        ResourceLocation type,
        String value
) {
    public static final ResourceLocation TEXT_TYPE = ResourceLocation.fromNamespaceAndPath("emcore", "text");

    public SignalPayload {
        Objects.requireNonNull(type, "type");
        value = Objects.requireNonNull(value, "value");
    }

    public static SignalPayload text(String value) {
        return new SignalPayload(TEXT_TYPE, value);
    }
}
