package com.firedoge.emcore.api.signal;

import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

public interface SignalAccess {
    Optional<SignalSample> sample(ServerLevel level, ResourceLocation channelId, Vec3 receiverPosition);
}
