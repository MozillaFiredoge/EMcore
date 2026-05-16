package com.firedoge.emcore.api.signal;

import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

public interface SignalAccess {
    void registerSource(ServerLevel level, SignalSource source);

    void unregisterSource(ServerLevel level, ResourceLocation sourceId);

    List<SignalSource> sources(ServerLevel level);

    Optional<SignalSample> sample(ServerLevel level, ResourceLocation channelId, Vec3 receiverPosition);
}
