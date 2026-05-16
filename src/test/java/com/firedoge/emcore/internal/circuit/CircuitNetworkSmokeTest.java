package com.firedoge.emcore.internal.circuit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.firedoge.emcore.api.circuit.AcCircuitEquationBuilder;
import com.firedoge.emcore.api.circuit.AcCircuitSample;
import com.firedoge.emcore.api.circuit.AcCircuitSnapshot;
import com.firedoge.emcore.api.circuit.AcLinearCircuitElement;
import com.firedoge.emcore.api.circuit.BatchTransientRequest;
import com.firedoge.emcore.api.circuit.BatchTransientResult;
import com.firedoge.emcore.api.circuit.CapacitorElement;
import com.firedoge.emcore.api.circuit.CircuitBranchCurrent;
import com.firedoge.emcore.api.circuit.CircuitEquationBuilder;
import com.firedoge.emcore.api.circuit.CircuitElement;
import com.firedoge.emcore.api.circuit.CircuitDiagnosticType;
import com.firedoge.emcore.api.circuit.CircuitNetlist;
import com.firedoge.emcore.api.circuit.CircuitPhasor;
import com.firedoge.emcore.api.circuit.CircuitPort;
import com.firedoge.emcore.api.circuit.CircuitSample;
import com.firedoge.emcore.api.circuit.CircuitSnapshot;
import com.firedoge.emcore.api.circuit.CircuitTopologyBuilder;
import com.firedoge.emcore.api.circuit.CircuitTopologyElement;
import com.firedoge.emcore.api.circuit.DiodeElement;
import com.firedoge.emcore.api.circuit.InductorElement;
import com.firedoge.emcore.api.circuit.LinearCircuitElement;
import com.firedoge.emcore.api.circuit.ResistorElement;
import com.firedoge.emcore.api.circuit.TransientVoltageSourceElement;
import com.firedoge.emcore.api.circuit.VoltageSourceElement;
import com.firedoge.emcore.api.circuit.WireElement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

public final class CircuitNetworkSmokeTest {
    private static final String TEST_NAMESPACE = "emcore_test";
    private static final double EPSILON = 1.0e-9;

    private CircuitNetworkSmokeTest() {
    }

    public static void main(String[] args) {
        solvesSourceResistorLoop();
        stepsTransientRcChargingCircuit();
        stepsTransientRlEnergizingCircuit();
        stepsTransientDiodeCapacitorChargingCircuit();
        solvesBatchTransientRcWithTimeVaryingSource();
        solvesBatchTransientDiodeCapacitorCircuit();
        solvesForwardBiasedDiodeOperatingPoint();
        solvesReverseBiasedDiodeLeakage();
        linearizesForwardBiasedDiodeForAcSmallSignal();
        reportsShortedVoltageSource();
        reportsConflictingVoltageSources();
        solvesHealthyComponentWhenAnotherComponentIsInvalid();
        solvesCustomLinearElement();
        solvesCustomTopologyElement();
        solvesVoltageControlledCurrentSource();
        solvesVoltageControlledVoltageSource();
        solvesCurrentControlledCurrentSource();
        solvesCurrentControlledVoltageSource();
        reportsDenseSolverScaleWarning();
        solvesComplexDenseLinearSystem();
        returnsNullForSingularComplexLinearSystem();
        solvesAcResistiveLoop();
        solvesAcCapacitiveLoopUsingFrequencyContext();
        solvesAcVoltageControlledCurrentSource();
        solvesAcVoltageControlledVoltageSource();
        solvesAcCurrentControlledCurrentSource();
        solvesAcCurrentControlledVoltageSource();
    }

    private static void solvesSourceResistorLoop() {
        CircuitNetwork network = new CircuitNetwork();
        TestLoop loop = registerSourceResistorLoop(network, "loop", new BlockPos(0, 0, 0));

        CircuitSnapshot snapshot = network.snapshot(0.0);
        Map<CircuitPort, CircuitSample> samples = samplesByPort(snapshot);

        assertEquals(0, snapshot.diagnostics().size(), "normal loop diagnostics");
        assertClose(12.0, samples.get(loop.sourcePositive()).voltageVolts(), "source positive voltage");
        assertClose(0.0, samples.get(loop.sourceNegative()).voltageVolts(), "source negative voltage");
        assertClose(2.0, Math.abs(samples.get(loop.resistorPositive()).currentAmps()), "resistor current");
        assertClose(24.0, Math.abs(samples.get(loop.resistorPositive()).powerWatts()), "resistor power");
    }

    private static void stepsTransientRcChargingCircuit() {
        CircuitNetwork network = new CircuitNetwork();
        CircuitPort sourcePositive = port("transient_rc/source_positive", new BlockPos(0, 0, 0));
        CircuitPort sourceNegative = port("transient_rc/source_negative", new BlockPos(1, 0, 0));
        CircuitPort resistorPositive = port("transient_rc/resistor_positive", new BlockPos(0, 0, 1));
        CircuitPort resistorNegative = port("transient_rc/resistor_negative", new BlockPos(1, 0, 1));
        CircuitPort capacitorPositive = port("transient_rc/capacitor_positive", new BlockPos(0, 0, 2));
        CircuitPort capacitorNegative = port("transient_rc/capacitor_negative", new BlockPos(1, 0, 2));

        network.registerElement(new VoltageSourceElement(id("transient_rc/source"), sourcePositive, sourceNegative, 10.0));
        network.registerElement(new ResistorElement(id("transient_rc/resistor"), resistorPositive, resistorNegative, 1_000.0));
        network.registerElement(new CapacitorElement(id("transient_rc/capacitor"), capacitorPositive, capacitorNegative, 1.0e-6));
        network.registerElement(new TestIdealLinkElement(id("transient_rc/source_link"), sourcePositive, resistorPositive));
        network.registerElement(new TestIdealLinkElement(id("transient_rc/capacitor_link"), resistorNegative, capacitorPositive));
        network.registerElement(new TestIdealLinkElement(id("transient_rc/ground_link"), capacitorNegative, sourceNegative));

        CircuitSnapshot firstStep = network.stepTransient(0.001, 0.001);
        Map<CircuitPort, CircuitSample> firstSamples = samplesByPort(firstStep);
        CircuitSnapshot secondStep = network.stepTransient(0.001, 0.002);
        Map<CircuitPort, CircuitSample> secondSamples = samplesByPort(secondStep);

        assertEquals(0, firstStep.diagnostics().size(), "transient RC first-step diagnostics");
        assertEquals(0, secondStep.diagnostics().size(), "transient RC second-step diagnostics");
        assertClose(5.0, firstSamples.get(capacitorPositive).voltageVolts(), "transient RC first-step capacitor voltage");
        assertClose(7.5, secondSamples.get(capacitorPositive).voltageVolts(), "transient RC second-step capacitor voltage");
        assertClose(12.5e-6, firstSamples.get(capacitorPositive).storedEnergyJoules(), "transient RC first-step stored energy");
        assertClose(28.125e-6, secondSamples.get(capacitorPositive).storedEnergyJoules(), "transient RC second-step stored energy");
    }

