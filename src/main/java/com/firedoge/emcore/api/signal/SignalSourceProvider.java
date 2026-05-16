package com.firedoge.emcore.api.signal;

import java.util.Collection;

import com.firedoge.emcore.api.Electromagnetics;
import net.minecraft.server.level.ServerLevel;

public interface SignalSourceProvider {
    Collection<SignalSource> signalSources();

    default void registerSignalSources(ServerLevel level) {
        SignalAccess signals = Electromagnetics.api().signals();

        signalSources().forEach(source -> signals.registerSource(level, source));
    }

    default void unregisterSignalSources(ServerLevel level) {
        SignalAccess signals = Electromagnetics.api().signals();

        signalSources().forEach(source -> signals.unregisterSource(level, source.id()));
    }
}
