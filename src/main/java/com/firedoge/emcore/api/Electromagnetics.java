package com.firedoge.emcore.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.concurrent.atomic.AtomicReference;

import com.firedoge.emcore.api.circuit.CircuitAccess;
import com.firedoge.emcore.api.circuit.AcCircuitSample;
import com.firedoge.emcore.api.circuit.AcCircuitSnapshot;
import com.firedoge.emcore.api.circuit.BatchTransientRequest;
import com.firedoge.emcore.api.circuit.BatchTransientResult;
import com.firedoge.emcore.api.circuit.CircuitElement;
import com.firedoge.emcore.api.circuit.CircuitPort;
import com.firedoge.emcore.api.circuit.CircuitSample;
import com.firedoge.emcore.api.circuit.CircuitSnapshot;
import com.firedoge.emcore.api.circuit.CircuitTerminal;
import com.firedoge.emcore.api.event.ElectromagneticEventListener;
import com.firedoge.emcore.api.field.ElectricFieldSample;
import com.firedoge.emcore.api.field.FieldSampler;
import com.firedoge.emcore.api.field.MagneticFieldSample;
import com.firedoge.emcore.api.signal.SignalAccess;
import com.firedoge.emcore.api.signal.SignalSample;
import com.firedoge.emcore.api.signal.SignalSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

public final class Electromagnetics {
    private static final ElectromagneticsApi EMPTY_API = new EmptyElectromagneticsApi();
    private static final AtomicReference<ElectromagneticsApi> API = new AtomicReference<>(EMPTY_API);

    private Electromagnetics() {
    }

    public static ElectromagneticsApi api() {
        return API.get();
    }

    public static boolean isInstalled() {
        return API.get() != EMPTY_API;
    }

    public static void install(ElectromagneticsApi api) {
        Objects.requireNonNull(api, "api");

        if (!API.compareAndSet(EMPTY_API, api)) {
            throw new IllegalStateException("Electromagnetics API has already been installed");
        }
    }

    private static final class EmptyElectromagneticsApi implements ElectromagneticsApi {
        private final FieldSampler fields = new EmptyFieldSampler();
        private final CircuitAccess circuits = new EmptyCircuitAccess();
        private final SignalAccess signals = new EmptySignalAccess();

        @Override
        public FieldSampler fields() {
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
        }

        @Override
        public void unregisterListener(ResourceLocation ownerId) {
        }
    }

    private static final class EmptyFieldSampler implements FieldSampler {
        @Override
        public ElectricFieldSample sampleElectricField(ServerLevel level, Vec3 position) {
            return new ElectricFieldSample(Vec3.ZERO, 0.0, 0.0, 0.0);
        }

        @Override
        public MagneticFieldSample sampleMagneticField(ServerLevel level, Vec3 position) {
            return new MagneticFieldSample(Vec3.ZERO, 0.0, Vec3.ZERO, 0.0);
        }

        @Override
        public double samplePotential(ServerLevel level, Vec3 position) {
            return 0.0;
        }

        @Override
        public double sampleMagneticFlux(ServerLevel level, BlockPos position, Direction normal) {
            return 0.0;
        }
    }

    private static final class EmptyCircuitAccess implements CircuitAccess {
        @Override
        public CircuitSnapshot snapshot(ServerLevel level) {
            return new CircuitSnapshot(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), 0.0);
        }

        @Override
        public AcCircuitSnapshot acSnapshot(ServerLevel level, double frequencyHertz) {
            return new AcCircuitSnapshot(
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    frequencyHertz,
                    0.0
            );
        }

        @Override
        public CircuitSnapshot stepTransient(ServerLevel level, double timeStepSeconds) {
            return new CircuitSnapshot(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), 0.0);
        }

        @Override
        public BatchTransientResult solveTransient(BatchTransientRequest request) {
            return new BatchTransientResult(
                    request.timeStepSeconds(),
                    request.startTimeSeconds(),
                    request.probes(),
                    List.of(),
                    List.of()
            );
        }

        @Override
        public void registerPort(ServerLevel level, CircuitPort port) {
        }

        @Override
        public void unregisterPort(ServerLevel level, CircuitPort port) {
        }

        @Override
        public void registerTerminal(ServerLevel level, CircuitTerminal terminal) {
        }

        @Override
        public void unregisterTerminal(ServerLevel level, CircuitTerminal terminal) {
        }

        @Override
        public void registerElement(ServerLevel level, CircuitElement element) {
        }

        @Override
        public void unregisterElement(ServerLevel level, ResourceLocation elementId) {
        }

        @Override
        public Optional<CircuitSample> samplePort(ServerLevel level, CircuitPort port) {
            return Optional.empty();
        }

        @Override
        public Optional<AcCircuitSample> sampleAcPort(ServerLevel level, CircuitPort port, double frequencyHertz) {
            return Optional.empty();
        }

        @Override
        public OptionalDouble getVoltage(ServerLevel level, CircuitPort port) {
            return OptionalDouble.empty();
        }

        @Override
        public OptionalDouble getCurrent(ServerLevel level, CircuitPort port) {
            return OptionalDouble.empty();
        }

        @Override
        public OptionalDouble getPower(ServerLevel level, CircuitPort port) {
            return OptionalDouble.empty();
        }

        @Override
        public OptionalDouble getStoredEnergy(ServerLevel level, CircuitPort port) {
            return OptionalDouble.empty();
        }
    }

    private static final class EmptySignalAccess implements SignalAccess {
        @Override
        public void registerSource(ServerLevel level, SignalSource source) {
        }

        @Override
        public void unregisterSource(ServerLevel level, ResourceLocation sourceId) {
        }

        @Override
        public List<SignalSource> sources(ServerLevel level) {
            return List.of();
        }

        @Override
        public Optional<SignalSample> sample(ServerLevel level, ResourceLocation channelId, Vec3 receiverPosition) {
            return Optional.empty();
        }
    }
}