    private static void stepsTransientRlEnergizingCircuit() {
        CircuitNetwork network = new CircuitNetwork();
        CircuitPort sourcePositive = port("transient_rl/source_positive", new BlockPos(0, 0, 0));
        CircuitPort sourceNegative = port("transient_rl/source_negative", new BlockPos(1, 0, 0));
        CircuitPort resistorPositive = port("transient_rl/resistor_positive", new BlockPos(0, 0, 1));
        CircuitPort resistorNegative = port("transient_rl/resistor_negative", new BlockPos(1, 0, 1));
        CircuitPort inductorPositive = port("transient_rl/inductor_positive", new BlockPos(0, 0, 2));
        CircuitPort inductorNegative = port("transient_rl/inductor_negative", new BlockPos(1, 0, 2));

        network.registerElement(new VoltageSourceElement(id("transient_rl/source"), sourcePositive, sourceNegative, 10.0));
        network.registerElement(new ResistorElement(id("transient_rl/resistor"), resistorPositive, resistorNegative, 1.0));
        network.registerElement(new InductorElement(id("transient_rl/inductor"), inductorPositive, inductorNegative, 1.0));
        network.registerElement(new TestIdealLinkElement(id("transient_rl/source_link"), sourcePositive, resistorPositive));
        network.registerElement(new TestIdealLinkElement(id("transient_rl/inductor_link"), resistorNegative, inductorPositive));
        network.registerElement(new TestIdealLinkElement(id("transient_rl/ground_link"), inductorNegative, sourceNegative));

        CircuitSnapshot firstStep = network.stepTransient(0.5, 0.5);
        Map<CircuitPort, CircuitSample> firstSamples = samplesByPort(firstStep);
        CircuitSnapshot secondStep = network.stepTransient(0.5, 1.0);
        Map<CircuitPort, CircuitSample> secondSamples = samplesByPort(secondStep);

        assertEquals(0, firstStep.diagnostics().size(), "transient RL first-step diagnostics");
        assertEquals(0, secondStep.diagnostics().size(), "transient RL second-step diagnostics");
        assertClose(20.0 / 3.0, firstSamples.get(inductorPositive).voltageVolts(), "transient RL first-step inductor voltage");
        assertClose(10.0 / 3.0, firstSamples.get(inductorPositive).currentAmps(), "transient RL first-step inductor current");
        assertClose(40.0 / 9.0, secondSamples.get(inductorPositive).voltageVolts(), "transient RL second-step inductor voltage");
        assertClose(50.0 / 9.0, secondSamples.get(inductorPositive).currentAmps(), "transient RL second-step inductor current");
        assertClose(50.0 / 9.0, firstSamples.get(inductorPositive).storedEnergyJoules(), "transient RL first-step stored energy");
        assertClose(1_250.0 / 81.0, secondSamples.get(inductorPositive).storedEnergyJoules(), "transient RL second-step stored energy");
    }

    private static void stepsTransientDiodeCapacitorChargingCircuit() {
        CircuitNetwork network = new CircuitNetwork();
        CircuitPort sourcePositive = port("transient_diode_cap/source_positive", new BlockPos(0, 0, 0));
        CircuitPort sourceNegative = port("transient_diode_cap/source_negative", new BlockPos(1, 0, 0));
        CircuitPort diodeAnode = port("transient_diode_cap/anode", new BlockPos(0, 0, 1));
        CircuitPort diodeCathode = port("transient_diode_cap/cathode", new BlockPos(1, 0, 1));
        CircuitPort capacitorPositive = port("transient_diode_cap/capacitor_positive", new BlockPos(0, 0, 2));
        CircuitPort capacitorNegative = port("transient_diode_cap/capacitor_negative", new BlockPos(1, 0, 2));

        network.registerElement(new VoltageSourceElement(
                id("transient_diode_cap/source"),
                sourcePositive,
                sourceNegative,
                1.0
        ));
        network.registerElement(new DiodeElement(id("transient_diode_cap/diode"), diodeAnode, diodeCathode));
        network.registerElement(new CapacitorElement(
                id("transient_diode_cap/capacitor"),
                capacitorPositive,
                capacitorNegative,
                1.0e-6
        ));
        network.registerElement(new TestIdealLinkElement(id("transient_diode_cap/anode_link"), sourcePositive, diodeAnode));
        network.registerElement(new TestIdealLinkElement(
                id("transient_diode_cap/cathode_link"),
                diodeCathode,
                capacitorPositive
        ));
        network.registerElement(new TestIdealLinkElement(
                id("transient_diode_cap/ground_link"),
                capacitorNegative,
                sourceNegative
        ));

        CircuitSnapshot firstStep = network.stepTransient(0.001, 0.001);
        Map<CircuitPort, CircuitSample> firstSamples = samplesByPort(firstStep);
        CircuitSnapshot secondStep = network.stepTransient(0.001, 0.002);
        Map<CircuitPort, CircuitSample> secondSamples = samplesByPort(secondStep);
        double firstCapacitorVoltage = firstSamples.get(capacitorPositive).voltageVolts();
        double secondCapacitorVoltage = secondSamples.get(capacitorPositive).voltageVolts();
        double firstDiodeVoltage = firstSamples.get(diodeAnode).voltageVolts()
                - firstSamples.get(diodeCathode).voltageVolts();
        double secondDiodeVoltage = secondSamples.get(diodeAnode).voltageVolts()
                - secondSamples.get(diodeCathode).voltageVolts();

        assertEquals(0, firstStep.diagnostics().size(), "transient diode-capacitor first-step diagnostics");
        assertEquals(0, secondStep.diagnostics().size(), "transient diode-capacitor second-step diagnostics");
        assertBetween(0.45, 0.55, firstCapacitorVoltage, "transient diode-capacitor first-step capacitor voltage");
        assertBetween(0.50, 0.60, secondCapacitorVoltage, "transient diode-capacitor second-step capacitor voltage");
        assertBetween(0.48, 0.56, firstDiodeVoltage, "transient diode-capacitor first-step diode voltage");
        assertBetween(0.42, 0.52, secondDiodeVoltage, "transient diode-capacitor second-step diode voltage");
        assertBetween(
                firstCapacitorVoltage,
                0.60,
                secondCapacitorVoltage,
                "transient diode-capacitor capacitor voltage should increase"
        );
        assertBetween(
                firstSamples.get(capacitorPositive).storedEnergyJoules(),
                1.8e-7,
                secondSamples.get(capacitorPositive).storedEnergyJoules(),
                "transient diode-capacitor stored energy should increase"
        );
    }

    private static void solvesBatchTransientRcWithTimeVaryingSource() {
        CircuitPort sourcePositive = port("batch_rc/source_positive", new BlockPos(0, 0, 0));
        CircuitPort sourceNegative = port("batch_rc/source_negative", new BlockPos(1, 0, 0));
        CircuitPort resistorPositive = port("batch_rc/resistor_positive", new BlockPos(0, 0, 1));
        CircuitPort resistorNegative = port("batch_rc/resistor_negative", new BlockPos(1, 0, 1));
        CircuitPort capacitorPositive = port("batch_rc/capacitor_positive", new BlockPos(0, 0, 2));
        CircuitPort capacitorNegative = port("batch_rc/capacitor_negative", new BlockPos(1, 0, 2));

        List<CircuitElement> elements = List.of(
                new TransientVoltageSourceElement(
                        id("batch_rc/source"),
                        sourcePositive,
                        sourceNegative,
                        timeSeconds -> timeSeconds < 0.0015 ? 0.0 : 10.0
                ),
                new ResistorElement(id("batch_rc/resistor"), resistorPositive, resistorNegative, 1_000.0),
                new CapacitorElement(id("batch_rc/capacitor"), capacitorPositive, capacitorNegative, 1.0e-6),
                new TestIdealLinkElement(id("batch_rc/source_link"), sourcePositive, resistorPositive),
                new TestIdealLinkElement(id("batch_rc/capacitor_link"), resistorNegative, capacitorPositive),
                new TestIdealLinkElement(id("batch_rc/ground_link"), capacitorNegative, sourceNegative)
        );
        BatchTransientResult result = BatchTransientSolver.solve(new BatchTransientRequest(
                new CircuitNetlist(elements),
                0.001,
                2,
                List.of(sourcePositive, capacitorPositive)
        ));

        Map<CircuitPort, CircuitSample> firstSamples = samplesByPort(result.steps().getFirst().probeSamples());
        Map<CircuitPort, CircuitSample> secondSamples = samplesByPort(result.steps().get(1).probeSamples());

        assertEquals(0, result.diagnostics().size(), "batch transient diagnostics");
        assertEquals(2, result.steps().size(), "batch transient step count");
        assertClose(0.001, result.steps().getFirst().timeSeconds(), "batch transient first time");
        assertClose(0.002, result.steps().get(1).timeSeconds(), "batch transient second time");
        assertClose(0.0, firstSamples.get(sourcePositive).voltageVolts(), "batch transient first source voltage");
        assertClose(0.0, firstSamples.get(capacitorPositive).voltageVolts(), "batch transient first capacitor voltage");
        assertClose(10.0, secondSamples.get(sourcePositive).voltageVolts(), "batch transient second source voltage");
        assertClose(5.0, secondSamples.get(capacitorPositive).voltageVolts(), "batch transient second capacitor voltage");
    }

