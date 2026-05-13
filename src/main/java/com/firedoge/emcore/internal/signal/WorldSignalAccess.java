package com.firedoge.emcore.internal.signal;

import java.util.Objects;
import java.util.Optional;

import com.firedoge.emcore.api.signal.SignalAccess;
import com.firedoge.emcore.api.signal.SignalSample;
import com.firedoge.emcore.internal.world.EmWorldManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

public final class WorldSignalAccess implements SignalAccess {
    private final EmWorldManager worldManager;

    public WorldSignalAccess(EmWorldManager worldManager) {
        this.worldManager = Objects.requireNonNull(worldManager, "worldManager");
    }

    @Override
    public Optional<SignalSample> sample(ServerLevel level, ResourceLocation channelId, Vec3 receiverPosition) {
        return worldManager.getOrCreate(level).sampleSignal(channelId, receiverPosition);
    }
}
