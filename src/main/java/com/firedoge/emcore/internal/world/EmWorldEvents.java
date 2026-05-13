package com.firedoge.emcore.internal.world;

import java.util.Objects;

import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

public final class EmWorldEvents {
    private final EmWorldManager worldManager;

    public EmWorldEvents(EmWorldManager worldManager) {
        this.worldManager = Objects.requireNonNull(worldManager, "worldManager");
    }

    @SubscribeEvent
    public void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level) {
            worldManager.tick(level);
        }
    }

    @SubscribeEvent
    public void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            worldManager.remove(level);
        }
    }

    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        worldManager.clear();
    }
}