    private static void solvesBatchTransientDiodeCapacitorCircuit() {
        CircuitPort sourcePositive = port("batch_diode_cap/source_positive", new BlockPos(0, 0, 0));
        CircuitPort sourceNegative = port("batch_diode_cap/source_negative", new BlockPos(1, 0, 0));
        CircuitPort diodeAnode = port("batch_diode_cap/anode", new BlockPos(0, 0, 1));
        CircuitPort diodeCathode = port("batch_diode_cap/cathode", new BlockPos(1, 0, 1));
        CircuitPort capacitorPositive = port("batch_diode_cap/capacitor_positive", new BlockPos(0, 0, 2));
        CircuitPort capacitorNegative = port("batch_diode_cap/capacitor_negative", new BlockPos(1, 0, 2));

        List<CircuitElement> elements = List.of(
                new TransientVoltageSourceElement(
                        id("batch_diode_cap/source"),
                        sourcePositive,
                        sourceNegative,
                        ignored -> 1.0
                ),
                new DiodeElement(id("batch_diode_cap/diode"), diodeAnode, diodeCathode),
                new CapacitorElement(id("batch_diode_cap/capacitor"), capacitorPositive, capacitorNegative, 1.0e-6),
                new TestIdealLinkElement(id("batch_diode_cap/anode_link"), sourcePositive, diodeAnode),
                new TestIdealLinkElement(id("batch_diode_cap/cathode_link"), diodeCathode, capacitorPositive),
                new TestIdealLinkElement(id("batch_diode_cap/ground_link"), capacitorNegative, sourceNegative)
        );
        BatchTransientResult result = BatchTransientSolver.solve(new BatchTransientRequest(
                new CircuitNetlist(elements),
                0.001,
                2,
                List.of(diodeAnode, diodeCathode, capacitorPositive)
        ));

        Map<CircuitPort, CircuitSample> firstSamples = samplesByPort(result.steps().getFirst().probeSamples());
        Map<CircuitPort, CircuitSample> secondSamples = samplesByPort(result.steps().get(1).probeSamples());
        double firstCapacitorVoltage = firstSamples.get(capacitorPositive).voltageVolts();
        double secondCapacitorVoltage = secondSamples.get(capacitorPositive).voltageVolts();

        assertEquals(0, result.diagnostics().size(), "batch diode-capacitor diagnostics");
        assertBetween(0.45, 0.55, firstCapacitorVoltage, "batch diode-capacitor first-step capacitor voltage");
        assertBetween(0.50, 0.60, secondCapacitorVoltage, "batch diode-capacitor second-step capacitor voltage");
        assertBetween(
                firstCapacitorVoltage,
                0.60,
                secondCapacitorVoltage,
                "batch diode-capacitor capacitor voltage should increase"
        );
    }

    private static void solvesForwardBiasedDiodeOperatingPoint() {
        CircuitNetwork network = new CircuitNetwork();
        CircuitPort sourcePositive = port("diode_forward/source_positive", new BlockPos(0, 0, 0));
        CircuitPort sourceNegative = port("diode_forward/source_negative", new BlockPos(1, 0, 0));
        CircuitPort resistorPositive = port("diode_forward/resistor_positive", new BlockPos(0, 0, 1));
        CircuitPort resistorNegative = port("diode_forward/resistor_negative", new BlockPos(1, 0, 1));
        CircuitPort diodeAnode = port("diode_forward/anode", new BlockPos(0, 0, 2));
        CircuitPort diodeCathode = port("diode_forward/cathode", new BlockPos(1, 0, 2));

        network.registerElement(new VoltageSourceElement(id("diode_forward/source"), sourcePositive, sourceNegative, 1.0));
        network.registerElement(new ResistorElement(id("diode_forward/resistor"), resistorPositive, resistorNegative, 1_000.0));
        network.registerElement(new DiodeElement(id("diode_forward/diode"), diodeAnode, diodeCathode));
        network.registerElement(new TestIdealLinkElement(id("diode_forward/source_link"), sourcePositive, resistorPositive));
        network.registerElement(new TestIdealLinkElement(id("diode_forward/anode_link"), resistorNegative, diodeAnode));
        network.registerElement(new TestIdealLinkElement(id("diode_forward/cathode_link"), diodeCathode, sourceNegative));

        CircuitSnapshot snapshot = network.snapshot(0.0);
        Map<CircuitPort, CircuitSample> samples = samplesByPort(snapshot);
        double diodeVoltage = samples.get(diodeAnode).voltageVolts() - samples.get(diodeCathode).voltageVolts();
        double diodeCurrent = samples.get(diodeAnode).currentAmps();

        assertEquals(0, snapshot.diagnostics().size(), "forward diode diagnostics");
        assertBetween(0.45, 0.65, diodeVoltage, "forward diode voltage");
        assertBetween(3.0e-4, 6.0e-4, diodeCurrent, "forward diode current");
        assertClose((1.0 - diodeVoltage) / 1_000.0, diodeCurrent, "forward diode current balance", 1.0e-6);
    }

    private static void solvesReverseBiasedDiodeLeakage() {
        CircuitNetwork network = new CircuitNetwork();
        CircuitPort sourcePositive = port("diode_reverse/source_positive", new BlockPos(0, 0, 0));
        CircuitPort sourceNegative = port("diode_reverse/source_negative", new BlockPos(1, 0, 0));
        CircuitPort resistorPositive = port("diode_reverse/resistor_positive", new BlockPos(0, 0, 1));
        CircuitPort resistorNegative = port("diode_reverse/resistor_negative", new BlockPos(1, 0, 1));
        CircuitPort diodeAnode = port("diode_reverse/anode", new BlockPos(0, 0, 2));
        CircuitPort diodeCathode = port("diode_reverse/cathode", new BlockPos(1, 0, 2));

        network.registerElement(new VoltageSourceElement(id("diode_reverse/source"), sourcePositive, sourceNegative, -1.0));
        network.registerElement(new ResistorElement(id("diode_reverse/resistor"), resistorPositive, resistorNegative, 1_000.0));
        network.registerElement(new DiodeElement(id("diode_reverse/diode"), diodeAnode, diodeCathode));
        network.registerElement(new TestIdealLinkElement(id("diode_reverse/source_link"), sourcePositive, resistorPositive));
        network.registerElement(new TestIdealLinkElement(id("diode_reverse/anode_link"), resistorNegative, diodeAnode));
        network.registerElement(new TestIdealLinkElement(id("diode_reverse/cathode_link"), diodeCathode, sourceNegative));

        CircuitSnapshot snapshot = network.snapshot(0.0);
        Map<CircuitPort, CircuitSample> samples = samplesByPort(snapshot);
        double diodeVoltage = samples.get(diodeAnode).voltageVolts() - samples.get(diodeCathode).voltageVolts();
        double diodeCurrent = samples.get(diodeAnode).currentAmps();

        assertEquals(0, snapshot.diagnostics().size(), "reverse diode diagnostics");
        assertBetween(-1.01, -0.99, diodeVoltage, "reverse diode voltage");
        assertBetween(-1.1e-12, -0.9e-12, diodeCurrent, "reverse diode current");
    }

