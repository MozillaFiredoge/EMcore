package com.firedoge.emcore.internal.signal;

import java.util.List;
import java.util.Objects;

import com.firedoge.emcore.api.circuit.AcCircuitEquationBuilder;
import com.firedoge.emcore.api.circuit.AcCircuitSample;
import com.firedoge.emcore.api.circuit.AcCircuitSnapshot;
import com.firedoge.emcore.api.circuit.AcLinearCircuitElement;
import com.firedoge.emcore.api.circuit.CircuitPhasor;
import com.firedoge.emcore.api.circuit.CircuitPort;
import com.firedoge.emcore.api.signal.SignalPayload;
import com.firedoge.emcore.api.signal.SignalSample;
import com.firedoge.emcore.api.signal.SignalSource;
import com.firedoge.emcore.api.signal.SignalVoltageSourceElement;
import com.firedoge.emcore.internal.circuit.CircuitNetwork;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

public final class SignalNetworkSmokeTest {
    private static final String TEST_NAMESPACE = "emcore_test";
    private static final double EPSILON = 1.0e-9;

    private SignalNetworkSmokeTest() {
    }

    public static void main(String[] args) {
        samplesRegisteredSignalSource();
        selectsStrongestSourceAndReportsInterference();
        unregistersSignalSource();
        isolatesChannels();
        ignoresZeroPowerSources();
        convertsReceivedPowerToPhasors();
        stampsSignalSampleAsAcVoltageSource();
        ignoresVoltageSourceAtMismatchedFrequency();
    }

    private static void samplesRegisteredSignalSource() {
        SignalNetwork network = new SignalNetwork();
        SignalSource source = source("source/a", "am", new Vec3(0.0, 0.0, 0.0), 1_000_000.0, 100.0, "hello");

        network.registerSource(source);
        SignalSample sample = network.sample(id("channel/am"), new Vec3(10.0, 0.0, 0.0), 0.0)
                .orElseThrow(() -> new AssertionError("expected signal sample"));

        assertEquals(source.id(), sample.sourceId(), "source id");
        assertEquals(source.channelId(), sample.channelId(), "channel id");
        assertEquals(SignalPayload.text("hello"), sample.payload(), "payload");
        assertClose(1.0, sample.receivedPowerWatts(), "received power");
        assertClose(10.0 / 299_792_458.0, sample.delaySeconds(), "propagation delay");
        assertClose(0.0, sample.interferenceWatts(), "interference");
    }

    private static void selectsStrongestSourceAndReportsInterference() {
        SignalNetwork network = new SignalNetwork();
        SignalSource far = source("source/far", "music", new Vec3(0.0, 0.0, 0.0), 440.0, 100.0, "far");
        SignalSource close = source("source/close", "music", new Vec3(9.0, 0.0, 0.0), 440.0, 4.0, "close");

        network.registerSource(far);
        network.registerSource(close);
        SignalSample sample = network.sample(id("channel/music"), new Vec3(10.0, 0.0, 0.0), 1.0)
                .orElseThrow(() -> new AssertionError("expected strongest signal sample"));

        assertEquals(close.id(), sample.sourceId(), "strongest source id");
        assertClose(4.0, sample.receivedPowerWatts(), "strongest received power");
        assertClose(1.0, sample.interferenceWatts(), "same-channel interference");
    }

    private static void unregistersSignalSource() {
        SignalNetwork network = new SignalNetwork();
        SignalSource source = source("source/a", "am", Vec3.ZERO, 1000.0, 1.0, "hello");

        network.registerSource(source);
        network.unregisterSource(source.id());

        assertFalse(network.sample(id("channel/am"), Vec3.ZERO, 0.0).isPresent(), "unregistered source sample");
    }

    private static void isolatesChannels() {
        SignalNetwork network = new SignalNetwork();

        network.registerSource(source("source/a", "am", Vec3.ZERO, 1000.0, 1.0, "hello"));

        assertFalse(network.sample(id("channel/fm"), Vec3.ZERO, 0.0).isPresent(), "wrong channel sample");
    }

    private static void ignoresZeroPowerSources() {
        SignalNetwork network = new SignalNetwork();

        network.registerSource(source("source/a", "am", Vec3.ZERO, 1000.0, 0.0, "hello"));

        assertFalse(network.sample(id("channel/am"), Vec3.ZERO, 0.0).isPresent(), "zero-power source sample");
    }

    private static void convertsReceivedPowerToPhasors() {
        SignalSample sample = toneSample();

        assertPhasorClose(CircuitPhasor.of(0.0, 10.0), sample.voltagePhasor(25.0), "signal voltage phasor");
        assertPhasorClose(CircuitPhasor.of(0.0, 0.4), sample.currentPhasor(25.0), "signal current phasor");
    }

