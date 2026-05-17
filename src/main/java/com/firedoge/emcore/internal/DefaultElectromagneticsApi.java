package com.firedoge.emcore.internal;

import java.util.Objects;

import com.firedoge.emcore.api.ElectromagneticsApi;
import com.firedoge.emcore.api.circuit.CircuitAccess;
import com.firedoge.emcore.api.event.ElectromagneticEventListener;
import com.firedoge.emcore.api.field.FieldAccess;
import com.firedoge.emcore.api.signal.SignalAccess;
import com.firedoge.emcore.internal.circuit.WorldCircuitAccess;
import com.firedoge.emcore.internal.field.WorldFieldAccess;
import com.firedoge.emcore.internal.signal.WorldSignalAccess;
import com.firedoge.emcore.internal.world.EmWorldManager;
import net.minecraft.resources.ResourceLocation;

public final class DefaultElectromagneticsApi implements ElectromagneticsApi {
    private final EmWorldManager worldManager;
    private final FieldAccess fields;
    private final CircuitAccess circuits;
    private final SignalAccess signals;

    public DefaultElectromagneticsApi(EmWorldManager worldManager) {
        this.worldManager = Objects.requireNonNull(worldManager, "worldManager");
        this.fields = new WorldFieldAccess(worldManager);
        this.circuits = new WorldCircuitAccess(worldManager);
        this.signals = new WorldSignalAccess(worldManager);
    }

    @Override
    public FieldAccess fields() {
        return fields;
    }

    @Override
    public CircuitAccess circuits() {
        return circuits;
    }

    @Override
    public SignalAccess signals() {
        return signals;
    }

    @Override
    public void registerListener(ResourceLocation ownerId, ElectromagneticEventListener listener) {
        worldManager.registerListener(ownerId, listener);
    }

    @Override
    public void unregisterListener(ResourceLocation ownerId) {
        worldManager.unregisterListener(ownerId);
    }
}