    private static void linearizesForwardBiasedDiodeForAcSmallSignal() {
        CircuitNetwork network = new CircuitNetwork();
        CircuitPort sourcePositive = port("diode_small_signal/source_positive", new BlockPos(0, 0, 0));
        CircuitPort sourceNegative = port("diode_small_signal/source_negative", new BlockPos(1, 0, 0));
        CircuitPort resistorPositive = port("diode_small_signal/resistor_positive", new BlockPos(0, 0, 1));
        CircuitPort resistorNegative = port("diode_small_signal/resistor_negative", new BlockPos(1, 0, 1));
        CircuitPort diodeAnode = port("diode_small_signal/anode", new BlockPos(0, 0, 2));
        CircuitPort diodeCathode = port("diode_small_signal/cathode", new BlockPos(1, 0, 2));
        double smallSignalCurrentAmps = 1.0e-6;

        network.registerElement(new VoltageSourceElement(id("diode_small_signal/source"), sourcePositive, sourceNegative, 1.0));
        network.registerElement(new ResistorElement(id("diode_small_signal/resistor"), resistorPositive, resistorNegative, 1_000.0));
        network.registerElement(new DiodeElement(id("diode_small_signal/diode"), diodeAnode, diodeCathode));
        network.registerElement(new TestIdealLinkElement(id("diode_small_signal/source_link"), sourcePositive, resistorPositive));
        network.registerElement(new TestIdealLinkElement(id("diode_small_signal/anode_link"), resistorNegative, diodeAnode));
        network.registerElement(new TestIdealLinkElement(id("diode_small_signal/cathode_link"), diodeCathode, sourceNegative));
        network.registerElement(new TestAcCurrentSourceElement(
                id("diode_small_signal/ac_current"),
                diodeCathode,
                diodeAnode,
                CircuitPhasor.real(smallSignalCurrentAmps)
        ));

        CircuitSnapshot dcSnapshot = network.snapshot(0.0);
        Map<CircuitPort, CircuitSample> dcSamples = samplesByPort(dcSnapshot);
        double diodeCurrent = dcSamples.get(diodeAnode).currentAmps();
        double expectedConductance = (diodeCurrent + DiodeElement.DEFAULT_SATURATION_CURRENT_AMPS)
                / DiodeElement.DEFAULT_THERMAL_VOLTAGE_VOLTS;
        double expectedVoltage = smallSignalCurrentAmps / expectedConductance;

        AcCircuitSnapshot acSnapshot = network.acSnapshot(1_000.0, 0.0);
        Map<CircuitPort, AcCircuitSample> acSamples = acSamplesByPort(acSnapshot);
        CircuitPhasor diodeVoltage = phasorDifference(
                acSamples.get(diodeAnode).voltageVolts(),
                acSamples.get(diodeCathode).voltageVolts()
        );

        assertEquals(0, dcSnapshot.diagnostics().size(), "small-signal diode DC diagnostics");
        assertEquals(0, acSnapshot.diagnostics().size(), "small-signal diode AC diagnostics");
        assertPhasorClose(CircuitPhasor.real(expectedVoltage), diodeVoltage, "small-signal diode voltage");
    }

    private static void reportsShortedVoltageSource() {
        CircuitNetwork network = new CircuitNetwork();
        CircuitPort positive = port("short/positive", new BlockPos(0, 0, 0));
        CircuitPort negative = port("short/negative", new BlockPos(1, 0, 0));

        network.registerElement(new VoltageSourceElement(id("short/source"), positive, negative, 12.0));
        network.registerElement(new WireElement(id("short/wire"), positive, negative));

        CircuitSnapshot snapshot = network.snapshot(0.0);
        assertHasDiagnostic(snapshot, CircuitDiagnosticType.VOLTAGE_SOURCE_SHORT);
    }

    private static void reportsConflictingVoltageSources() {
        CircuitNetwork network = new CircuitNetwork();
        CircuitPort positive = port("conflict/positive", new BlockPos(0, 0, 0));
        CircuitPort negative = port("conflict/negative", new BlockPos(1, 0, 0));

        network.registerElement(new VoltageSourceElement(id("conflict/source_12v"), positive, negative, 12.0));
        network.registerElement(new VoltageSourceElement(id("conflict/source_5v"), positive, negative, 5.0));

        CircuitSnapshot snapshot = network.snapshot(0.0);
        assertHasDiagnostic(snapshot, CircuitDiagnosticType.VOLTAGE_SOURCE_CONFLICT);
    }

    private static void solvesHealthyComponentWhenAnotherComponentIsInvalid() {
        CircuitNetwork network = new CircuitNetwork();
        TestLoop loop = registerSourceResistorLoop(network, "independent/loop", new BlockPos(0, 0, 0));
        CircuitPort shortPositive = port("independent/short_positive", new BlockPos(10, 0, 0));
        CircuitPort shortNegative = port("independent/short_negative", new BlockPos(11, 0, 0));

        network.registerElement(new VoltageSourceElement(
                id("independent/short_source"),
                shortPositive,
                shortNegative,
                12.0
        ));
        network.registerElement(new WireElement(id("independent/short_wire"), shortPositive, shortNegative));

        CircuitSnapshot snapshot = network.snapshot(0.0);
        Map<CircuitPort, CircuitSample> samples = samplesByPort(snapshot);

        assertHasDiagnostic(snapshot, CircuitDiagnosticType.VOLTAGE_SOURCE_SHORT);
        assertClose(12.0, samples.get(loop.sourcePositive()).voltageVolts(), "healthy component source voltage");
        assertClose(2.0, Math.abs(samples.get(loop.resistorPositive()).currentAmps()), "healthy component current");
    }

    private static void solvesCustomLinearElement() {
        CircuitNetwork network = new CircuitNetwork();
        CircuitPort high = port("custom/high", new BlockPos(0, 0, 0));
        CircuitPort low = port("custom/low", new BlockPos(1, 0, 0));

        network.registerElement(new ResistorElement(id("custom/resistor"), high, low, 6.0));
        network.registerElement(new TestCurrentSourceElement(id("custom/current_source"), low, high, 2.0));

        CircuitSnapshot snapshot = network.snapshot(0.0);
        Map<CircuitPort, CircuitSample> samples = samplesByPort(snapshot);

        assertEquals(0, snapshot.diagnostics().size(), "custom element diagnostics");
        assertClose(12.0, samples.get(high).voltageVolts() - samples.get(low).voltageVolts(), "custom element voltage");
    }

    private static void solvesCustomTopologyElement() {
        CircuitNetwork network = new CircuitNetwork();
        CircuitPort sourcePositive = port("custom_topology/source_positive", new BlockPos(0, 0, 0));
        CircuitPort sourceNegative = port("custom_topology/source_negative", new BlockPos(1, 0, 0));
        CircuitPort resistorPositive = port("custom_topology/resistor_positive", new BlockPos(0, 0, 1));
        CircuitPort resistorNegative = port("custom_topology/resistor_negative", new BlockPos(1, 0, 1));

        network.registerElement(new VoltageSourceElement(
                id("custom_topology/source"),
                sourcePositive,
                sourceNegative,
                12.0
        ));
        network.registerElement(new ResistorElement(
                id("custom_topology/resistor"),
                resistorPositive,
                resistorNegative,
                6.0
        ));
        network.registerElement(new TestIdealLinkElement(
                id("custom_topology/positive_link"),
                sourcePositive,
                resistorPositive
        ));
        network.registerElement(new TestIdealLinkElement(
                id("custom_topology/negative_link"),
                sourceNegative,
                resistorNegative
        ));

        CircuitSnapshot snapshot = network.snapshot(0.0);
        Map<CircuitPort, CircuitSample> samples = samplesByPort(snapshot);

        assertEquals(0, snapshot.diagnostics().size(), "custom topology diagnostics");
        assertClose(2.0, Math.abs(samples.get(resistorPositive).currentAmps()), "custom topology current");
    }