    private static void stampsSignalSampleAsAcVoltageSource() {
        CircuitNetwork circuitNetwork = new CircuitNetwork();
        SignalSample sample = toneSample();
        CircuitPort positive = port("radio/positive", new BlockPos(0, 0, 0));
        CircuitPort negative = port("radio/negative", new BlockPos(1, 0, 0));

        circuitNetwork.registerElement(new SignalVoltageSourceElement(
                id("radio/antenna_source"),
                positive,
                negative,
                sample,
                25.0,
                1.0,
                0.0
        ));
        circuitNetwork.registerElement(new TestAcAdmittanceElement(
                id("radio/load"),
                positive,
                negative,
                CircuitPhasor.real(0.1)
        ));

        AcCircuitSnapshot snapshot = circuitNetwork.acSnapshot(0.25, 1.0);
        AcCircuitSample positiveSample = sampleByPort(snapshot, positive);

        assertEquals(0, snapshot.diagnostics().size(), "signal voltage source diagnostics");
        assertPhasorClose(CircuitPhasor.of(0.0, 10.0), positiveSample.voltageVolts(), "signal-driven voltage");
    }

    private static void ignoresVoltageSourceAtMismatchedFrequency() {
        CircuitNetwork circuitNetwork = new CircuitNetwork();
        SignalSample sample = toneSample();
        CircuitPort positive = port("radio/off_frequency_positive", new BlockPos(0, 0, 0));
        CircuitPort negative = port("radio/off_frequency_negative", new BlockPos(1, 0, 0));
        SignalVoltageSourceElement element = new SignalVoltageSourceElement(
                id("radio/off_frequency_antenna_source"),
                positive,
                negative,
                sample,
                25.0,
                1.0,
                0.0
        );

        assertFalse(element.matchesFrequency(0.5), "frequency match");

        circuitNetwork.registerElement(element);
        circuitNetwork.registerElement(new TestAcAdmittanceElement(
                id("radio/off_frequency_load"),
                positive,
                negative,
                CircuitPhasor.real(0.1)
        ));

        AcCircuitSnapshot snapshot = circuitNetwork.acSnapshot(0.5, 1.0);
        AcCircuitSample positiveSample = sampleByPort(snapshot, positive);

        assertEquals(0, snapshot.diagnostics().size(), "off-frequency diagnostics");
        assertPhasorClose(CircuitPhasor.ZERO, positiveSample.voltageVolts(), "off-frequency voltage");
    }

    private static SignalSample toneSample() {
        SignalNetwork network = new SignalNetwork();

        network.registerSource(source("source/tone", "tone", Vec3.ZERO, 0.25, 4.0, "tone"));

        return network.sample(id("channel/tone"), Vec3.ZERO, 1.0)
                .orElseThrow(() -> new AssertionError("expected tone signal sample"));
    }

    private static AcCircuitSample sampleByPort(AcCircuitSnapshot snapshot, CircuitPort port) {
        return snapshot.samples().stream()
                .filter(sample -> sample.port().equals(port))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing AC sample for " + port));
    }

    private static SignalSource source(
            String path,
            String channel,
            Vec3 position,
            double frequencyHz,
            double transmitPowerWatts,
            String payload
    ) {
        return new SignalSource(
                id(path),
                id("channel/" + channel),
                position,
                frequencyHz,
                transmitPowerWatts,
                SignalPayload.text(payload)
        );
    }

    private static CircuitPort port(String path, BlockPos position) {
        return new CircuitPort(id("owner/" + path), id("terminal/" + path), position, Direction.UP);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(TEST_NAMESPACE, path);
    }

    private static void assertEquals(Object expected, Object actual, String label) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertClose(double expected, double actual, String label) {
        if (Math.abs(expected - actual) > EPSILON) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertPhasorClose(CircuitPhasor expected, CircuitPhasor actual, String label) {
        assertClose(expected.real(), actual.real(), label + " real");
        assertClose(expected.imaginary(), actual.imaginary(), label + " imaginary");
    }

    private static void assertFalse(boolean value, String label) {
        if (value) {
            throw new AssertionError(label + ": expected false");
        }
    }

    private record TestAcAdmittanceElement(
            ResourceLocation id,
            CircuitPort positivePort,
            CircuitPort negativePort,
            CircuitPhasor admittanceSiemens
    ) implements AcLinearCircuitElement {
        @Override
        public List<CircuitPort> ports() {
            return List.of(positivePort, negativePort);
        }

        @Override
        public void stamp(AcCircuitEquationBuilder builder) {
            builder.addAdmittance(positivePort, negativePort, admittanceSiemens);
        }
    }
}
