package com.firedoge.emcore.internal.world;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.firedoge.emcore.api.event.ElectromagneticEventListener;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

public final class EmWorldManager {
    private final Map<ResourceKey<Level>, EmWorldState> states = new HashMap<>();
    private final Map<ResourceLocation, ElectromagneticEventListener> listeners = new HashMap<>();

    public EmWorldState getOrCreate(ServerLevel level) {
        Objects.requireNonNull(level, "level");
        return states.computeIfAbsent(level.dimension(), EmWorldState::new);
    }

    public void tick(ServerLevel level) {
        getOrCreate(level).tick();
    }

    public void remove(ServerLevel level) {
        Objects.requireNonNull(level, "level");
        states.remove(level.dimension());
    }

    public void clear() {
        states.clear();
        listeners.clear();
    }

    public void registerListener(ResourceLocation ownerId, ElectromagneticEventListener listener) {
        listeners.put(Objects.requireNonNull(ownerId, "ownerId"), Objects.requireNonNull(listener, "listener"));
    }

    public void unregisterListener(ResourceLocation ownerId) {
        listeners.remove(Objects.requireNonNull(ownerId, "ownerId"));
    }

    public Collection<ElectromagneticEventListener> listeners() {
        return List.copyOf(listeners.values());
    }
}