    private static void solvesVoltageControlledCurrentSource() {
        CircuitNetwork network = new CircuitNetwork();
        CircuitPort controlHigh = port("vccs/control_high", new BlockPos(0, 0, 0));
        CircuitPort ground = port("vccs/ground", new BlockPos(1, 0, 0));
        CircuitPort outputHigh = port("vccs/output_high", new BlockPos(2, 0, 0));

        network.registerElement(new VoltageSourceElement(id("vccs/control_source"), controlHigh, ground, 2.0));
        network.registerElement(new ResistorElement(id("vccs/load"), outputHigh, ground, 10.0));
        network.registerElement(new TestVoltageControlledCurrentSourceElement(
                id("vccs/source"),
                ground,
                outputHigh,
                controlHigh,
                ground,
                0.1
        ));

        CircuitSnapshot snapshot = network.snapshot(0.0);
        Map<CircuitPort, CircuitSample> samples = samplesByPort(snapshot);

        assertEquals(0, snapshot.diagnostics().size(), "vccs diagnostics");
        assertClose(2.0, samples.get(outputHigh).voltageVolts() - samples.get(ground).voltageVolts(), "vccs output voltage");
    }

    private static void solvesVoltageControlledVoltageSource() {
        CircuitNetwork network = new CircuitNetwork();
        CircuitPort controlHigh = port("vcvs/control_high", new BlockPos(0, 0, 0));
        CircuitPort ground = port("vcvs/ground", new BlockPos(1, 0, 0));
        CircuitPort sourceHigh = port("vcvs/source_high", new BlockPos(2, 0, 0));
        CircuitPort loadHigh = port("vcvs/load_high", new BlockPos(3, 0, 0));

        network.registerElement(new VoltageSourceElement(id("vcvs/control_source"), controlHigh, ground, 3.0));
        network.registerElement(new ResistorElement(id("vcvs/load"), loadHigh, ground, 6.0));
        network.registerElement(new TestVoltageControlledVoltageSourceElement(
                id("vcvs/source"),
                sourceHigh,
                ground,
                controlHigh,
                ground,
                2.0
        ));
        network.registerElement(new TestIdealLinkElement(id("vcvs/high_link"), sourceHigh, loadHigh));

        CircuitSnapshot snapshot = network.snapshot(0.0);
        Map<CircuitPort, CircuitSample> samples = samplesByPort(snapshot);

        assertEquals(0, snapshot.diagnostics().size(), "vcvs diagnostics");
        assertClose(6.0, samples.get(loadHigh).voltageVolts() - samples.get(ground).voltageVolts(), "vcvs output voltage");
        assertClose(1.0, Math.abs(samples.get(loadHigh).currentAmps()), "vcvs load current");
    }

    private static void solvesCurrentControlledCurrentSource() {
        CircuitNetwork network = new CircuitNetwork();
        CircuitPort controlSourceHigh = port("cccs/control_source_high", new BlockPos(0, 0, 0));
        CircuitPort controlLoadHigh = port("cccs/control_load_high", new BlockPos(1, 0, 0));
        CircuitPort ground = port("cccs/ground", new BlockPos(2, 0, 0));
        CircuitPort outputHigh = port("cccs/output_high", new BlockPos(3, 0, 0));

        network.registerElement(new VoltageSourceElement(id("cccs/control_source"), controlSourceHigh, ground, 12.0));
        network.registerElement(new ResistorElement(id("cccs/control_load"), controlLoadHigh, ground, 6.0));
        network.registerElement(new ResistorElement(id("cccs/output_load"), outputHigh, ground, 10.0));
        network.registerElement(new TestProbeControlledCurrentSourceElement(
                id("cccs/source"),
                controlSourceHigh,
                controlLoadHigh,
                ground,
                outputHigh,
                0.1
        ));

        CircuitSnapshot snapshot = network.snapshot(0.0);
        Map<CircuitPort, CircuitSample> samples = samplesByPort(snapshot);

        assertEquals(0, snapshot.diagnostics().size(), "cccs diagnostics");
        assertClose(2.0, samples.get(outputHigh).voltageVolts() - samples.get(ground).voltageVolts(), "cccs output voltage");
    }

    private static void solvesCurrentControlledVoltageSource() {
        CircuitNetwork network = new CircuitNetwork();
        CircuitPort controlSourceHigh = port("ccvs/control_source_high", new BlockPos(0, 0, 0));
        CircuitPort controlLoadHigh = port("ccvs/control_load_high", new BlockPos(1, 0, 0));
        CircuitPort ground = port("ccvs/ground", new BlockPos(2, 0, 0));
        CircuitPort outputSourceHigh = port("ccvs/output_source_high", new BlockPos(3, 0, 0));
        CircuitPort outputLoadHigh = port("ccvs/output_load_high", new BlockPos(4, 0, 0));

        network.registerElement(new VoltageSourceElement(id("ccvs/control_source"), controlSourceHigh, ground, 12.0));
        network.registerElement(new ResistorElement(id("ccvs/control_load"), controlLoadHigh, ground, 6.0));
        network.registerElement(new ResistorElement(id("ccvs/output_load"), outputLoadHigh, ground, 6.0));
        network.registerElement(new TestProbeControlledVoltageSourceElement(
                id("ccvs/source"),
                controlSourceHigh,
                controlLoadHigh,
                outputSourceHigh,
                ground,
                3.0
        ));
        network.registerElement(new TestIdealLinkElement(id("ccvs/output_link"), outputSourceHigh, outputLoadHigh));

        CircuitSnapshot snapshot = network.snapshot(0.0);
        Map<CircuitPort, CircuitSample> samples = samplesByPort(snapshot);

        assertEquals(0, snapshot.diagnostics().size(), "ccvs diagnostics");
        assertClose(6.0, samples.get(outputLoadHigh).voltageVolts() - samples.get(ground).voltageVolts(), "ccvs output voltage");
        assertClose(1.0, Math.abs(samples.get(outputLoadHigh).currentAmps()), "ccvs output current");
    }

    private static void reportsDenseSolverScaleWarning() {
        CircuitNetwork network = new CircuitNetwork();
        CircuitPort ground = port("large/ground", new BlockPos(0, 0, 0));
        CircuitPort previous = port("large/node_0", new BlockPos(1, 0, 0));

        network.registerElement(new VoltageSourceElement(id("large/source"), previous, ground, 1.0));
        for (int index = 1; index <= 127; index++) {
            CircuitPort next = port("large/node_" + index, new BlockPos(index + 1, 0, 0));
            network.registerElement(new ResistorElement(id("large/resistor_" + index), previous, next, 1.0));
            previous = next;
        }

        CircuitSnapshot snapshot = network.snapshot(0.0);
        assertHasDiagnostic(snapshot, CircuitDiagnosticType.DENSE_SOLVER_SCALE_WARNING);
    }

    private static void solvesComplexDenseLinearSystem() {
        Complex[][] matrix = {
                {Complex.of(2.0, 1.0), Complex.of(1.0, -1.0)},
                {Complex.real(3.0), Complex.I.negate()}
        };
        Complex[] rhs = {
                Complex.of(2.0, 1.0),
                Complex.of(2.0, 3.0)
        };

        Complex[] solution = DenseLinearSolver.solve(matrix, rhs);

        assertClose(1.0, solution[0].real(), "complex solver x real");
        assertClose(2.0, solution[0].imaginary(), "complex solver x imaginary");
        assertClose(3.0, solution[1].real(), "complex solver y real");
        assertClose(-1.0, solution[1].imaginary(), "complex solver y imaginary");
    }

    private static void returnsNullForSingularComplexLinearSystem() {
        Complex[][] matrix = {
                {Complex.ONE, Complex.ONE},
                {Complex.ONE, Complex.ONE}
        };
        Complex[] rhs = {
                Complex.ONE,
                Complex.of(2.0, 0.0)
        };

        Complex[] solution = DenseLinearSolver.solve(matrix, rhs);

        assertNull(solution, "singular complex solver result");
    }

