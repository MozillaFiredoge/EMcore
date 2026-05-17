package com.firedoge.emcore.internal.field;

import java.util.Optional;

import com.firedoge.emcore.api.field.ChargedFieldProbe;
import com.firedoge.emcore.api.circuit.CircuitPort;
import com.firedoge.emcore.api.circuit.ResistorElement;
import com.firedoge.emcore.api.circuit.VoltageSourceElement;
import com.firedoge.emcore.api.circuit.WireElement;
import com.firedoge.emcore.api.field.CircuitDrivenFieldSource;
import com.firedoge.emcore.api.field.CoilRegion;
import com.firedoge.emcore.api.field.CoilTorqueProbe;
import com.firedoge.emcore.api.field.CurrentSegmentProbe;
import com.firedoge.emcore.api.field.FieldDiagnosticSeverity;
import com.firedoge.emcore.api.field.FieldDiagnosticType;
import com.firedoge.emcore.api.field.FieldEnergySample;
import com.firedoge.emcore.api.field.FieldForceSample;
import com.firedoge.emcore.api.field.FieldRegion;
import com.firedoge.emcore.api.field.FieldSample;
import com.firedoge.emcore.api.field.FieldSolveResult;
import com.firedoge.emcore.api.field.FieldSnapshot;
import com.firedoge.emcore.api.field.FieldSource;
import com.firedoge.emcore.api.field.FieldTorqueSample;
import com.firedoge.emcore.api.field.FluxSample;
import com.firedoge.emcore.api.field.MagneticFieldSample;
import com.firedoge.emcore.internal.world.EmWorldState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class FieldNetworkSmokeTest {
    private static final String TEST_NAMESPACE = "emcore_test";

    private FieldNetworkSmokeTest() {
    }

    public static void main(String[] args) {
        PreparedPoissonRegionSmokeTest.runAll();
        registersRegionAndReportsDirtySnapshot();
        samplesInsideRegionFromCommittedShape();
        solveClearsDirtyState();
        solveProducesFieldSample();
        solveProducesMagneticFieldSample();
        registersCoilAndSamplesFlux();
        samplesEnergyForceAndTorque();
        syncsCircuitCurrentIntoFieldSource();
        requestSolveCommitsOnTick();
        tickAutoSchedulesDirtyRegion();
        largeRegionAutoSolveIsDeferred();
        sourceMarksRegionDirty();
        reportsSourceWithMissingRegion();
    }

    private static void registersRegionAndReportsDirtySnapshot() {
        FieldNetwork network = new FieldNetwork();
        FieldRegion region = region("region/a");

        network.registerRegion(region);
        FieldSnapshot snapshot = network.snapshot();

        assertEquals(1, snapshot.regions().size(), "region count");
        assertEquals(region.id(), snapshot.regions().getFirst().id(), "region id");
        assertEquals(1, snapshot.dirtyRegionIds().size(), "dirty region count");
        assertTrue(snapshot.stale(), "stale snapshot");
        assertTrue(snapshot.diagnostics().stream()
                .anyMatch(diagnostic -> diagnostic.type() == FieldDiagnosticType.STALE_REGION),
                "stale region diagnostic");
    }

    private static void samplesInsideRegionFromCommittedShape() {
        FieldNetwork network = new FieldNetwork();
        FieldRegion region = region("region/sample");

        network.registerRegion(region);
        Optional<FieldSample> sample = network.sample(new Vec3(1.0, 1.0, 1.0));

        assertTrue(sample.isPresent(), "sample inside region");
        assertEquals(region.id(), sample.orElseThrow().regionIds().getFirst(), "sample region id");
        assertTrue(sample.orElseThrow().stale(), "sample stale before solve");
        assertFalse(network.sample(new Vec3(9.0, 9.0, 9.0)).isPresent(), "sample outside region");
    }

    private static void solveClearsDirtyState() {
        FieldNetwork network = new FieldNetwork();
        FieldRegion region = region("region/solve");

        network.registerRegion(region);
        assertTrue(network.solve(region.id()).isPresent(), "solve accepted");

        FieldSnapshot snapshot = network.snapshot();
        assertFalse(snapshot.stale(), "snapshot no longer stale after solve");
        assertEquals(0, snapshot.dirtyRegionIds().size(), "dirty regions cleared");
        assertFalse(network.sample(new Vec3(1.0, 1.0, 1.0)).orElseThrow().stale(), "sample no longer stale");
        assertFalse(network.requestSolve(id("region/missing")), "missing region solve rejected");
    }

    private static void solveProducesFieldSample() {
        FieldNetwork network = new FieldNetwork();
        FieldRegion region = region("region/poisson");

        network.registerRegion(region);
        network.registerSource(FieldSource.pointCharge(
                id("source/poisson_charge"),
                region.id(),
                new Vec3(2.0, 2.0, 2.0),
                1.0e-12
        ));

        FieldSolveResult result = network.solve(region.id()).orElseThrow(() -> new AssertionError("expected solve"));
        FieldSample sample = network.sample(new Vec3(2.0, 2.0, 2.0))
                .orElseThrow(() -> new AssertionError("expected solved field sample"));

        assertEquals(region.id(), result.regionId(), "solved region id");
        assertEquals(1, result.sourceCount(), "solved source count");
        assertTrue(sample.potentialVolts() > 0.0, "positive charge potential");
        assertFalse(sample.stale(), "sample is fresh after solve");
    }

    private static void solveProducesMagneticFieldSample() {
        FieldNetwork network = new FieldNetwork();
        FieldRegion region = new FieldRegion(
                id("region/magnetic"),
                new AABB(0.0, 0.0, 0.0, 8.0, 8.0, 8.0),
                1.0
        );

        network.registerRegion(region);
        network.registerSource(FieldSource.currentDensity(
                id("source/current_density"),
                region.id(),
                new Vec3(4.0, 4.0, 4.0),
                1.0,
                new Vec3(0.0, 10_000.0, 0.0)
        ));

        FieldSolveResult result = network.solve(region.id()).orElseThrow(() -> new AssertionError("expected solve"));
        MagneticFieldSample magneticSample = network.sampleMagneticField(new Vec3(5.0, 4.0, 4.0));
        double magnitude = magneticSample.fluxDensityTesla().length();

        assertEquals(region.id(), result.regionId(), "magnetic solved region id");
        assertEquals(1, result.sourceCount(), "magnetic source count");
        assertTrue(magnitude > 0.0, "current density creates magnetic flux density");
    }

    private static void registersCoilAndSamplesFlux() {
        FieldNetwork network = new FieldNetwork();
        FieldRegion region = new FieldRegion(
                id("region/coil"),
                new AABB(0.0, 0.0, 0.0, 8.0, 8.0, 8.0),
                1.0
        );
        ResourceLocation currentId = id("source/coil_current");
        CoilRegion coil = new CoilRegion(
                id("coil/test"),
                region.id(),
                new Vec3(5.0, 4.0, 4.0),
                Direction.NORTH,
                1.0,
                10
        );

        network.registerRegion(region);
        network.registerSource(FieldSource.currentDensity(
                currentId,
                region.id(),
                new Vec3(4.0, 4.0, 4.0),
                1.0,
                new Vec3(0.0, 10_000.0, 0.0)
        ));
        network.registerCoil(coil);
        network.solve(region.id()).orElseThrow(() -> new AssertionError("expected solve"));

        FluxSample firstSample = network.sampleCoil(coil.id(), 1.0)
                .orElseThrow(() -> new AssertionError("expected coil flux sample"));
        assertTrue(Math.abs(firstSample.fluxWebers()) > 0.0, "coil samples nonzero flux");
        assertEquals(10, firstSample.turns(), "coil turns");
        assertClose(firstSample.fluxWebers() * firstSample.turns(), firstSample.fluxLinkageWebers(),
                "flux linkage");
        assertFalse(firstSample.inducedVoltageVolts().isPresent(), "first coil sample has no previous history");
        assertFalse(firstSample.stale(), "coil sample fresh after solve");

        network.registerSource(FieldSource.currentDensity(
                currentId,
                region.id(),
                new Vec3(4.0, 4.0, 4.0),
                1.0,
                new Vec3(0.0, 20_000.0, 0.0)
        ));
        network.solve(region.id()).orElseThrow(() -> new AssertionError("expected second solve"));
        FluxSample secondSample = network.sampleCoil(coil.id(), 2.0)
                .orElseThrow(() -> new AssertionError("expected second coil flux sample"));

        assertTrue(secondSample.inducedVoltageVolts().isPresent(), "second coil sample has induced voltage");
        assertTrue(Math.abs(secondSample.inducedVoltageVolts().getAsDouble()) > 0.0, "induced voltage is nonzero");

        FluxSample repeatedSample = network.sampleCoil(coil.id(), 2.0)
                .orElseThrow(() -> new AssertionError("expected repeated coil flux sample"));
        assertTrue(repeatedSample.inducedVoltageVolts().isPresent(), "same-time coil sample keeps induced voltage");
        assertClose(
                secondSample.inducedVoltageVolts().getAsDouble(),
                repeatedSample.inducedVoltageVolts().getAsDouble(),
                "same-time induced voltage"
        );
    }

    private static void samplesEnergyForceAndTorque() {
        FieldNetwork electricNetwork = new FieldNetwork();
        FieldRegion electricRegion = region("region/interaction_electric");
        electricNetwork.registerRegion(electricRegion);
        electricNetwork.registerSource(FieldSource.pointCharge(
                id("source/interaction_charge"),
                electricRegion.id(),
                new Vec3(2.0, 2.0, 2.0),
                1.0e-12
        ));
        electricNetwork.solve(electricRegion.id()).orElseThrow(() -> new AssertionError("expected electric solve"));

        FieldForceSample chargedForce = electricNetwork.sampleForce(ChargedFieldProbe.stationary(
                id("probe/charge"),
                new Vec3(3.0, 2.0, 2.0),
                1.0
        )).orElseThrow(() -> new AssertionError("expected charged force sample"));
        assertTrue(chargedForce.totalForceNewtons().length() > 0.0, "charged probe receives electric force");
        assertFalse(chargedForce.stale(), "charged force sample fresh");

        FieldNetwork magneticNetwork = new FieldNetwork();
        FieldRegion magneticRegion = new FieldRegion(
                id("region/interaction_magnetic"),
                new AABB(0.0, 0.0, 0.0, 8.0, 8.0, 8.0),
                1.0
        );
        magneticNetwork.registerRegion(magneticRegion);
        magneticNetwork.registerSource(FieldSource.currentDensity(
                id("source/interaction_current"),
                magneticRegion.id(),
                new Vec3(4.0, 4.0, 4.0),
                1.0,
                new Vec3(0.0, 10_000.0, 0.0)
        ));
        magneticNetwork.solve(magneticRegion.id()).orElseThrow(() -> new AssertionError("expected magnetic solve"));

        Vec3 probePosition = new Vec3(5.0, 4.0, 4.0);
        FieldEnergySample energy = magneticNetwork.sampleEnergy(probePosition)
                .orElseThrow(() -> new AssertionError("expected energy sample"));
        assertTrue(energy.magneticEnergyDensityJoulesPerCubicMeter() > 0.0, "magnetic energy density");
        assertClose(
                energy.electricEnergyDensityJoulesPerCubicMeter()
                        + energy.magneticEnergyDensityJoulesPerCubicMeter(),
                energy.totalEnergyDensityJoulesPerCubicMeter(),
                "total energy density"
        );

        FieldForceSample segmentForce = magneticNetwork.sampleForce(new CurrentSegmentProbe(
                id("probe/current_segment"),
                probePosition,
                new Vec3(0.0, 1.0, 0.0),
                1.0,
                2.0
        )).orElseThrow(() -> new AssertionError("expected current segment force"));
        assertTrue(segmentForce.magneticForceNewtons().length() > 0.0, "current segment receives magnetic force");

        FieldTorqueSample torque = magneticNetwork.sampleTorque(new CoilTorqueProbe(
                id("probe/torque"),
                probePosition,
                new Vec3(1.0, 0.0, 0.0),
                1.0,
                10,
                2.0
        )).orElseThrow(() -> new AssertionError("expected coil torque sample"));
        assertTrue(torque.torqueNewtonMeters().length() > 0.0, "coil receives magnetic torque");
        assertFalse(torque.stale(), "torque sample fresh");
    }

    private static void syncsCircuitCurrentIntoFieldSource() {
        EmWorldState state = new EmWorldState(ResourceKey.create(Registries.DIMENSION, id("dimension/coupled")));
        FieldRegion region = region("region/circuit_driven");
        CircuitPort sourcePositive = circuitPort("coupled/source_positive", new BlockPos(0, 0, 0));
        CircuitPort sourceNegative = circuitPort("coupled/source_negative", new BlockPos(1, 0, 0));
        CircuitPort resistorPositive = circuitPort("coupled/resistor_positive", new BlockPos(0, 0, 1));
        CircuitPort resistorNegative = circuitPort("coupled/resistor_negative", new BlockPos(1, 0, 1));
        CircuitDrivenFieldSource drivenSource = new CircuitDrivenFieldSource(
                id("source/circuit_driven"),
                region.id(),
                resistorPositive,
                new Vec3(2.0, 2.0, 2.0),
                1.0,
                new Vec3(0.0, 100.0, 0.0)
        );

        state.registerFieldRegion(region);
        state.registerCircuitElement(new VoltageSourceElement(
                id("circuit_driven/source"),
                sourcePositive,
                sourceNegative,
                12.0
        ));
        state.registerCircuitElement(new ResistorElement(
                id("circuit_driven/resistor"),
                resistorPositive,
                resistorNegative,
                6.0
        ));
        state.registerCircuitElement(new WireElement(
                id("circuit_driven/positive_wire"),
                sourcePositive,
                resistorPositive
        ));
        state.registerCircuitElement(new WireElement(
                id("circuit_driven/negative_wire"),
                sourceNegative,
                resistorNegative
        ));
        state.registerCircuitDrivenFieldSource(drivenSource);
        state.tick();

        FieldSource generatedSource = state.fieldSources().stream()
                .filter(source -> source.id().equals(drivenSource.id()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("expected circuit-driven field source"));

        assertEquals(region.id(), generatedSource.regionId(), "circuit-driven source region");
        assertClose(0.0, generatedSource.vectorValue().x, "circuit-driven source x current density");
        assertClose(200.0, generatedSource.vectorValue().y, "circuit-driven source y current density");
        assertClose(0.0, generatedSource.vectorValue().z, "circuit-driven source z current density");
        assertTrue(state.fieldSnapshot().dirtyRegionIds().contains(region.id()), "circuit-driven source dirties region");

        state.unregisterCircuitDrivenFieldSource(drivenSource.id());
        assertFalse(state.fieldSources().stream().anyMatch(source -> source.id().equals(drivenSource.id())),
                "unregister circuit-driven source removes generated source");
    }

    private static void requestSolveCommitsOnTick() {
        FieldNetwork network = new FieldNetwork();
        FieldRegion region = region("region/async");

        network.registerRegion(region);
        network.registerSource(FieldSource.pointCharge(
                id("source/async_charge"),
                region.id(),
                new Vec3(2.0, 2.0, 2.0),
                1.0e-12
        ));

        assertTrue(network.requestSolve(region.id()), "async solve accepted");
        assertTrue(network.sample(new Vec3(2.0, 2.0, 2.0)).orElseThrow().stale(), "sample stale while queued");
        waitForAsyncSolve(network);

        FieldSample sample = network.sample(new Vec3(2.0, 2.0, 2.0))
                .orElseThrow(() -> new AssertionError("expected async solved field sample"));
        assertFalse(sample.stale(), "sample fresh after async commit");
        assertTrue(sample.potentialVolts() > 0.0, "async positive charge potential");
    }

    private static void tickAutoSchedulesDirtyRegion() {
        FieldNetwork network = new FieldNetwork();
        FieldRegion region = region("region/auto");

        network.registerRegion(region);
        network.registerSource(FieldSource.pointCharge(
                id("source/auto_charge"),
                region.id(),
                new Vec3(2.0, 2.0, 2.0),
                1.0e-12
        ));

        waitForAsyncSolve(network);

        FieldSample sample = network.sample(new Vec3(2.0, 2.0, 2.0))
                .orElseThrow(() -> new AssertionError("expected auto-solved field sample"));
        assertFalse(sample.stale(), "auto-solved sample fresh");
        assertTrue(sample.potentialVolts() > 0.0, "auto-solved positive charge potential");
    }

    private static void largeRegionAutoSolveIsDeferred() {
        FieldNetwork network = new FieldNetwork();
        FieldRegion region = new FieldRegion(
                id("region/large"),
                new AABB(0.0, 0.0, 0.0, 80.0, 80.0, 80.0),
                1.0
        );

        network.registerRegion(region);
        network.tick();

        FieldSnapshot snapshot = network.snapshot();
        assertTrue(snapshot.stale(), "large region remains stale");
        assertTrue(snapshot.diagnostics().stream()
                        .anyMatch(diagnostic -> diagnostic.type() == FieldDiagnosticType.POISSON_SOLVE_DEFERRED),
                "large region deferred diagnostic");
    }

    private static void sourceMarksRegionDirty() {
        FieldNetwork network = new FieldNetwork();
        FieldRegion region = region("region/source");

        network.registerRegion(region);
        network.requestSolve(region.id());
        network.registerSource(FieldSource.pointCharge(
                id("source/charge"),
                region.id(),
                new Vec3(1.0, 1.0, 1.0),
                1.0
        ));

        assertTrue(network.snapshot().dirtyRegionIds().contains(region.id()), "source dirtied region");
    }

    private static void reportsSourceWithMissingRegion() {
        FieldNetwork network = new FieldNetwork();
        ResourceLocation missingRegionId = id("region/missing");

        network.registerSource(FieldSource.pointCharge(
                id("source/missing"),
                missingRegionId,
                Vec3.ZERO,
                1.0
        ));

        assertTrue(network.snapshot().diagnostics().stream().anyMatch(diagnostic ->
                        diagnostic.type() == FieldDiagnosticType.SOURCE_REGION_NOT_FOUND
                                && diagnostic.severity() == FieldDiagnosticSeverity.WARNING),
                "missing region diagnostic");
    }

    private static FieldRegion region(String path) {
        return new FieldRegion(id(path), new AABB(0.0, 0.0, 0.0, 4.0, 4.0, 4.0), 1.0);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(TEST_NAMESPACE, path);
    }

    private static CircuitPort circuitPort(String path, BlockPos position) {
        return new CircuitPort(id("owner/" + path), id("port/" + path), position, Direction.UP);
    }

    private static void assertEquals(Object expected, Object actual, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + " but got " + actual);
        }
    }

    private static void assertClose(double expected, double actual, String label) {
        double tolerance = Math.max(1.0e-12, Math.abs(expected) * 1.0e-9);
        if (Math.abs(expected - actual) > tolerance) {
            throw new AssertionError(label + ": expected " + expected + " but got " + actual);
        }
    }

    private static void assertTrue(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
    }

    private static void assertFalse(boolean condition, String label) {
        assertTrue(!condition, label);
    }

    private static void waitForAsyncSolve(FieldNetwork network) {
        for (int attempt = 0; attempt < 200; attempt++) {
            network.tick();
            if (!network.snapshot().stale()) {
                return;
            }
            try {
                Thread.sleep(5L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while waiting for async field solve", exception);
            }
        }
        throw new AssertionError("async field solve did not finish");
    }
}
