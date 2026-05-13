package com.firedoge.emcore.api;

import com.firedoge.emcore.api.circuit.CircuitAccess;
import com.firedoge.emcore.api.event.ElectromagneticEventListener;
import com.firedoge.emcore.api.field.FieldSampler;
import com.firedoge.emcore.api.signal.SignalAccess;
import net.minecraft.resources.ResourceLocation;

public interface ElectromagneticsApi {
    FieldSampler fields();

    CircuitAccess circuits();

    SignalAccess signals();

    void registerListener(ResourceLocation ownerId, ElectromagneticEventListener listener);

    void unregisterListener(ResourceLocation ownerId);
}