    private static void solvesAcResistiveLoop() {
        CircuitNetwork network = new CircuitNetwork();
        CircuitPort sourcePositive = port("ac_resistor/source_positive", new BlockPos(0, 0, 0));
        CircuitPort sourceNegative = port("ac_resistor/source_negative", new BlockPos(1, 0, 0));
        CircuitPort loadPositive = port("ac_resistor/load_positive", new BlockPos(0, 0, 1));
        CircuitPort loadNegative = port("ac_resistor/load_negative", new BlockPos(1, 0, 1));

        network.registerElement(new TestAcVoltageSourceElement(
                id("ac_resistor/source"),
                sourcePositive,
                sourceNegative,
                CircuitPhasor.real(10.0)
        ));
        network.registerElement(new TestAcAdmittanceElement(
                id("ac_resistor/load"),
                loadPositive,
                loadNegative,
                CircuitPhasor.real(0.001)
        ));
        network.registerElement(new TestIdealLinkElement(id("ac_resistor/positive_link"), sourcePositive, loadPositive));
        network.registerElement(new TestIdealLinkElement(id("ac_resistor/negative_link"), sourceNegative, loadNegative));

        AcCircuitSnapshot snapshot = network.acSnapshot(60.0, 0.0);
        Map<CircuitPort, AcCircuitSample> samples = acSamplesByPort(snapshot);

        assertEquals(0, snapshot.diagnostics().size(), "ac resistor diagnostics");
        assertPhasorClose(CircuitPhasor.real(10.0), samples.get(loadPositive).voltageVolts(), "ac resistor voltage");
        assertPhasorClose(CircuitPhasor.real(0.01), samples.get(loadPositive).currentAmps(), "ac resistor current");
    }

    private static void solvesAcCapacitiveLoopUsingFrequencyContext() {
        CircuitNetwork network = new CircuitNetwork();
        CircuitPort sourcePositive = port("ac_capacitor/source_positive", new BlockPos(0, 0, 0));
        CircuitPort sourceNegative = port("ac_capacitor/source_negative", new BlockPos(1, 0, 0));
        CircuitPort capacitorPositive = port("ac_capacitor/capacitor_positive", new BlockPos(0, 0, 1));
        CircuitPort capacitorNegative = port("ac_capacitor/capacitor_negative", new BlockPos(1, 0, 1));
        double frequencyHertz = 50.0;
        double capacitanceFarads = 0.001;

        network.registerElement(new TestAcVoltageSourceElement(
                id("ac_capacitor/source"),
                sourcePositive,
                sourceNegative,
                CircuitPhasor.real(10.0)
        ));
        network.registerElement(new TestAcCapacitorElement(
                id("ac_capacitor/capacitor"),
                capacitorPositive,
                capacitorNegative,
                capacitanceFarads
        ));
        network.registerElement(new TestIdealLinkElement(
                id("ac_capacitor/positive_link"),
                sourcePositive,
                capacitorPositive
        ));
        network.registerElement(new TestIdealLinkElement(
                id("ac_capacitor/negative_link"),
                sourceNegative,
                capacitorNegative
        ));

        AcCircuitSnapshot snapshot = network.acSnapshot(frequencyHertz, 0.0);
        Map<CircuitPort, AcCircuitSample> samples = acSamplesByPort(snapshot);
        double expectedImaginaryCurrent = 2.0 * Math.PI * frequencyHertz * capacitanceFarads * 10.0;

        assertEquals(0, snapshot.diagnostics().size(), "ac capacitor diagnostics");
        assertPhasorClose(
                CircuitPhasor.of(0.0, expectedImaginaryCurrent),
                samples.get(capacitorPositive).currentAmps(),
                "ac capacitor current"
        );
    }

    private static void solvesAcVoltageControlledCurrentSource() {
        CircuitNetwork network = new CircuitNetwork();
        CircuitPort controlHigh = port("ac_vccs/control_high", new BlockPos(0, 0, 0));
        CircuitPort ground = port("ac_vccs/ground", new BlockPos(1, 0, 0));
        CircuitPort outputHigh = port("ac_vccs/output_high", new BlockPos(2, 0, 0));

        network.registerElement(new TestAcVoltageSourceElement(
                id("ac_vccs/control_source"),
                controlHigh,
                ground,
                CircuitPhasor.real(2.0)
        ));
        network.registerElement(new TestAcAdmittanceElement(
                id("ac_vccs/load"),
                outputHigh,
                ground,
                CircuitPhasor.real(0.1)
        ));
        network.registerElement(new TestAcVoltageControlledCurrentSourceElement(
                id("ac_vccs/source"),
                ground,
                outputHigh,
                controlHigh,
                ground,
                CircuitPhasor.of(0.0, 0.1)
        ));

        AcCircuitSnapshot snapshot = network.acSnapshot(60.0, 0.0);
        Map<CircuitPort, AcCircuitSample> samples = acSamplesByPort(snapshot);

        assertEquals(0, snapshot.diagnostics().size(), "ac vccs diagnostics");
        assertPhasorClose(
                CircuitPhasor.of(0.0, 2.0),
                phasorDifference(samples.get(outputHigh).voltageVolts(), samples.get(ground).voltageVolts()),
                "ac vccs output voltage"
        );
    }

    private static void solvesAcVoltageControlledVoltageSource() {
        CircuitNetwork network = new CircuitNetwork();
        CircuitPort controlHigh = port("ac_vcvs/control_high", new BlockPos(0, 0, 0));
        CircuitPort ground = port("ac_vcvs/ground", new BlockPos(1, 0, 0));
        CircuitPort sourceHigh = port("ac_vcvs/source_high", new BlockPos(2, 0, 0));
        CircuitPort loadHigh = port("ac_vcvs/load_high", new BlockPos(3, 0, 0));

        network.registerElement(new TestAcVoltageSourceElement(
                id("ac_vcvs/control_source"),
                controlHigh,
                ground,
                CircuitPhasor.real(3.0)
        ));
        network.registerElement(new TestAcAdmittanceElement(
                id("ac_vcvs/load"),
                loadHigh,
                ground,
                CircuitPhasor.real(1.0 / 6.0)
        ));
        network.registerElement(new TestAcVoltageControlledVoltageSourceElement(
                id("ac_vcvs/source"),
                sourceHigh,
                ground,
                controlHigh,
                ground,
                CircuitPhasor.of(0.0, 2.0)
        ));
        network.registerElement(new TestIdealLinkElement(id("ac_vcvs/high_link"), sourceHigh, loadHigh));

        AcCircuitSnapshot snapshot = network.acSnapshot(60.0, 0.0);
        Map<CircuitPort, AcCircuitSample> samples = acSamplesByPort(snapshot);

        assertEquals(0, snapshot.diagnostics().size(), "ac vcvs diagnostics");
        assertPhasorClose(
                CircuitPhasor.of(0.0, 6.0),
                phasorDifference(samples.get(loadHigh).voltageVolts(), samples.get(ground).voltageVolts()),
                "ac vcvs output voltage"
        );
        assertPhasorClose(
                CircuitPhasor.of(0.0, 1.0),
                samples.get(loadHigh).currentAmps(),
                "ac vcvs load current"
        );
    }

    private static void solvesAcCurrentControlledCurrentSource() {
        CircuitNetwork network = new CircuitNetwork();
        CircuitPort controlSourceHigh = port("ac_cccs/control_source_high", new BlockPos(0, 0, 0));
        CircuitPort controlLoadHigh = port("ac_cccs/control_load_high", new BlockPos(1, 0, 0));
        CircuitPort ground = port("ac_cccs/ground", new BlockPos(2, 0, 0));
        CircuitPort outputHigh = port("ac_cccs/output_high", new BlockPos(3, 0, 0));

        network.registerElement(new TestAcVoltageSourceElement(
                id("ac_cccs/control_source"),
                controlSourceHigh,
                ground,
                CircuitPhasor.real(12.0)
        ));
        network.registerElement(new TestAcAdmittanceElement(
                id("ac_cccs/control_load"),
                controlLoadHigh,
                ground,
                CircuitPhasor.real(1.0 / 6.0)
        ));
        network.registerElement(new TestAcAdmittanceElement(
                id("ac_cccs/output_load"),
                outputHigh,
                ground,
                CircuitPhasor.real(0.1)
        ));
        network.registerElement(new TestAcProbeControlledCurrentSourceElement(
                id("ac_cccs/source"),
                controlSourceHigh,
                controlLoadHigh,
                ground,
                outputHigh,
                CircuitPhasor.of(0.0, 0.1)
        ));

        AcCircuitSnapshot snapshot = network.acSnapshot(60.0, 0.0);
        Map<CircuitPort, AcCircuitSample> samples = acSamplesByPort(snapshot);

        assertEquals(0, snapshot.diagnostics().size(), "ac cccs diagnostics");
        assertPhasorClose(
                CircuitPhasor.of(0.0, 2.0),
                phasorDifference(samples.get(outputHigh).voltageVolts(), samples.get(ground).voltageVolts()),
                "ac cccs output voltage"
        );
    }

    private static void solvesAcCurrentControlledVoltageSource() {
        CircuitNetwork network = new CircuitNetwork();
        CircuitPort controlSourceHigh = port("ac_ccvs/control_source_high", new BlockPos(0, 0, 0));
        CircuitPort controlLoadHigh = port("ac_ccvs/control_load_high", new BlockPos(1, 0, 0));
        CircuitPort ground = port("ac_ccvs/ground", new BlockPos(2, 0, 0));
        CircuitPort outputSourceHigh = port("ac_ccvs/output_source_high", new BlockPos(3, 0, 0));
        CircuitPort outputLoadHigh = port("ac_ccvs/output_load_high", new BlockPos(4, 0, 0));

        network.registerElement(new TestAcVoltageSourceElement(
                id("ac_ccvs/control_source"),
                controlSourceHigh,
                ground,
                CircuitPhasor.real(12.0)
        ));
        network.registerElement(new TestAcAdmittanceElement(
                id("ac_ccvs/control_load"),
                controlLoadHigh,
                ground,
                CircuitPhasor.real(1.0 / 6.0)
        ));
        network.registerElement(new TestAcAdmittanceElement(
                id("ac_ccvs/output_load"),
                outputLoadHigh,
                ground,
                CircuitPhasor.real(1.0 / 6.0)
        ));
        network.registerElement(new TestAcProbeControlledVoltageSourceElement(
                id("ac_ccvs/source"),
                controlSourceHigh,
                controlLoadHigh,
                outputSourceHigh,
                ground,
                CircuitPhasor.of(0.0, 3.0)
        ));
        network.registerElement(new TestIdealLinkElement(id("ac_ccvs/output_link"), outputSourceHigh, outputLoadHigh));

        AcCircuitSnapshot snapshot = network.acSnapshot(60.0, 0.0);
        Map<CircuitPort, AcCircuitSample> samples = acSamplesByPort(snapshot);

        assertEquals(0, snapshot.diagnostics().size(), "ac ccvs diagnostics");
        assertPhasorClose(
                CircuitPhasor.of(0.0, 6.0),
                phasorDifference(samples.get(outputLoadHigh).voltageVolts(), samples.get(ground).voltageVolts()),
                "ac ccvs output voltage"
        );
        assertPhasorClose(
                CircuitPhasor.of(0.0, 1.0),
                samples.get(outputLoadHigh).currentAmps(),
                "ac ccvs output current"
        );
    }

    private static TestLoop registerSourceResistorLoop(CircuitNetwork network, String prefix, BlockPos base) {
        CircuitPort sourcePositive = port(prefix + "/source_positive", base);
        CircuitPort sourceNegative = port(prefix + "/source_negative", base.east());
        CircuitPort resistorPositive = port(prefix + "/resistor_positive", base.north());
        CircuitPort resistorNegative = port(prefix + "/resistor_negative", base.east().north());

        network.registerElement(new VoltageSourceElement(
                id(prefix + "/source"),
                sourcePositive,
                sourceNegative,
                12.0
        ));
        network.registerElement(new ResistorElement(
                id(prefix + "/resistor"),
                resistorPositive,
                resistorNegative,
                6.0
        ));
        network.registerElement(new WireElement(id(prefix + "/positive_wire"), sourcePositive, resistorPositive));
        network.registerElement(new WireElement(id(prefix + "/negative_wire"), sourceNegative, resistorNegative));

        return new TestLoop(sourcePositive, sourceNegative, resistorPositive);
    }

    private static Map<CircuitPort, CircuitSample> samplesByPort(CircuitSnapshot snapshot) {
        return samplesByPort(snapshot.samples());
    }

    private static Map<CircuitPort, CircuitSample> samplesByPort(List<CircuitSample> samplesList) {
        Map<CircuitPort, CircuitSample> samples = new LinkedHashMap<>();
        for (CircuitSample sample : samplesList) {
            samples.put(sample.port(), sample);
        }
        return samples;
    }

    private static Map<CircuitPort, AcCircuitSample> acSamplesByPort(AcCircuitSnapshot snapshot) {
        Map<CircuitPort, AcCircuitSample> samples = new LinkedHashMap<>();
        for (AcCircuitSample sample : snapshot.samples()) {
            samples.put(sample.port(), sample);
        }
        return samples;
    }

    private static void assertHasDiagnostic(CircuitSnapshot snapshot, CircuitDiagnosticType type) {
        boolean found = snapshot.diagnostics().stream().anyMatch(diagnostic -> diagnostic.type() == type);
        if (!found) {
            throw new AssertionError("Expected diagnostic " + type + ", got " + snapshot.diagnostics());
        }
    }

    private static void assertEquals(int expected, int actual, String label) {
        if (expected != actual) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertClose(double expected, double actual, String label) {
        if (Math.abs(expected - actual) > EPSILON) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertClose(double expected, double actual, String label, double epsilon) {
        if (Math.abs(expected - actual) > epsilon) {
            throw new AssertionError(label + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertBetween(double minInclusive, double maxInclusive, double actual, String label) {
        if (actual < minInclusive || actual > maxInclusive) {
            throw new AssertionError(label + ": expected between " + minInclusive + " and " + maxInclusive + ", got " + actual);
        }
    }

    private static void assertPhasorClose(CircuitPhasor expected, CircuitPhasor actual, String label) {
        assertClose(expected.real(), actual.real(), label + " real");
        assertClose(expected.imaginary(), actual.imaginary(), label + " imaginary");
    }

    private static CircuitPhasor phasorDifference(CircuitPhasor left, CircuitPhasor right) {
        return CircuitPhasor.of(left.real() - right.real(), left.imaginary() - right.imaginary());
    }

    private static void assertNull(Object value, String label) {
        if (value != null) {
            throw new AssertionError(label + ": expected null, got " + value);
        }
    }

    private static CircuitPort port(String path, BlockPos position) {
        return new CircuitPort(id("port/" + path), id("terminal/" + path), position, Direction.UP);
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(TEST_NAMESPACE, path);
    }

    private record TestLoop(CircuitPort sourcePositive, CircuitPort sourceNegative, CircuitPort resistorPositive) {
    }

    private record TestCurrentSourceElement(
            ResourceLocation id,
            CircuitPort positivePort,
            CircuitPort negativePort,
            double currentAmps
    ) implements LinearCircuitElement {
        @Override
        public List<CircuitPort> ports() {
            return List.of(positivePort, negativePort);
        }

        @Override
        public void stamp(CircuitEquationBuilder builder) {
            builder.addCurrentSource(positivePort, negativePort, currentAmps);
        }
    }

    private record TestIdealLinkElement(
            ResourceLocation id,
            CircuitPort firstPort,
            CircuitPort secondPort
    ) implements CircuitTopologyElement {
        @Override
        public List<CircuitPort> ports() {
            return List.of(firstPort, secondPort);
        }

        @Override
        public void buildTopology(CircuitTopologyBuilder builder) {
            builder.connectIdeal(firstPort, secondPort);
        }
    }

    private record TestVoltageControlledCurrentSourceElement(
            ResourceLocation id,
            CircuitPort positivePort,
            CircuitPort negativePort,
            CircuitPort controlPositivePort,
            CircuitPort controlNegativePort,
            double transconductanceSiemens
    ) implements LinearCircuitElement {
        @Override
        public List<CircuitPort> ports() {
            return List.of(positivePort, negativePort, controlPositivePort, controlNegativePort);
        }

        @Override
        public void stamp(CircuitEquationBuilder builder) {
            builder.addVoltageControlledCurrentSource(
                    positivePort,
                    negativePort,
                    controlPositivePort,
                    controlNegativePort,
                    transconductanceSiemens
            );
        }
    }

    private record TestVoltageControlledVoltageSourceElement(
            ResourceLocation id,
            CircuitPort positivePort,
            CircuitPort negativePort,
            CircuitPort controlPositivePort,
            CircuitPort controlNegativePort,
            double gain
    ) implements LinearCircuitElement {
        @Override
        public List<CircuitPort> ports() {
            return List.of(positivePort, negativePort, controlPositivePort, controlNegativePort);
        }

        @Override
        public void stamp(CircuitEquationBuilder builder) {
            builder.addVoltageControlledVoltageSource(
                    positivePort,
                    negativePort,
                    controlPositivePort,
                    controlNegativePort,
                    gain
            );
        }
    }

    private record TestProbeControlledCurrentSourceElement(
            ResourceLocation id,
            CircuitPort probePositivePort,
            CircuitPort probeNegativePort,
            CircuitPort outputPositivePort,
            CircuitPort outputNegativePort,
            double gain
    ) implements LinearCircuitElement {
        @Override
        public List<CircuitPort> ports() {
            return List.of(probePositivePort, probeNegativePort, outputPositivePort, outputNegativePort);
        }

        @Override
        public void stamp(CircuitEquationBuilder builder) {
            CircuitBranchCurrent controlCurrent = builder.addCurrentProbe(probePositivePort, probeNegativePort);
            builder.addCurrentControlledCurrentSource(
                    outputPositivePort,
                    outputNegativePort,
                    controlCurrent,
                    gain
            );
        }
    }

    private record TestProbeControlledVoltageSourceElement(
            ResourceLocation id,
            CircuitPort probePositivePort,
            CircuitPort probeNegativePort,
            CircuitPort outputPositivePort,
            CircuitPort outputNegativePort,
            double transresistanceOhms
    ) implements LinearCircuitElement {
        @Override
        public List<CircuitPort> ports() {
            return List.of(probePositivePort, probeNegativePort, outputPositivePort, outputNegativePort);
        }

        @Override
        public void stamp(CircuitEquationBuilder builder) {
            CircuitBranchCurrent controlCurrent = builder.addCurrentProbe(probePositivePort, probeNegativePort);
            builder.addCurrentControlledVoltageSource(
                    outputPositivePort,
                    outputNegativePort,
                    controlCurrent,
                    transresistanceOhms
            );
        }
    }

    private record TestAcVoltageSourceElement(
            ResourceLocation id,
            CircuitPort positivePort,
            CircuitPort negativePort,
            CircuitPhasor voltageVolts
    ) implements AcLinearCircuitElement {
        @Override
        public List<CircuitPort> ports() {
            return List.of(positivePort, negativePort);
        }

        @Override
        public void stamp(AcCircuitEquationBuilder builder) {
            builder.addVoltageSource(positivePort, negativePort, voltageVolts);
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

    private record TestAcCurrentSourceElement(
            ResourceLocation id,
            CircuitPort positivePort,
            CircuitPort negativePort,
            CircuitPhasor currentAmps
    ) implements AcLinearCircuitElement {
        @Override
        public List<CircuitPort> ports() {
            return List.of(positivePort, negativePort);
        }

        @Override
        public void stamp(AcCircuitEquationBuilder builder) {
            builder.addCurrentSource(positivePort, negativePort, currentAmps);
        }
    }

    private record TestAcCapacitorElement(
            ResourceLocation id,
            CircuitPort positivePort,
            CircuitPort negativePort,
            double capacitanceFarads
    ) implements AcLinearCircuitElement {
        @Override
        public List<CircuitPort> ports() {
            return List.of(positivePort, negativePort);
        }

        @Override
        public void stamp(AcCircuitEquationBuilder builder) {
            double susceptanceSiemens = builder.angularFrequencyRadiansPerSecond() * capacitanceFarads;
            builder.addAdmittance(positivePort, negativePort, CircuitPhasor.of(0.0, susceptanceSiemens));
        }
    }

    private record TestAcVoltageControlledCurrentSourceElement(
            ResourceLocation id,
            CircuitPort positivePort,
            CircuitPort negativePort,
            CircuitPort controlPositivePort,
            CircuitPort controlNegativePort,
            CircuitPhasor transconductanceSiemens
    ) implements AcLinearCircuitElement {
        @Override
        public List<CircuitPort> ports() {
            return List.of(positivePort, negativePort, controlPositivePort, controlNegativePort);
        }

        @Override
        public void stamp(AcCircuitEquationBuilder builder) {
            builder.addVoltageControlledCurrentSource(
                    positivePort,
                    negativePort,
                    controlPositivePort,
                    controlNegativePort,
                    transconductanceSiemens
            );
        }
    }

    private record TestAcVoltageControlledVoltageSourceElement(
            ResourceLocation id,
            CircuitPort positivePort,
            CircuitPort negativePort,
            CircuitPort controlPositivePort,
            CircuitPort controlNegativePort,
            CircuitPhasor gain
    ) implements AcLinearCircuitElement {
        @Override
        public List<CircuitPort> ports() {
            return List.of(positivePort, negativePort, controlPositivePort, controlNegativePort);
        }

        @Override
        public void stamp(AcCircuitEquationBuilder builder) {
            builder.addVoltageControlledVoltageSource(
                    positivePort,
                    negativePort,
                    controlPositivePort,
                    controlNegativePort,
                    gain
            );
        }
    }

    private record TestAcProbeControlledCurrentSourceElement(
            ResourceLocation id,
            CircuitPort probePositivePort,
            CircuitPort probeNegativePort,
            CircuitPort outputPositivePort,
            CircuitPort outputNegativePort,
            CircuitPhasor gain
    ) implements AcLinearCircuitElement {
        @Override
        public List<CircuitPort> ports() {
            return List.of(probePositivePort, probeNegativePort, outputPositivePort, outputNegativePort);
        }

        @Override
        public void stamp(AcCircuitEquationBuilder builder) {
            CircuitBranchCurrent controlCurrent = builder.addCurrentProbe(probePositivePort, probeNegativePort);
            builder.addCurrentControlledCurrentSource(
                    outputPositivePort,
                    outputNegativePort,
                    controlCurrent,
                    gain
            );
        }
    }

    private record TestAcProbeControlledVoltageSourceElement(
            ResourceLocation id,
            CircuitPort probePositivePort,
            CircuitPort probeNegativePort,
            CircuitPort outputPositivePort,
            CircuitPort outputNegativePort,
            CircuitPhasor transimpedanceOhms
    ) implements AcLinearCircuitElement {
        @Override
        public List<CircuitPort> ports() {
            return List.of(probePositivePort, probeNegativePort, outputPositivePort, outputNegativePort);
        }

        @Override
        public void stamp(AcCircuitEquationBuilder builder) {
            CircuitBranchCurrent controlCurrent = builder.addCurrentProbe(probePositivePort, probeNegativePort);
            builder.addCurrentControlledVoltageSource(
                    outputPositivePort,
                    outputNegativePort,
                    controlCurrent,
                    transimpedanceOhms
            );
        }
    }
}
