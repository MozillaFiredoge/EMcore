package com.firedoge.emcore.internal.circuit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.firedoge.emcore.EMcore;
import com.firedoge.emcore.api.circuit.AcCircuitEquationBuilder;
import com.firedoge.emcore.api.circuit.AcCircuitSample;
import com.firedoge.emcore.api.circuit.AcCircuitSnapshot;
import com.firedoge.emcore.api.circuit.AcLinearCircuitElement;
import com.firedoge.emcore.api.circuit.CircuitBranchCurrent;
import com.firedoge.emcore.api.circuit.CircuitDiagnostic;
import com.firedoge.emcore.api.circuit.CircuitDiagnosticSeverity;
import com.firedoge.emcore.api.circuit.CircuitDiagnosticType;
import com.firedoge.emcore.api.circuit.CircuitElement;
import com.firedoge.emcore.api.circuit.CircuitEquationBuilder;
import com.firedoge.emcore.api.circuit.CircuitNode;
import com.firedoge.emcore.api.circuit.CircuitPhasor;
import com.firedoge.emcore.api.circuit.CircuitPort;
import com.firedoge.emcore.api.circuit.CircuitSample;
import com.firedoge.emcore.api.circuit.CircuitSnapshot;
import com.firedoge.emcore.api.circuit.CircuitTerminal;
import com.firedoge.emcore.api.circuit.CircuitTopologyBuilder;
import com.firedoge.emcore.api.circuit.CircuitTopologyElement;
import com.firedoge.emcore.api.circuit.LinearCircuitElement;
import com.firedoge.emcore.api.circuit.NonlinearCircuitElement;
import com.firedoge.emcore.api.circuit.NonlinearCircuitEquationBuilder;
import com.firedoge.emcore.api.circuit.TransientCircuitElement;
import com.firedoge.emcore.api.circuit.TransientCircuitEquationBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

public final class CircuitNetwork {
    private final Map<CircuitPort, CircuitPort> ports = new LinkedHashMap<>();
    private final Map<CircuitPort, Integer> portReferences = new HashMap<>();
    private final Map<CircuitTerminal, Integer> terminalReferences = new HashMap<>();
    private final Map<BlockPos, List<CircuitTerminal>> terminalsByNode = new LinkedHashMap<>();
    private final Map<ResourceLocation, CircuitElement> elements = new LinkedHashMap<>();
    private final Map<CircuitPort, CircuitSample> samples = new LinkedHashMap<>();
    private final Map<ResourceLocation, Double> transientVoltages = new HashMap<>();
    private final Map<ResourceLocation, Double> transientCurrents = new HashMap<>();
    private final Map<CircuitPort, Double> transientPortVoltages = new HashMap<>();
    private List<CircuitNode> nodes = List.of();
    private List<CircuitDiagnostic> diagnostics = List.of();
    private boolean dirty = true;

    public void registerPort(CircuitPort port) {
        Objects.requireNonNull(port, "port");
        addPortReference(port);
        dirty = true;
    }

    public void unregisterPort(CircuitPort port) {
        Objects.requireNonNull(port, "port");
        removePortReference(port);
        dirty = true;
    }

    public void registerTerminal(CircuitTerminal terminal) {
        Objects.requireNonNull(terminal, "terminal");
        addTerminalReference(terminal);
        dirty = true;
    }

    public void unregisterTerminal(CircuitTerminal terminal) {
        Objects.requireNonNull(terminal, "terminal");
        removeTerminalReference(terminal);
        dirty = true;
    }

    public void registerElement(CircuitElement element) {
        Objects.requireNonNull(element, "element");
        CircuitElement previous = elements.put(element.id(), element);
        if (previous != null) {
            previous.ports().forEach(this::removePortReference);
        }
        element.ports().forEach(this::addPortReference);
        dirty = true;
    }

    public void unregisterElement(ResourceLocation elementId) {
        Objects.requireNonNull(elementId, "elementId");
        CircuitElement previous = elements.remove(elementId);
        if (previous != null) {
            previous.ports().forEach(this::removePortReference);
        }
        dirty = true;
    }

    public void tick() {
        solveIfDirty();
    }

    public CircuitSnapshot snapshot(double simulatedTimeSeconds) {
        solveIfDirty();
        return new CircuitSnapshot(
                nodes,
                List.copyOf(ports.keySet()),
                terminals(),
                List.copyOf(elements.values()),
                diagnostics,
                List.copyOf(samples.values()),
                simulatedTimeSeconds
        );
    }

    public AcCircuitSnapshot acSnapshot(double frequencyHertz, double simulatedTimeSeconds) {
        solveIfDirty();
        AcSolveTopology topology = buildAcTopology(frequencyHertz);
        AcSolveResult result = AcMnaSolver.solve(topology, buildNodes(topology));
        return new AcCircuitSnapshot(
                result.nodes(),
                List.copyOf(ports.keySet()),
                terminals(),
                List.copyOf(elements.values()),
                result.diagnostics(),
                result.samples(),
                frequencyHertz,
                simulatedTimeSeconds
        );
    }

    public Optional<CircuitSample> samplePort(CircuitPort port) {
        Objects.requireNonNull(port, "port");
        solveIfDirty();
        return Optional.ofNullable(samples.get(port));
    }

    public Optional<AcCircuitSample> sampleAcPort(
            CircuitPort port,
            double frequencyHertz,
            double simulatedTimeSeconds
    ) {
        Objects.requireNonNull(port, "port");
        return acSnapshot(frequencyHertz, simulatedTimeSeconds).samples().stream()
                .filter(sample -> sample.port().equals(port))
                .findFirst();
    }

    public CircuitSnapshot stepTransient(double timeStepSeconds, double simulatedTimeSeconds) {
        if (!Double.isFinite(timeStepSeconds) || timeStepSeconds <= 0.0) {
            throw new IllegalArgumentException("timeStepSeconds must be finite and greater than zero");
        }

        SolveTopology topology = buildTransientTopology(timeStepSeconds, simulatedTimeSeconds);
        SolveResult result = MnaSolver.solve(topology, buildNodes(topology));
        Map<CircuitPort, CircuitSample> transientSamples = withTransientStoredEnergy(
                result.samples(),
                topology.transientCapacitances(),
                topology.transientInductances()
        );

        if (!hasError(result.diagnostics())) {
            updateTransientVoltages(topology.transientCapacitances(), transientSamples);
            updateTransientCurrents(topology.transientInductances(), transientSamples);
            updateTransientPortVoltages(transientSamples);
        }

        return new CircuitSnapshot(
                result.nodes(),
                List.copyOf(ports.keySet()),
                terminals(),
                List.copyOf(elements.values()),
                result.diagnostics(),
                List.copyOf(transientSamples.values()),
                simulatedTimeSeconds
        );
    }

    PreparedTransientPlan prepareTransient(double timeStepSeconds, double startTimeSeconds) {
        if (!Double.isFinite(timeStepSeconds) || timeStepSeconds <= 0.0) {
            throw new IllegalArgumentException("timeStepSeconds must be finite and greater than zero");
        }
        if (!Double.isFinite(startTimeSeconds)) {
            throw new IllegalArgumentException("startTimeSeconds must be finite");
        }

        return new PreparedTransientPlan(timeStepSeconds, startTimeSeconds, buildPreparedTransientTopology());
    }

    private void solveIfDirty() {
        if (!dirty) {
            return;
        }

        SolveTopology topology = buildTopology();
        SolveResult result = MnaSolver.solve(topology, buildNodes(topology));
        nodes = result.nodes();
        diagnostics = result.diagnostics();
        samples.clear();
        samples.putAll(result.samples());
        dirty = false;
    }

    private SolveTopology buildTopology() {
        List<CircuitPort> portList = new ArrayList<>(ports.keySet());
        Map<CircuitPort, Integer> portIndexes = new HashMap<>();
        for (int index = 0; index < portList.size(); index++) {
            portIndexes.put(portList.get(index), index);
        }

        DisjointSet unionFind = new DisjointSet(portList.size());
        TopologyRecorder topologyRecorder = new TopologyRecorder(portIndexes, unionFind);
        EquationRecorder equationRecorder = new EquationRecorder(portIndexes);

        for (CircuitElement element : elements.values()) {
            if (element instanceof CircuitTopologyElement topologyElement) {
                topologyRecorder.record(element, topologyElement);
            }
        }
        for (CircuitElement element : elements.values()) {
            if (element instanceof LinearCircuitElement linearElement) {
                equationRecorder.record(element, linearElement);
            }
        }
        for (List<CircuitTerminal> terminals : terminalsByNode.values()) {
            unionTerminals(unionFind, portIndexes, terminals);
        }

        Map<Integer, Integer> nodeByRoot = new LinkedHashMap<>();
        Integer preferredGroundRoot = preferredGroundRoot(portIndexes, unionFind, equationRecorder.voltageSources());
        if (preferredGroundRoot != null) {
            nodeByRoot.put(preferredGroundRoot, 0);
        }

        int[] nodeByPort = new int[portList.size()];
        for (int index = 0; index < portList.size(); index++) {
            int root = unionFind.find(index);
            Integer nodeIndex = nodeByRoot.get(root);
            if (nodeIndex == null) {
                nodeIndex = nodeByRoot.size();
                nodeByRoot.put(root, nodeIndex);
            }
            nodeByPort[index] = nodeIndex;
        }

        return new SolveTopology(
                portList,
                portIndexes,
                nodeByPort,
                nodeByRoot.size(),
                new double[nodeByRoot.size()],
                equationRecorder.conductances(),
                equationRecorder.currentSources(),
                equationRecorder.voltageSources(),
                equationRecorder.voltageControlledCurrentSources(),
                equationRecorder.voltageControlledVoltageSources(),
                equationRecorder.currentControlledCurrentSources(),
                equationRecorder.currentControlledVoltageSources(),
                nonlinearElements(),
                List.of(),
                List.of(),
                combineDiagnostics(topologyRecorder.diagnostics(), equationRecorder.diagnostics())
        );
    }

    private PreparedTransientTopology buildPreparedTransientTopology() {
        List<CircuitPort> portList = new ArrayList<>(ports.keySet());
        Map<CircuitPort, Integer> portIndexes = new HashMap<>();
        for (int index = 0; index < portList.size(); index++) {
            portIndexes.put(portList.get(index), index);
        }

        DisjointSet unionFind = new DisjointSet(portList.size());
        TopologyRecorder topologyRecorder = new TopologyRecorder(portIndexes, unionFind);
        EquationRecorder equationRecorder = new EquationRecorder(portIndexes);
        List<TransientElementStamp> transientElements = new ArrayList<>();

        for (CircuitElement element : elements.values()) {
            if (element instanceof CircuitTopologyElement topologyElement) {
                topologyRecorder.record(element, topologyElement);
            }
        }
        for (CircuitElement element : elements.values()) {
            if (element instanceof LinearCircuitElement linearElement) {
                equationRecorder.record(element, linearElement);
            }
        }
        for (CircuitElement element : elements.values()) {
            if (element instanceof TransientCircuitElement transientElement) {
                transientElements.add(new TransientElementStamp(element, transientElement));
            }
        }
        for (List<CircuitTerminal> terminals : terminalsByNode.values()) {
            unionTerminals(unionFind, portIndexes, terminals);
        }

        Map<Integer, Integer> nodeByRoot = new LinkedHashMap<>();
        Integer preferredGroundRoot = preferredGroundRoot(portIndexes, unionFind, equationRecorder.voltageSources());
        if (preferredGroundRoot != null) {
            nodeByRoot.put(preferredGroundRoot, 0);
        }

        int[] nodeByPort = new int[portList.size()];
        for (int index = 0; index < portList.size(); index++) {
            int root = unionFind.find(index);
            Integer nodeIndex = nodeByRoot.get(root);
            if (nodeIndex == null) {
                nodeIndex = nodeByRoot.size();
                nodeByRoot.put(root, nodeIndex);
            }
            nodeByPort[index] = nodeIndex;
        }

        int nodeCount = nodeByRoot.size();
        return new PreparedTransientTopology(
                portList,
                portIndexes,
                nodeByPort,
                nodeCount,
                buildNodes(portList, nodeByPort, nodeCount, "circuit_node"),
                equationRecorder.conductances(),
                equationRecorder.currentSources(),
                equationRecorder.voltageSources(),
                equationRecorder.voltageControlledCurrentSources(),
                equationRecorder.voltageControlledVoltageSources(),
                equationRecorder.currentControlledCurrentSources(),
                equationRecorder.currentControlledVoltageSources(),
                nonlinearElements(),
                List.copyOf(transientElements),
                combineDiagnostics(topologyRecorder.diagnostics(), equationRecorder.diagnostics()),
                terminals(),
                List.copyOf(elements.values())
        );
    }

    private SolveTopology buildTransientTopology(double timeStepSeconds, double simulatedTimeSeconds) {
        List<CircuitPort> portList = new ArrayList<>(ports.keySet());
        Map<CircuitPort, Integer> portIndexes = new HashMap<>();
        for (int index = 0; index < portList.size(); index++) {
            portIndexes.put(portList.get(index), index);
        }

        DisjointSet unionFind = new DisjointSet(portList.size());
        TopologyRecorder topologyRecorder = new TopologyRecorder(portIndexes, unionFind);
        EquationRecorder equationRecorder = new EquationRecorder(portIndexes);
        TransientEquationRecorder transientRecorder = new TransientEquationRecorder(
                portIndexes,
                timeStepSeconds,
                simulatedTimeSeconds,
                transientVoltages,
                transientCurrents
        );

        for (CircuitElement element : elements.values()) {
            if (element instanceof CircuitTopologyElement topologyElement) {
                topologyRecorder.record(element, topologyElement);
            }
        }
        for (CircuitElement element : elements.values()) {
            if (element instanceof LinearCircuitElement linearElement) {
                equationRecorder.record(element, linearElement);
            }
        }
        for (CircuitElement element : elements.values()) {
            if (element instanceof TransientCircuitElement transientElement) {
                transientRecorder.record(element, transientElement);
            }
        }
        for (List<CircuitTerminal> terminals : terminalsByNode.values()) {
            unionTerminals(unionFind, portIndexes, terminals);
        }

        List<VoltageSourceStamp> voltageSources = combineVoltageSources(
                equationRecorder.voltageSources(),
                transientRecorder.voltageSources()
        );

        Map<Integer, Integer> nodeByRoot = new LinkedHashMap<>();
        Integer preferredGroundRoot = preferredGroundRoot(portIndexes, unionFind, voltageSources);
        if (preferredGroundRoot != null) {
            nodeByRoot.put(preferredGroundRoot, 0);
        }

        int[] nodeByPort = new int[portList.size()];
        for (int index = 0; index < portList.size(); index++) {
            int root = unionFind.find(index);
            Integer nodeIndex = nodeByRoot.get(root);
            if (nodeIndex == null) {
                nodeIndex = nodeByRoot.size();
                nodeByRoot.put(root, nodeIndex);
            }
            nodeByPort[index] = nodeIndex;
        }

        return new SolveTopology(
                portList,
                portIndexes,
                nodeByPort,
                nodeByRoot.size(),
                initialNodeVoltages(portList, nodeByPort, nodeByRoot.size(), transientPortVoltages),
                combineConductances(equationRecorder.conductances(), transientRecorder.conductances()),
                combineCurrentSources(equationRecorder.currentSources(), transientRecorder.currentSources()),
                voltageSources,
                equationRecorder.voltageControlledCurrentSources(),
                equationRecorder.voltageControlledVoltageSources(),
                equationRecorder.currentControlledCurrentSources(),
                equationRecorder.currentControlledVoltageSources(),
                nonlinearElements(),
                transientRecorder.capacitances(),
                transientRecorder.inductances(),
                combineDiagnostics(
                        topologyRecorder.diagnostics(),
                        equationRecorder.diagnostics(),
                        transientRecorder.diagnostics()
                )
        );
    }

    private AcSolveTopology buildAcTopology(double frequencyHertz) {
        if (!Double.isFinite(frequencyHertz) || frequencyHertz < 0.0) {
            throw new IllegalArgumentException("frequencyHertz must be finite and non-negative");
        }

        List<CircuitPort> portList = new ArrayList<>(ports.keySet());
        Map<CircuitPort, Integer> portIndexes = new HashMap<>();
        for (int index = 0; index < portList.size(); index++) {
            portIndexes.put(portList.get(index), index);
        }

        DisjointSet unionFind = new DisjointSet(portList.size());
        TopologyRecorder topologyRecorder = new TopologyRecorder(portIndexes, unionFind);
        AcEquationRecorder equationRecorder = new AcEquationRecorder(portIndexes, frequencyHertz);
        SmallSignalNonlinearRecorder nonlinearRecorder = new SmallSignalNonlinearRecorder(portIndexes, samples);

        for (CircuitElement element : elements.values()) {
            if (element instanceof CircuitTopologyElement topologyElement) {
                topologyRecorder.record(element, topologyElement);
            }
        }
        for (CircuitElement element : elements.values()) {
            if (element instanceof AcLinearCircuitElement linearElement) {
                equationRecorder.record(element, linearElement);
            }
        }
        if (!hasDcOperatingPointError()) {
            for (NonlinearElementStamp nonlinearElement : nonlinearElements()) {
                nonlinearRecorder.record(nonlinearElement);
            }
        }
        for (List<CircuitTerminal> terminals : terminalsByNode.values()) {
            unionTerminals(unionFind, portIndexes, terminals);
        }

        Map<Integer, Integer> nodeByRoot = new LinkedHashMap<>();
        Integer preferredGroundRoot = preferredAcGroundRoot(portIndexes, unionFind, equationRecorder.voltageSources());
        if (preferredGroundRoot != null) {
            nodeByRoot.put(preferredGroundRoot, 0);
        }

        int[] nodeByPort = new int[portList.size()];
        for (int index = 0; index < portList.size(); index++) {
            int root = unionFind.find(index);
            Integer nodeIndex = nodeByRoot.get(root);
            if (nodeIndex == null) {
                nodeIndex = nodeByRoot.size();
                nodeByRoot.put(root, nodeIndex);
            }
            nodeByPort[index] = nodeIndex;
        }

        return new AcSolveTopology(
                portList,
                portIndexes,
                nodeByPort,
                nodeByRoot.size(),
                frequencyHertz,
                combineAdmittances(equationRecorder.admittances(), nonlinearRecorder.admittances()),
                equationRecorder.currentSources(),
                equationRecorder.voltageSources(),
                equationRecorder.voltageControlledCurrentSources(),
                equationRecorder.voltageControlledVoltageSources(),
                equationRecorder.currentControlledCurrentSources(),
                equationRecorder.currentControlledVoltageSources(),
                combineDiagnostics(
                        diagnostics,
                        topologyRecorder.diagnostics(),
                        equationRecorder.diagnostics(),
                        nonlinearRecorder.diagnostics()
                )
        );
    }

    @SafeVarargs
    private static List<CircuitDiagnostic> combineDiagnostics(List<CircuitDiagnostic>... diagnosticLists) {
        int totalSize = 0;
        for (List<CircuitDiagnostic> diagnostics : diagnosticLists) {
            totalSize += diagnostics.size();
        }
        if (totalSize == 0) {
            return List.of();
        }

        List<CircuitDiagnostic> combined = new ArrayList<>(totalSize);
        for (List<CircuitDiagnostic> diagnostics : diagnosticLists) {
            combined.addAll(diagnostics);
        }
        return List.copyOf(combined);
    }

    private static List<AcAdmittanceStamp> combineAdmittances(
            List<AcAdmittanceStamp> first,
            List<AcAdmittanceStamp> second
    ) {
        if (first.isEmpty()) {
            return second;
        }
        if (second.isEmpty()) {
            return first;
        }

        List<AcAdmittanceStamp> combined = new ArrayList<>(first.size() + second.size());
        combined.addAll(first);
        combined.addAll(second);
        return List.copyOf(combined);
    }

    private static List<ConductanceStamp> combineConductances(
            List<ConductanceStamp> first,
            List<ConductanceStamp> second
    ) {
        if (first.isEmpty()) {
            return second;
        }
        if (second.isEmpty()) {
            return first;
        }

        List<ConductanceStamp> combined = new ArrayList<>(first.size() + second.size());
        combined.addAll(first);
        combined.addAll(second);
        return List.copyOf(combined);
    }

    private static List<CurrentSourceStamp> combineCurrentSources(
            List<CurrentSourceStamp> first,
            List<CurrentSourceStamp> second
    ) {
        if (first.isEmpty()) {
            return second;
        }
        if (second.isEmpty()) {
            return first;
        }

        List<CurrentSourceStamp> combined = new ArrayList<>(first.size() + second.size());
        combined.addAll(first);
        combined.addAll(second);
        return List.copyOf(combined);
    }

    private static List<VoltageSourceStamp> combineVoltageSources(
            List<VoltageSourceStamp> first,
            List<VoltageSourceStamp> second
    ) {
        if (first.isEmpty()) {
            return second;
        }
        if (second.isEmpty()) {
            return first;
        }

        List<VoltageSourceStamp> combined = new ArrayList<>(first.size() + second.size());
        combined.addAll(first);
        combined.addAll(second);
        return List.copyOf(combined);
    }

    private boolean hasDcOperatingPointError() {
        return diagnostics.stream().anyMatch(diagnostic -> diagnostic.severity() == CircuitDiagnosticSeverity.ERROR);
    }

    private static boolean hasError(List<CircuitDiagnostic> diagnostics) {
        return diagnostics.stream().anyMatch(diagnostic -> diagnostic.severity() == CircuitDiagnosticSeverity.ERROR);
    }

    private Map<CircuitPort, CircuitSample> withTransientStoredEnergy(
            Map<CircuitPort, CircuitSample> solvedSamples,
            List<TransientCapacitanceStamp> capacitances,
            List<TransientInductanceStamp> inductances
    ) {
        if (capacitances.isEmpty() && inductances.isEmpty()) {
            return solvedSamples;
        }

        Map<CircuitPort, CircuitSample> updatedSamples = new LinkedHashMap<>(solvedSamples);
        for (TransientCapacitanceStamp capacitance : capacitances) {
            CircuitSample positiveSample = updatedSamples.get(capacitance.positivePort());
            CircuitSample negativeSample = updatedSamples.get(capacitance.negativePort());
            if (positiveSample == null || negativeSample == null) {
                continue;
            }

            double voltage = positiveSample.voltageVolts() - negativeSample.voltageVolts();
            double storedEnergy = 0.5 * capacitance.capacitanceFarads() * voltage * voltage;
            addStoredEnergy(updatedSamples, capacitance.positivePort(), storedEnergy);
            addStoredEnergy(updatedSamples, capacitance.negativePort(), storedEnergy);
        }
        for (TransientInductanceStamp inductance : inductances) {
            CircuitSample positiveSample = updatedSamples.get(inductance.positivePort());
            CircuitSample negativeSample = updatedSamples.get(inductance.negativePort());
            if (positiveSample == null || negativeSample == null) {
                continue;
            }

            double current = transientInductorCurrent(inductance, positiveSample, negativeSample);
            double storedEnergy = 0.5 * inductance.inductanceHenries() * current * current;
            addStoredEnergy(updatedSamples, inductance.positivePort(), storedEnergy);
            addStoredEnergy(updatedSamples, inductance.negativePort(), storedEnergy);
        }
        return updatedSamples;
    }

    private void updateTransientVoltages(
            List<TransientCapacitanceStamp> capacitances,
            Map<CircuitPort, CircuitSample> transientSamples
    ) {
        for (TransientCapacitanceStamp capacitance : capacitances) {
            CircuitSample positiveSample = transientSamples.get(capacitance.positivePort());
            CircuitSample negativeSample = transientSamples.get(capacitance.negativePort());
            if (positiveSample != null && negativeSample != null) {
                transientVoltages.put(
                        capacitance.stateId(),
                        positiveSample.voltageVolts() - negativeSample.voltageVolts()
                );
            }
        }
    }

    private void updateTransientCurrents(
            List<TransientInductanceStamp> inductances,
            Map<CircuitPort, CircuitSample> transientSamples
    ) {
        for (TransientInductanceStamp inductance : inductances) {
            CircuitSample positiveSample = transientSamples.get(inductance.positivePort());
            CircuitSample negativeSample = transientSamples.get(inductance.negativePort());
            if (positiveSample != null && negativeSample != null) {
                transientCurrents.put(
                        inductance.stateId(),
                        transientInductorCurrent(inductance, positiveSample, negativeSample)
                );
            }
        }
    }

    private void updateTransientPortVoltages(Map<CircuitPort, CircuitSample> transientSamples) {
        transientPortVoltages.clear();
        for (Map.Entry<CircuitPort, CircuitSample> entry : transientSamples.entrySet()) {
            transientPortVoltages.put(entry.getKey(), entry.getValue().voltageVolts());
        }
    }

    private static double transientInductorCurrent(
            TransientInductanceStamp inductance,
            CircuitSample positiveSample,
            CircuitSample negativeSample
    ) {
        double voltage = positiveSample.voltageVolts() - negativeSample.voltageVolts();
        return inductance.conductanceSiemens() * voltage + inductance.previousCurrentAmps();
    }

    private static void addStoredEnergy(
            Map<CircuitPort, CircuitSample> samples,
            CircuitPort port,
            double storedEnergyJoules
    ) {
        CircuitSample sample = samples.get(port);
        if (sample == null) {
            return;
        }

        samples.put(port, new CircuitSample(
                sample.port(),
                sample.voltageVolts(),
                sample.currentAmps(),
                sample.powerWatts(),
                sample.storedEnergyJoules() + storedEnergyJoules,
                sample.overloaded()
        ));
    }

    private static double[] initialNodeVoltages(
            List<CircuitPort> portList,
            int[] nodeByPort,
            int nodeCount,
            Map<CircuitPort, Double> portVoltages
    ) {
        if (portVoltages.isEmpty()) {
            return new double[nodeCount];
        }

        double[] voltages = new double[nodeCount];
        boolean[] assigned = new boolean[nodeCount];
        for (int portIndex = 0; portIndex < portList.size(); portIndex++) {
            int node = nodeByPort[portIndex];
            if (assigned[node]) {
                continue;
            }

            Double voltage = portVoltages.get(portList.get(portIndex));
            if (voltage != null && Double.isFinite(voltage)) {
                voltages[node] = voltage;
                assigned[node] = true;
            }
        }
        return voltages;
    }

    private Integer preferredGroundRoot(
            Map<CircuitPort, Integer> portIndexes,
            DisjointSet unionFind,
            List<VoltageSourceStamp> voltageSources
    ) {
        if (voltageSources.isEmpty()) {
            return null;
        }

        Integer negativePortIndex = portIndexes.get(voltageSources.getFirst().negativePort());
        return negativePortIndex == null ? null : unionFind.find(negativePortIndex);
    }

    private Integer preferredAcGroundRoot(
            Map<CircuitPort, Integer> portIndexes,
            DisjointSet unionFind,
            List<AcVoltageSourceStamp> voltageSources
    ) {
        if (voltageSources.isEmpty()) {
            return null;
        }

        Integer negativePortIndex = portIndexes.get(voltageSources.getFirst().negativePort());
        return negativePortIndex == null ? null : unionFind.find(negativePortIndex);
    }

    private List<CircuitNode> buildNodes(SolveTopology topology) {
        return buildNodes(topology.ports(), topology.nodeByPort(), topology.nodeCount(), "circuit_node");
    }

    private List<CircuitNode> buildNodes(AcSolveTopology topology) {
        return buildNodes(topology.ports(), topology.nodeByPort(), topology.nodeCount(), "ac_circuit_node");
    }

    private static List<CircuitNode> buildNodes(
            List<CircuitPort> portList,
            int[] nodeByPort,
            int nodeCount,
            String pathPrefix
    ) {
        List<CircuitNode> solvedNodes = new ArrayList<>();
        for (int node = 0; node < nodeCount; node++) {
            CircuitPort representative = representativePort(portList, nodeByPort, node);
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(EMcore.MODID, pathPrefix + "/" + node);
            solvedNodes.add(new CircuitNode(id, centerOf(representative)));
        }
        return List.copyOf(solvedNodes);
    }

    private static CircuitPort representativePort(List<CircuitPort> portList, int[] nodeByPort, int node) {
        for (int portIndex = 0; portIndex < nodeByPort.length; portIndex++) {
            if (nodeByPort[portIndex] == node) {
                return portList.get(portIndex);
            }
        }
        throw new IllegalArgumentException("Unknown circuit node: " + node);
    }

    private static void union(
            DisjointSet unionFind,
            Map<CircuitPort, Integer> portIndexes,
            CircuitPort firstPort,
            CircuitPort secondPort
    ) {
        Integer firstIndex = portIndexes.get(firstPort);
        Integer secondIndex = portIndexes.get(secondPort);

        if (firstIndex != null && secondIndex != null) {
            unionFind.union(firstIndex, secondIndex);
        }
    }

    private static void unionTerminals(
            DisjointSet unionFind,
            Map<CircuitPort, Integer> portIndexes,
            List<CircuitTerminal> terminals
    ) {
        if (terminals.size() < 2) {
            return;
        }

        CircuitPort firstPort = terminals.getFirst().port();
        for (int index = 1; index < terminals.size(); index++) {
            union(unionFind, portIndexes, firstPort, terminals.get(index).port());
        }
    }

    private static CircuitSample zeroSample(CircuitPort port) {
        return new CircuitSample(port, 0.0, 0.0, 0.0, 0.0, false);
    }

    private void addPortReference(CircuitPort port) {
        ports.put(port, port);
        samples.putIfAbsent(port, zeroSample(port));
        portReferences.merge(port, 1, Integer::sum);
    }

    private void removePortReference(CircuitPort port) {
        Integer references = portReferences.get(port);
        if (references == null) {
            return;
        }

        if (references <= 1) {
            portReferences.remove(port);
            ports.remove(port);
            samples.remove(port);
        } else {
            portReferences.put(port, references - 1);
        }
    }

    private void addTerminalReference(CircuitTerminal terminal) {
        addPortReference(terminal.port());

        int references = terminalReferences.merge(terminal, 1, Integer::sum);
        if (references == 1) {
            terminalsByNode.computeIfAbsent(terminal.nodePosition(), ignored -> new ArrayList<>()).add(terminal);
        }
    }

    private void removeTerminalReference(CircuitTerminal terminal) {
        Integer references = terminalReferences.get(terminal);
        if (references == null) {
            return;
        }

        if (references <= 1) {
            terminalReferences.remove(terminal);
            List<CircuitTerminal> terminals = terminalsByNode.get(terminal.nodePosition());
            if (terminals != null) {
                terminals.remove(terminal);
                if (terminals.isEmpty()) {
                    terminalsByNode.remove(terminal.nodePosition());
                }
            }
        } else {
            terminalReferences.put(terminal, references - 1);
        }

        removePortReference(terminal.port());
    }

    private List<CircuitTerminal> terminals() {
        List<CircuitTerminal> terminals = new ArrayList<>();
        for (List<CircuitTerminal> nodeTerminals : terminalsByNode.values()) {
            terminals.addAll(nodeTerminals);
        }
        return List.copyOf(terminals);
    }

    private static Vec3 centerOf(CircuitPort port) {
        return new Vec3(
                port.position().getX() + 0.5,
                port.position().getY() + 0.5,
                port.position().getZ() + 0.5
        );
    }

    private List<NonlinearElementStamp> nonlinearElements() {
        List<NonlinearElementStamp> nonlinearElements = new ArrayList<>();
        for (CircuitElement element : elements.values()) {
            if (element instanceof NonlinearCircuitElement nonlinearElement) {
                nonlinearElements.add(new NonlinearElementStamp(
                        element.id(),
                        element.ports(),
                        nonlinearElement
                ));
            }
        }
        return List.copyOf(nonlinearElements);
    }

    final class PreparedTransientPlan {
        private final double timeStepSeconds;
        private final PreparedTransientTopology baseTopology;
        private final MnaSolver.PreparedMnaPlan preparedMnaPlan;

        private PreparedTransientPlan(
                double timeStepSeconds,
                double startTimeSeconds,
                PreparedTransientTopology baseTopology
        ) {
            this.timeStepSeconds = timeStepSeconds;
            this.baseTopology = baseTopology;
            TransientEquationRecorder templateRecorder = recordTransientElements(startTimeSeconds + timeStepSeconds);
            this.preparedMnaPlan = MnaSolver.prepare(
                    buildStepTopology(templateRecorder),
                    baseTopology.nodes(),
                    new MnaSolver.StaticStampCounts(
                            baseTopology.conductances().size(),
                            baseTopology.currentSources().size(),
                            baseTopology.voltageSources().size(),
                            baseTopology.voltageControlledCurrentSources().size(),
                            baseTopology.voltageControlledVoltageSources().size(),
                            baseTopology.currentControlledCurrentSources().size(),
                            baseTopology.currentControlledVoltageSources().size()
                    )
            );
        }

        CircuitSnapshot step(double simulatedTimeSeconds) {
            TransientEquationRecorder transientRecorder = recordTransientElements(simulatedTimeSeconds);
            SolveTopology topology = buildStepTopology(transientRecorder);
            SolveResult result = MnaSolver.solvePrepared(topology, preparedMnaPlan);
            Map<CircuitPort, CircuitSample> transientSamples = withTransientStoredEnergy(
                    result.samples(),
                    topology.transientCapacitances(),
                    topology.transientInductances()
            );

            if (!hasError(result.diagnostics())) {
                updateTransientVoltages(topology.transientCapacitances(), transientSamples);
                updateTransientCurrents(topology.transientInductances(), transientSamples);
                updateTransientPortVoltages(transientSamples);
            }

            return new CircuitSnapshot(
                    result.nodes(),
                    baseTopology.ports(),
                    baseTopology.terminals(),
                    baseTopology.elements(),
                    result.diagnostics(),
                    List.copyOf(transientSamples.values()),
                    simulatedTimeSeconds
            );
        }

        private TransientEquationRecorder recordTransientElements(double simulatedTimeSeconds) {
            TransientEquationRecorder transientRecorder = new TransientEquationRecorder(
                    baseTopology.portIndexes(),
                    timeStepSeconds,
                    simulatedTimeSeconds,
                    transientVoltages,
                    transientCurrents
            );
            for (TransientElementStamp transientElement : baseTopology.transientElements()) {
                transientRecorder.record(transientElement.element(), transientElement.transientElement());
            }
            return transientRecorder;
        }

        private SolveTopology buildStepTopology(TransientEquationRecorder transientRecorder) {
            List<VoltageSourceStamp> voltageSources = combineVoltageSources(
                    baseTopology.voltageSources(),
                    transientRecorder.voltageSources()
            );
            return new SolveTopology(
                    baseTopology.ports(),
                    baseTopology.portIndexes(),
                    baseTopology.nodeByPort(),
                    baseTopology.nodeCount(),
                    initialNodeVoltages(
                            baseTopology.ports(),
                            baseTopology.nodeByPort(),
                            baseTopology.nodeCount(),
                            transientPortVoltages
                    ),
                    combineConductances(baseTopology.conductances(), transientRecorder.conductances()),
                    combineCurrentSources(baseTopology.currentSources(), transientRecorder.currentSources()),
                    voltageSources,
                    baseTopology.voltageControlledCurrentSources(),
                    baseTopology.voltageControlledVoltageSources(),
                    baseTopology.currentControlledCurrentSources(),
                    baseTopology.currentControlledVoltageSources(),
                    baseTopology.nonlinearElements(),
                    transientRecorder.capacitances(),
                    transientRecorder.inductances(),
                    combineDiagnostics(baseTopology.diagnostics(), transientRecorder.diagnostics())
            );
        }
    }

    record PreparedTransientTopology(
            List<CircuitPort> ports,
            Map<CircuitPort, Integer> portIndexes,
            int[] nodeByPort,
            int nodeCount,
            List<CircuitNode> nodes,
            List<ConductanceStamp> conductances,
            List<CurrentSourceStamp> currentSources,
            List<VoltageSourceStamp> voltageSources,
            List<VoltageControlledCurrentSourceStamp> voltageControlledCurrentSources,
            List<VoltageControlledVoltageSourceStamp> voltageControlledVoltageSources,
            List<CurrentControlledCurrentSourceStamp> currentControlledCurrentSources,
            List<CurrentControlledVoltageSourceStamp> currentControlledVoltageSources,
            List<NonlinearElementStamp> nonlinearElements,
            List<TransientElementStamp> transientElements,
            List<CircuitDiagnostic> diagnostics,
            List<CircuitTerminal> terminals,
            List<CircuitElement> elements
    ) {
        PreparedTransientTopology {
            ports = List.copyOf(ports);
            portIndexes = Map.copyOf(portIndexes);
            nodeByPort = nodeByPort.clone();
            nodes = List.copyOf(nodes);
            conductances = List.copyOf(conductances);
            currentSources = List.copyOf(currentSources);
            voltageSources = List.copyOf(voltageSources);
            voltageControlledCurrentSources = List.copyOf(voltageControlledCurrentSources);
            voltageControlledVoltageSources = List.copyOf(voltageControlledVoltageSources);
            currentControlledCurrentSources = List.copyOf(currentControlledCurrentSources);
            currentControlledVoltageSources = List.copyOf(currentControlledVoltageSources);
            nonlinearElements = List.copyOf(nonlinearElements);
            transientElements = List.copyOf(transientElements);
            diagnostics = List.copyOf(diagnostics);
            terminals = List.copyOf(terminals);
            elements = List.copyOf(elements);
        }
    }

    record TransientElementStamp(
            CircuitElement element,
            TransientCircuitElement transientElement
    ) {
    }

    record SolveTopology(
            List<CircuitPort> ports,
            Map<CircuitPort, Integer> portIndexes,
            int[] nodeByPort,
            int nodeCount,
            double[] initialNodeVoltages,
            List<ConductanceStamp> conductances,
            List<CurrentSourceStamp> currentSources,
            List<VoltageSourceStamp> voltageSources,
            List<VoltageControlledCurrentSourceStamp> voltageControlledCurrentSources,
            List<VoltageControlledVoltageSourceStamp> voltageControlledVoltageSources,
            List<CurrentControlledCurrentSourceStamp> currentControlledCurrentSources,
            List<CurrentControlledVoltageSourceStamp> currentControlledVoltageSources,
            List<NonlinearElementStamp> nonlinearElements,
            List<TransientCapacitanceStamp> transientCapacitances,
            List<TransientInductanceStamp> transientInductances,
            List<CircuitDiagnostic> diagnostics
    ) implements MnaTopologySupport.NodeLookup {
        public int nodeOf(CircuitPort port) {
            Integer portIndex = portIndexes.get(port);
            if (portIndex == null) {
                throw new IllegalArgumentException("Unknown circuit port: " + port);
            }
            return nodeByPort[portIndex];
        }

        CircuitPort representativePort(int node) {
            for (int portIndex = 0; portIndex < nodeByPort.length; portIndex++) {
                if (nodeByPort[portIndex] == node) {
                    return ports.get(portIndex);
                }
            }
            throw new IllegalArgumentException("Unknown circuit node: " + node);
        }
    }

    record SolveResult(
            List<CircuitNode> nodes,
            List<CircuitDiagnostic> diagnostics,
            Map<CircuitPort, CircuitSample> samples
    ) {
    }

    record AcSolveTopology(
            List<CircuitPort> ports,
            Map<CircuitPort, Integer> portIndexes,
            int[] nodeByPort,
            int nodeCount,
            double frequencyHertz,
            List<AcAdmittanceStamp> admittances,
            List<AcCurrentSourceStamp> currentSources,
            List<AcVoltageSourceStamp> voltageSources,
            List<AcVoltageControlledCurrentSourceStamp> voltageControlledCurrentSources,
            List<AcVoltageControlledVoltageSourceStamp> voltageControlledVoltageSources,
            List<AcCurrentControlledCurrentSourceStamp> currentControlledCurrentSources,
            List<AcCurrentControlledVoltageSourceStamp> currentControlledVoltageSources,
            List<CircuitDiagnostic> diagnostics
    ) implements MnaTopologySupport.NodeLookup {
        public int nodeOf(CircuitPort port) {
            Integer portIndex = portIndexes.get(port);
            if (portIndex == null) {
                throw new IllegalArgumentException("Unknown circuit port: " + port);
            }
            return nodeByPort[portIndex];
        }

        CircuitPort representativePort(int node) {
            for (int portIndex = 0; portIndex < nodeByPort.length; portIndex++) {
                if (nodeByPort[portIndex] == node) {
                    return ports.get(portIndex);
                }
            }
            throw new IllegalArgumentException("Unknown circuit node: " + node);
        }
    }

    record AcSolveResult(
            List<CircuitNode> nodes,
            List<CircuitDiagnostic> diagnostics,
            List<AcCircuitSample> samples
    ) {
    }

    record ConductanceStamp(
            ResourceLocation id,
            CircuitPort positivePort,
            CircuitPort negativePort,
            double conductanceSiemens
    ) {
    }

    record CurrentSourceStamp(
            ResourceLocation id,
            CircuitPort positivePort,
            CircuitPort negativePort,
            double currentAmps
    ) {
    }

    record VoltageSourceStamp(
            ResourceLocation id,
            CircuitBranchCurrent branchCurrent,
            CircuitPort positivePort,
            CircuitPort negativePort,
            double voltageVolts
    ) implements MnaTopologySupport.VoltageLikeBranch {
    }

    record VoltageControlledCurrentSourceStamp(
            ResourceLocation id,
            CircuitPort positivePort,
            CircuitPort negativePort,
            CircuitPort controlPositivePort,
            CircuitPort controlNegativePort,
            double transconductanceSiemens
    ) {
    }

    record VoltageControlledVoltageSourceStamp(
            ResourceLocation id,
            CircuitBranchCurrent branchCurrent,
            CircuitPort positivePort,
            CircuitPort negativePort,
            CircuitPort controlPositivePort,
            CircuitPort controlNegativePort,
            double gain
    ) implements MnaTopologySupport.VoltageLikeBranch {
    }

    record CurrentControlledCurrentSourceStamp(
            ResourceLocation id,
            CircuitPort positivePort,
            CircuitPort negativePort,
            CircuitBranchCurrent controlCurrent,
            double gain
    ) {
    }

    record CurrentControlledVoltageSourceStamp(
            ResourceLocation id,
            CircuitBranchCurrent branchCurrent,
            CircuitPort positivePort,
            CircuitPort negativePort,
            CircuitBranchCurrent controlCurrent,
            double transresistanceOhms
    ) implements MnaTopologySupport.VoltageLikeBranch {
    }

    record NonlinearElementStamp(
            ResourceLocation id,
            List<CircuitPort> ports,
            NonlinearCircuitElement element
    ) {
        NonlinearElementStamp {
            ports = List.copyOf(ports);
        }
    }

    record NonlinearCurrentStamp(
            ResourceLocation id,
            CircuitPort positivePort,
            CircuitPort negativePort,
            double currentAmps,
            double conductanceSiemens
    ) {
    }

    record TransientCapacitanceStamp(
            ResourceLocation stateId,
            CircuitPort positivePort,
            CircuitPort negativePort,
            double capacitanceFarads
    ) {
    }

    record TransientInductanceStamp(
            ResourceLocation stateId,
            CircuitPort positivePort,
            CircuitPort negativePort,
            double inductanceHenries,
            double conductanceSiemens,
            double previousCurrentAmps
    ) {
    }

    record AcAdmittanceStamp(
            ResourceLocation id,
            CircuitPort positivePort,
            CircuitPort negativePort,
            Complex admittanceSiemens
    ) {
    }

    record AcCurrentSourceStamp(
            ResourceLocation id,
            CircuitPort positivePort,
            CircuitPort negativePort,
            Complex currentAmps
    ) {
    }

    record AcVoltageSourceStamp(
            ResourceLocation id,
            CircuitBranchCurrent branchCurrent,
            CircuitPort positivePort,
            CircuitPort negativePort,
            Complex voltageVolts
    ) implements MnaTopologySupport.VoltageLikeBranch {
    }

    record AcVoltageControlledCurrentSourceStamp(
            ResourceLocation id,
            CircuitPort positivePort,
            CircuitPort negativePort,
            CircuitPort controlPositivePort,
            CircuitPort controlNegativePort,
            Complex transconductanceSiemens
    ) {
    }

    record AcVoltageControlledVoltageSourceStamp(
            ResourceLocation id,
            CircuitBranchCurrent branchCurrent,
            CircuitPort positivePort,
            CircuitPort negativePort,
            CircuitPort controlPositivePort,
            CircuitPort controlNegativePort,
            Complex gain
    ) implements MnaTopologySupport.VoltageLikeBranch {
    }

    record AcCurrentControlledCurrentSourceStamp(
            ResourceLocation id,
            CircuitPort positivePort,
            CircuitPort negativePort,
            CircuitBranchCurrent controlCurrent,
            Complex gain
    ) {
    }

    record AcCurrentControlledVoltageSourceStamp(
            ResourceLocation id,
            CircuitBranchCurrent branchCurrent,
            CircuitPort positivePort,
            CircuitPort negativePort,
            CircuitBranchCurrent controlCurrent,
            Complex transimpedanceOhms
    ) implements MnaTopologySupport.VoltageLikeBranch {
    }

    private record IdealConnection(CircuitPort firstPort, CircuitPort secondPort) {
    }

    private static final class TopologyRecorder {
        private final Map<CircuitPort, Integer> portIndexes;
        private final DisjointSet unionFind;
        private final List<CircuitDiagnostic> diagnostics = new ArrayList<>();

        private TopologyRecorder(Map<CircuitPort, Integer> portIndexes, DisjointSet unionFind) {
            this.portIndexes = portIndexes;
            this.unionFind = unionFind;
        }

        private void record(CircuitElement element, CircuitTopologyElement topologyElement) {
            Objects.requireNonNull(element, "element");
            Objects.requireNonNull(topologyElement, "topologyElement");

            TopologyCollector collector = new TopologyCollector(portIndexes);
            try {
                topologyElement.buildTopology(collector);
            } catch (RuntimeException exception) {
                diagnostics.add(new CircuitDiagnostic(
                        CircuitDiagnosticType.ELEMENT_TOPOLOGY_FAILED,
                        CircuitDiagnosticSeverity.ERROR,
                        element.ports(),
                        topologyFailureMessage(element, exception)
                ));
                EMcore.LOGGER.warn("Circuit element {} failed while building topology", element.id(), exception);
                return;
            }

            for (IdealConnection connection : collector.connections()) {
                union(unionFind, portIndexes, connection.firstPort(), connection.secondPort());
            }
        }

        private List<CircuitDiagnostic> diagnostics() {
            return List.copyOf(diagnostics);
        }

        private static String topologyFailureMessage(CircuitElement element, RuntimeException exception) {
            String detail = exception.getMessage();
            if (detail == null || detail.isBlank()) {
                return "Element " + element.id() + " failed while building topology";
            }
            return "Element " + element.id() + " failed while building topology: " + detail;
        }
    }

    private static final class TopologyCollector implements CircuitTopologyBuilder {
        private final Map<CircuitPort, Integer> portIndexes;
        private final List<IdealConnection> connections = new ArrayList<>();

        private TopologyCollector(Map<CircuitPort, Integer> portIndexes) {
            this.portIndexes = portIndexes;
        }

        @Override
        public void connectIdeal(CircuitPort firstPort, CircuitPort secondPort) {
            connections.add(new IdealConnection(
                    requirePort(firstPort, "firstPort"),
                    requirePort(secondPort, "secondPort")
            ));
        }

        private List<IdealConnection> connections() {
            return List.copyOf(connections);
        }

        private CircuitPort requirePort(CircuitPort port, String name) {
            Objects.requireNonNull(port, name);
            if (!portIndexes.containsKey(port)) {
                throw new IllegalArgumentException("Topology port was not returned by ports(): " + port);
            }
            return port;
        }
    }

    private static final class TransientEquationRecorder implements TransientCircuitEquationBuilder {
        private final Map<CircuitPort, Integer> portIndexes;
        private final double timeStepSeconds;
        private final double timeSeconds;
        private final Map<ResourceLocation, Double> previousVoltages;
        private final Map<ResourceLocation, Double> previousCurrents;
        private final List<ConductanceStamp> conductances = new ArrayList<>();
        private final List<CurrentSourceStamp> currentSources = new ArrayList<>();
        private final List<VoltageSourceStamp> voltageSources = new ArrayList<>();
        private final List<TransientCapacitanceStamp> capacitances = new ArrayList<>();
        private final List<TransientInductanceStamp> inductances = new ArrayList<>();
        private final List<CircuitDiagnostic> diagnostics = new ArrayList<>();
        private ResourceLocation elementId;
        private int branchCurrentIndex;
        private int capacitanceIndex;
        private int inductanceIndex;

        private TransientEquationRecorder(
                Map<CircuitPort, Integer> portIndexes,
                double timeStepSeconds,
                double timeSeconds,
                Map<ResourceLocation, Double> previousVoltages,
                Map<ResourceLocation, Double> previousCurrents
        ) {
            this.portIndexes = portIndexes;
            this.timeStepSeconds = timeStepSeconds;
            this.timeSeconds = timeSeconds;
            this.previousVoltages = previousVoltages;
            this.previousCurrents = previousCurrents;
        }

        private void record(CircuitElement element, TransientCircuitElement transientElement) {
            Objects.requireNonNull(element, "element");
            Objects.requireNonNull(transientElement, "transientElement");

            ResourceLocation previousElementId = elementId;
            int previousBranchCurrentIndex = branchCurrentIndex;
            int previousCapacitanceIndex = capacitanceIndex;
            int previousInductanceIndex = inductanceIndex;
            elementId = element.id();
            branchCurrentIndex = 0;
            capacitanceIndex = 0;
            inductanceIndex = 0;
            int conductanceSize = conductances.size();
            int currentSourceSize = currentSources.size();
            int voltageSourceSize = voltageSources.size();
            int capacitanceSize = capacitances.size();
            int inductanceSize = inductances.size();

            try {
                transientElement.stamp(this);
            } catch (RuntimeException exception) {
                trim(conductances, conductanceSize);
                trim(currentSources, currentSourceSize);
                trim(voltageSources, voltageSourceSize);
                trim(capacitances, capacitanceSize);
                trim(inductances, inductanceSize);
                diagnostics.add(new CircuitDiagnostic(
                        CircuitDiagnosticType.ELEMENT_STAMP_FAILED,
                        CircuitDiagnosticSeverity.ERROR,
                        element.ports(),
                        transientFailureMessage(element, exception)
                ));
                EMcore.LOGGER.warn("Transient circuit element {} failed while stamping equations", element.id(), exception);
            } finally {
                elementId = previousElementId;
                branchCurrentIndex = previousBranchCurrentIndex;
                capacitanceIndex = previousCapacitanceIndex;
                inductanceIndex = previousInductanceIndex;
            }
        }

        @Override
        public double timeStepSeconds() {
            return timeStepSeconds;
        }

        @Override
        public double timeSeconds() {
            return timeSeconds;
        }

        @Override
        public void addConductance(CircuitPort positivePort, CircuitPort negativePort, double conductanceSiemens) {
            requireFinite(conductanceSiemens, "conductanceSiemens");
            if (conductanceSiemens == 0.0) {
                return;
            }

            conductances.add(new ConductanceStamp(
                    elementId,
                    requirePort(positivePort, "positivePort"),
                    requirePort(negativePort, "negativePort"),
                    conductanceSiemens
            ));
        }

        @Override
        public void addCurrentSource(CircuitPort positivePort, CircuitPort negativePort, double currentAmps) {
            requireFinite(currentAmps, "currentAmps");
            currentSources.add(new CurrentSourceStamp(
                    elementId,
                    requirePort(positivePort, "positivePort"),
                    requirePort(negativePort, "negativePort"),
                    currentAmps
            ));
        }

        @Override
        public CircuitBranchCurrent addVoltageSource(
                CircuitPort positivePort,
                CircuitPort negativePort,
                double voltageVolts
        ) {
            requireFinite(voltageVolts, "voltageVolts");
            CircuitBranchCurrent branchCurrent = nextBranchCurrent();
            voltageSources.add(new VoltageSourceStamp(
                    elementId,
                    branchCurrent,
                    requirePort(positivePort, "positivePort"),
                    requirePort(negativePort, "negativePort"),
                    voltageVolts
            ));
            return branchCurrent;
        }

        @Override
        public void addCapacitance(CircuitPort positivePort, CircuitPort negativePort, double capacitanceFarads) {
            requireFinitePositive(capacitanceFarads, "capacitanceFarads");
            CircuitPort checkedPositivePort = requirePort(positivePort, "positivePort");
            CircuitPort checkedNegativePort = requirePort(negativePort, "negativePort");
            ResourceLocation stateId = nextCapacitanceStateId();
            double companionConductance = capacitanceFarads / timeStepSeconds;
            double previousVoltage = previousVoltages.getOrDefault(stateId, 0.0);
            double equivalentCurrent = -companionConductance * previousVoltage;

            conductances.add(new ConductanceStamp(
                    stateId,
                    checkedPositivePort,
                    checkedNegativePort,
                    companionConductance
            ));
            currentSources.add(new CurrentSourceStamp(
                    stateId,
                    checkedPositivePort,
                    checkedNegativePort,
                    equivalentCurrent
            ));
            capacitances.add(new TransientCapacitanceStamp(
                    stateId,
                    checkedPositivePort,
                    checkedNegativePort,
                    capacitanceFarads
            ));
        }

        @Override
        public void addInductance(CircuitPort positivePort, CircuitPort negativePort, double inductanceHenries) {
            requireFinitePositive(inductanceHenries, "inductanceHenries");
            CircuitPort checkedPositivePort = requirePort(positivePort, "positivePort");
            CircuitPort checkedNegativePort = requirePort(negativePort, "negativePort");
            ResourceLocation stateId = nextInductanceStateId();
            double companionConductance = timeStepSeconds / inductanceHenries;
            double previousCurrent = previousCurrents.getOrDefault(stateId, 0.0);

            conductances.add(new ConductanceStamp(
                    stateId,
                    checkedPositivePort,
                    checkedNegativePort,
                    companionConductance
            ));
            currentSources.add(new CurrentSourceStamp(
                    stateId,
                    checkedPositivePort,
                    checkedNegativePort,
                    previousCurrent
            ));
            inductances.add(new TransientInductanceStamp(
                    stateId,
                    checkedPositivePort,
                    checkedNegativePort,
                    inductanceHenries,
                    companionConductance,
                    previousCurrent
            ));
        }

        private List<ConductanceStamp> conductances() {
            return List.copyOf(conductances);
        }

        private List<CurrentSourceStamp> currentSources() {
            return List.copyOf(currentSources);
        }

        private List<VoltageSourceStamp> voltageSources() {
            return List.copyOf(voltageSources);
        }

        private List<TransientCapacitanceStamp> capacitances() {
            return List.copyOf(capacitances);
        }

        private List<TransientInductanceStamp> inductances() {
            return List.copyOf(inductances);
        }

        private List<CircuitDiagnostic> diagnostics() {
            return List.copyOf(diagnostics);
        }

        private CircuitPort requirePort(CircuitPort port, String name) {
            Objects.requireNonNull(port, name);
            if (!portIndexes.containsKey(port)) {
                throw new IllegalArgumentException("Transient stamped port was not returned by ports(): " + port);
            }
            return port;
        }

        private ResourceLocation nextCapacitanceStateId() {
            return ResourceLocation.fromNamespaceAndPath(
                    elementId.getNamespace(),
                    elementId.getPath() + "/transient_capacitance/" + capacitanceIndex++
            );
        }

        private CircuitBranchCurrent nextBranchCurrent() {
            return new CircuitBranchCurrent(ResourceLocation.fromNamespaceAndPath(
                    elementId.getNamespace(),
                    elementId.getPath() + "/transient_branch_current/" + branchCurrentIndex++
            ));
        }

        private ResourceLocation nextInductanceStateId() {
            return ResourceLocation.fromNamespaceAndPath(
                    elementId.getNamespace(),
                    elementId.getPath() + "/transient_inductance/" + inductanceIndex++
            );
        }

        private static void requireFinitePositive(double value, String name) {
            if (!Double.isFinite(value) || value <= 0.0) {
                throw new IllegalArgumentException(name + " must be finite and greater than zero");
            }
        }

        private static void requireFinite(double value, String name) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException(name + " must be finite");
            }
        }

        private static String transientFailureMessage(CircuitElement element, RuntimeException exception) {
            String detail = exception.getMessage();
            if (detail == null || detail.isBlank()) {
                return "Element " + element.id() + " failed while stamping transient equations";
            }
            return "Element " + element.id() + " failed while stamping transient equations: " + detail;
        }

        private static <T> void trim(List<T> values, int size) {
            while (values.size() > size) {
                values.removeLast();
            }
        }
    }

    private static final class SmallSignalNonlinearRecorder implements NonlinearCircuitEquationBuilder {
        private final Map<CircuitPort, Integer> portIndexes;
        private final Map<CircuitPort, CircuitSample> operatingPointSamples;
        private final List<AcAdmittanceStamp> admittances = new ArrayList<>();
        private final List<CircuitDiagnostic> diagnostics = new ArrayList<>();
        private ResourceLocation elementId;
        private List<CircuitPort> elementPorts = List.of();

        private SmallSignalNonlinearRecorder(
                Map<CircuitPort, Integer> portIndexes,
                Map<CircuitPort, CircuitSample> operatingPointSamples
        ) {
            this.portIndexes = portIndexes;
            this.operatingPointSamples = operatingPointSamples;
        }

        private void record(NonlinearElementStamp nonlinearElement) {
            ResourceLocation previousElementId = elementId;
            List<CircuitPort> previousElementPorts = elementPorts;
            elementId = nonlinearElement.id();
            elementPorts = nonlinearElement.ports();
            int admittanceSize = admittances.size();

            try {
                nonlinearElement.element().stamp(this);
            } catch (RuntimeException exception) {
                trim(admittances, admittanceSize);
                diagnostics.add(new CircuitDiagnostic(
                        CircuitDiagnosticType.ELEMENT_STAMP_FAILED,
                        CircuitDiagnosticSeverity.ERROR,
                        nonlinearElement.ports(),
                        smallSignalFailureMessage(nonlinearElement, exception)
                ));
                EMcore.LOGGER.warn(
                        "Nonlinear circuit element {} failed while stamping small-signal AC equations",
                        nonlinearElement.id(),
                        exception
                );
            } finally {
                elementId = previousElementId;
                elementPorts = previousElementPorts;
            }
        }

        @Override
        public double voltage(CircuitPort positivePort, CircuitPort negativePort) {
            CircuitPort checkedPositivePort = requirePort(positivePort, "positivePort");
            CircuitPort checkedNegativePort = requirePort(negativePort, "negativePort");
            return sampleVoltage(checkedPositivePort) - sampleVoltage(checkedNegativePort);
        }

        @Override
        public void addLinearizedCurrent(
                CircuitPort positivePort,
                CircuitPort negativePort,
                double currentAmps,
                double conductanceSiemens
        ) {
            requireFinite(currentAmps, "currentAmps");
            requireFinite(conductanceSiemens, "conductanceSiemens");
            if (conductanceSiemens == 0.0) {
                return;
            }

            admittances.add(new AcAdmittanceStamp(
                    elementId,
                    requirePort(positivePort, "positivePort"),
                    requirePort(negativePort, "negativePort"),
                    Complex.real(conductanceSiemens)
            ));
        }

        private List<AcAdmittanceStamp> admittances() {
            return List.copyOf(admittances);
        }

        private List<CircuitDiagnostic> diagnostics() {
            return List.copyOf(diagnostics);
        }

        private double sampleVoltage(CircuitPort port) {
            CircuitSample sample = operatingPointSamples.get(port);
            return sample == null ? 0.0 : sample.voltageVolts();
        }

        private CircuitPort requirePort(CircuitPort port, String name) {
            CircuitPort checkedPort = Objects.requireNonNull(port, name);
            if (!elementPorts.contains(checkedPort)) {
                throw new IllegalArgumentException("Small-signal port was not returned by ports(): " + checkedPort);
            }
            if (!portIndexes.containsKey(checkedPort)) {
                throw new IllegalArgumentException("Unknown small-signal circuit port: " + checkedPort);
            }
            return checkedPort;
        }

        private static void requireFinite(double value, String name) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException(name + " must be finite");
            }
        }

        private static String smallSignalFailureMessage(
                NonlinearElementStamp element,
                RuntimeException exception
        ) {
            String detail = exception.getMessage();
            if (detail == null || detail.isBlank()) {
                return "Element " + element.id() + " failed while stamping small-signal AC equations";
            }
            return "Element " + element.id() + " failed while stamping small-signal AC equations: " + detail;
        }

        private static <T> void trim(List<T> values, int size) {
            while (values.size() > size) {
                values.removeLast();
            }
        }
    }

    private static final class EquationRecorder implements CircuitEquationBuilder {
        private final Map<CircuitPort, Integer> portIndexes;
        private final List<ConductanceStamp> conductances = new ArrayList<>();
        private final List<CurrentSourceStamp> currentSources = new ArrayList<>();
        private final List<VoltageSourceStamp> voltageSources = new ArrayList<>();
        private final List<VoltageControlledCurrentSourceStamp> voltageControlledCurrentSources = new ArrayList<>();
        private final List<VoltageControlledVoltageSourceStamp> voltageControlledVoltageSources = new ArrayList<>();
        private final List<CurrentControlledCurrentSourceStamp> currentControlledCurrentSources = new ArrayList<>();
        private final List<CurrentControlledVoltageSourceStamp> currentControlledVoltageSources = new ArrayList<>();
        private final List<CircuitDiagnostic> diagnostics = new ArrayList<>();
        private ResourceLocation elementId;
        private int branchCurrentIndex;

        private EquationRecorder(Map<CircuitPort, Integer> portIndexes) {
            this.portIndexes = portIndexes;
        }

        private void record(CircuitElement element, LinearCircuitElement linearElement) {
            Objects.requireNonNull(element, "element");
            Objects.requireNonNull(linearElement, "linearElement");

            ResourceLocation previousElementId = elementId;
            elementId = element.id();
            int conductanceSize = conductances.size();
            int currentSourceSize = currentSources.size();
            int voltageSourceSize = voltageSources.size();
            int voltageControlledCurrentSourceSize = voltageControlledCurrentSources.size();
            int voltageControlledVoltageSourceSize = voltageControlledVoltageSources.size();
            int currentControlledCurrentSourceSize = currentControlledCurrentSources.size();
            int currentControlledVoltageSourceSize = currentControlledVoltageSources.size();
            int previousBranchCurrentIndex = branchCurrentIndex;

            try {
                linearElement.stamp(this);
            } catch (RuntimeException exception) {
                trim(conductances, conductanceSize);
                trim(currentSources, currentSourceSize);
                trim(voltageSources, voltageSourceSize);
                trim(voltageControlledCurrentSources, voltageControlledCurrentSourceSize);
                trim(voltageControlledVoltageSources, voltageControlledVoltageSourceSize);
                trim(currentControlledCurrentSources, currentControlledCurrentSourceSize);
                trim(currentControlledVoltageSources, currentControlledVoltageSourceSize);
                branchCurrentIndex = previousBranchCurrentIndex;
                diagnostics.add(new CircuitDiagnostic(
                        CircuitDiagnosticType.ELEMENT_STAMP_FAILED,
                        CircuitDiagnosticSeverity.ERROR,
                        element.ports(),
                        stampFailureMessage(element, exception)
                ));
                EMcore.LOGGER.warn("Circuit element {} failed while stamping equations", element.id(), exception);
            } finally {
                elementId = previousElementId;
            }
        }

        @Override
        public void addConductance(CircuitPort positivePort, CircuitPort negativePort, double conductanceSiemens) {
            requireFinite(conductanceSiemens, "conductanceSiemens");
            if (conductanceSiemens == 0.0) {
                return;
            }

            conductances.add(new ConductanceStamp(
                    elementId,
                    requirePort(positivePort, "positivePort"),
                    requirePort(negativePort, "negativePort"),
                    conductanceSiemens
            ));
        }

        @Override
        public void addCurrentSource(CircuitPort positivePort, CircuitPort negativePort, double currentAmps) {
            requireFinite(currentAmps, "currentAmps");
            if (currentAmps == 0.0) {
                return;
            }

            currentSources.add(new CurrentSourceStamp(
                    elementId,
                    requirePort(positivePort, "positivePort"),
                    requirePort(negativePort, "negativePort"),
                    currentAmps
            ));
        }

        @Override
        public CircuitBranchCurrent addVoltageSource(CircuitPort positivePort, CircuitPort negativePort, double voltageVolts) {
            requireFinite(voltageVolts, "voltageVolts");
            CircuitBranchCurrent branchCurrent = nextBranchCurrent();
            voltageSources.add(new VoltageSourceStamp(
                    elementId,
                    branchCurrent,
                    requirePort(positivePort, "positivePort"),
                    requirePort(negativePort, "negativePort"),
                    voltageVolts
            ));
            return branchCurrent;
        }

        @Override
        public CircuitBranchCurrent addCurrentProbe(CircuitPort positivePort, CircuitPort negativePort) {
            return addVoltageSource(positivePort, negativePort, 0.0);
        }

        @Override
        public void addVoltageControlledCurrentSource(
                CircuitPort positivePort,
                CircuitPort negativePort,
                CircuitPort controlPositivePort,
                CircuitPort controlNegativePort,
                double transconductanceSiemens
        ) {
            requireFinite(transconductanceSiemens, "transconductanceSiemens");
            if (transconductanceSiemens == 0.0) {
                return;
            }

            voltageControlledCurrentSources.add(new VoltageControlledCurrentSourceStamp(
                    elementId,
                    requirePort(positivePort, "positivePort"),
                    requirePort(negativePort, "negativePort"),
                    requirePort(controlPositivePort, "controlPositivePort"),
                    requirePort(controlNegativePort, "controlNegativePort"),
                    transconductanceSiemens
            ));
        }

        @Override
        public CircuitBranchCurrent addVoltageControlledVoltageSource(
                CircuitPort positivePort,
                CircuitPort negativePort,
                CircuitPort controlPositivePort,
                CircuitPort controlNegativePort,
                double gain
        ) {
            requireFinite(gain, "gain");
            CircuitBranchCurrent branchCurrent = nextBranchCurrent();
            voltageControlledVoltageSources.add(new VoltageControlledVoltageSourceStamp(
                    elementId,
                    branchCurrent,
                    requirePort(positivePort, "positivePort"),
                    requirePort(negativePort, "negativePort"),
                    requirePort(controlPositivePort, "controlPositivePort"),
                    requirePort(controlNegativePort, "controlNegativePort"),
                    gain
            ));
            return branchCurrent;
        }

        @Override
        public void addCurrentControlledCurrentSource(
                CircuitPort positivePort,
                CircuitPort negativePort,
                CircuitBranchCurrent controlCurrent,
                double gain
        ) {
            requireFinite(gain, "gain");
            if (gain == 0.0) {
                return;
            }

            currentControlledCurrentSources.add(new CurrentControlledCurrentSourceStamp(
                    elementId,
                    requirePort(positivePort, "positivePort"),
                    requirePort(negativePort, "negativePort"),
                    requireBranchCurrent(controlCurrent, "controlCurrent"),
                    gain
            ));
        }

        @Override
        public CircuitBranchCurrent addCurrentControlledVoltageSource(
                CircuitPort positivePort,
                CircuitPort negativePort,
                CircuitBranchCurrent controlCurrent,
                double transresistanceOhms
        ) {
            requireFinite(transresistanceOhms, "transresistanceOhms");
            CircuitBranchCurrent branchCurrent = nextBranchCurrent();
            currentControlledVoltageSources.add(new CurrentControlledVoltageSourceStamp(
                    elementId,
                    branchCurrent,
                    requirePort(positivePort, "positivePort"),
                    requirePort(negativePort, "negativePort"),
                    requireBranchCurrent(controlCurrent, "controlCurrent"),
                    transresistanceOhms
            ));
            return branchCurrent;
        }

        private List<ConductanceStamp> conductances() {
            return List.copyOf(conductances);
        }

        private List<CurrentSourceStamp> currentSources() {
            return List.copyOf(currentSources);
        }

        private List<VoltageSourceStamp> voltageSources() {
            return List.copyOf(voltageSources);
        }

        private List<VoltageControlledCurrentSourceStamp> voltageControlledCurrentSources() {
            return List.copyOf(voltageControlledCurrentSources);
        }

        private List<VoltageControlledVoltageSourceStamp> voltageControlledVoltageSources() {
            return List.copyOf(voltageControlledVoltageSources);
        }

        private List<CurrentControlledCurrentSourceStamp> currentControlledCurrentSources() {
            return List.copyOf(currentControlledCurrentSources);
        }

        private List<CurrentControlledVoltageSourceStamp> currentControlledVoltageSources() {
            return List.copyOf(currentControlledVoltageSources);
        }

        private List<CircuitDiagnostic> diagnostics() {
            return List.copyOf(diagnostics);
        }

        private CircuitPort requirePort(CircuitPort port, String name) {
            Objects.requireNonNull(port, name);
            if (!portIndexes.containsKey(port)) {
                throw new IllegalArgumentException("Stamped port was not returned by ports(): " + port);
            }
            return port;
        }

        private CircuitBranchCurrent requireBranchCurrent(CircuitBranchCurrent branchCurrent, String name) {
            return Objects.requireNonNull(branchCurrent, name);
        }

        private CircuitBranchCurrent nextBranchCurrent() {
            return new CircuitBranchCurrent(ResourceLocation.fromNamespaceAndPath(
                    elementId.getNamespace(),
                    elementId.getPath() + "/branch_current/" + branchCurrentIndex++
            ));
        }

        private static void requireFinite(double value, String name) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException(name + " must be finite");
            }
        }

        private static String stampFailureMessage(CircuitElement element, RuntimeException exception) {
            String detail = exception.getMessage();
            if (detail == null || detail.isBlank()) {
                return "Element " + element.id() + " failed while stamping equations";
            }
            return "Element " + element.id() + " failed while stamping equations: " + detail;
        }

        private static <T> void trim(List<T> values, int size) {
            while (values.size() > size) {
                values.removeLast();
            }
        }
    }

    private static final class AcEquationRecorder implements AcCircuitEquationBuilder {
        private final Map<CircuitPort, Integer> portIndexes;
        private final double frequencyHertz;
        private final List<AcAdmittanceStamp> admittances = new ArrayList<>();
        private final List<AcCurrentSourceStamp> currentSources = new ArrayList<>();
        private final List<AcVoltageSourceStamp> voltageSources = new ArrayList<>();
        private final List<AcVoltageControlledCurrentSourceStamp> voltageControlledCurrentSources = new ArrayList<>();
        private final List<AcVoltageControlledVoltageSourceStamp> voltageControlledVoltageSources = new ArrayList<>();
        private final List<AcCurrentControlledCurrentSourceStamp> currentControlledCurrentSources = new ArrayList<>();
        private final List<AcCurrentControlledVoltageSourceStamp> currentControlledVoltageSources = new ArrayList<>();
        private final List<CircuitDiagnostic> diagnostics = new ArrayList<>();
        private ResourceLocation elementId;
        private int branchCurrentIndex;

        private AcEquationRecorder(Map<CircuitPort, Integer> portIndexes, double frequencyHertz) {
            this.portIndexes = portIndexes;
            this.frequencyHertz = frequencyHertz;
        }

        private void record(CircuitElement element, AcLinearCircuitElement linearElement) {
            Objects.requireNonNull(element, "element");
            Objects.requireNonNull(linearElement, "linearElement");

            ResourceLocation previousElementId = elementId;
            elementId = element.id();
            int admittanceSize = admittances.size();
            int currentSourceSize = currentSources.size();
            int voltageSourceSize = voltageSources.size();
            int voltageControlledCurrentSourceSize = voltageControlledCurrentSources.size();
            int voltageControlledVoltageSourceSize = voltageControlledVoltageSources.size();
            int currentControlledCurrentSourceSize = currentControlledCurrentSources.size();
            int currentControlledVoltageSourceSize = currentControlledVoltageSources.size();
            int previousBranchCurrentIndex = branchCurrentIndex;

            try {
                linearElement.stamp(this);
            } catch (RuntimeException exception) {
                trim(admittances, admittanceSize);
                trim(currentSources, currentSourceSize);
                trim(voltageSources, voltageSourceSize);
                trim(voltageControlledCurrentSources, voltageControlledCurrentSourceSize);
                trim(voltageControlledVoltageSources, voltageControlledVoltageSourceSize);
                trim(currentControlledCurrentSources, currentControlledCurrentSourceSize);
                trim(currentControlledVoltageSources, currentControlledVoltageSourceSize);
                branchCurrentIndex = previousBranchCurrentIndex;
                diagnostics.add(new CircuitDiagnostic(
                        CircuitDiagnosticType.ELEMENT_STAMP_FAILED,
                        CircuitDiagnosticSeverity.ERROR,
                        element.ports(),
                        stampFailureMessage(element, exception)
                ));
                EMcore.LOGGER.warn("AC circuit element {} failed while stamping equations", element.id(), exception);
            } finally {
                elementId = previousElementId;
            }
        }

        @Override
        public double frequencyHertz() {
            return frequencyHertz;
        }

        @Override
        public void addAdmittance(CircuitPort positivePort, CircuitPort negativePort, CircuitPhasor admittanceSiemens) {
            Complex admittance = requireNonZeroPhasor(admittanceSiemens, "admittanceSiemens");
            if (admittance == null) {
                return;
            }

            admittances.add(new AcAdmittanceStamp(
                    elementId,
                    requirePort(positivePort, "positivePort"),
                    requirePort(negativePort, "negativePort"),
                    admittance
            ));
        }

        @Override
        public void addCurrentSource(CircuitPort positivePort, CircuitPort negativePort, CircuitPhasor currentAmps) {
            Complex current = requireNonZeroPhasor(currentAmps, "currentAmps");
            if (current == null) {
                return;
            }

            currentSources.add(new AcCurrentSourceStamp(
                    elementId,
                    requirePort(positivePort, "positivePort"),
                    requirePort(negativePort, "negativePort"),
                    current
            ));
        }

        @Override
        public CircuitBranchCurrent addVoltageSource(
                CircuitPort positivePort,
                CircuitPort negativePort,
                CircuitPhasor voltageVolts
        ) {
            Complex voltage = requirePhasor(voltageVolts, "voltageVolts");
            CircuitBranchCurrent branchCurrent = nextBranchCurrent();
            voltageSources.add(new AcVoltageSourceStamp(
                    elementId,
                    branchCurrent,
                    requirePort(positivePort, "positivePort"),
                    requirePort(negativePort, "negativePort"),
                    voltage
            ));
            return branchCurrent;
        }

        @Override
        public CircuitBranchCurrent addCurrentProbe(CircuitPort positivePort, CircuitPort negativePort) {
            return addVoltageSource(positivePort, negativePort, CircuitPhasor.ZERO);
        }

        @Override
        public void addVoltageControlledCurrentSource(
                CircuitPort positivePort,
                CircuitPort negativePort,
                CircuitPort controlPositivePort,
                CircuitPort controlNegativePort,
                CircuitPhasor transconductanceSiemens
        ) {
            Complex transconductance = requireNonZeroPhasor(
                    transconductanceSiemens,
                    "transconductanceSiemens"
            );
            if (transconductance == null) {
                return;
            }

            voltageControlledCurrentSources.add(new AcVoltageControlledCurrentSourceStamp(
                    elementId,
                    requirePort(positivePort, "positivePort"),
                    requirePort(negativePort, "negativePort"),
                    requirePort(controlPositivePort, "controlPositivePort"),
                    requirePort(controlNegativePort, "controlNegativePort"),
                    transconductance
            ));
        }

        @Override
        public CircuitBranchCurrent addVoltageControlledVoltageSource(
                CircuitPort positivePort,
                CircuitPort negativePort,
                CircuitPort controlPositivePort,
                CircuitPort controlNegativePort,
                CircuitPhasor gain
        ) {
            Complex sourceGain = requirePhasor(gain, "gain");
            CircuitBranchCurrent branchCurrent = nextBranchCurrent();
            voltageControlledVoltageSources.add(new AcVoltageControlledVoltageSourceStamp(
                    elementId,
                    branchCurrent,
                    requirePort(positivePort, "positivePort"),
                    requirePort(negativePort, "negativePort"),
                    requirePort(controlPositivePort, "controlPositivePort"),
                    requirePort(controlNegativePort, "controlNegativePort"),
                    sourceGain
            ));
            return branchCurrent;
        }

        @Override
        public void addCurrentControlledCurrentSource(
                CircuitPort positivePort,
                CircuitPort negativePort,
                CircuitBranchCurrent controlCurrent,
                CircuitPhasor gain
        ) {
            Complex sourceGain = requireNonZeroPhasor(gain, "gain");
            if (sourceGain == null) {
                return;
            }

            currentControlledCurrentSources.add(new AcCurrentControlledCurrentSourceStamp(
                    elementId,
                    requirePort(positivePort, "positivePort"),
                    requirePort(negativePort, "negativePort"),
                    requireBranchCurrent(controlCurrent, "controlCurrent"),
                    sourceGain
            ));
        }

        @Override
        public CircuitBranchCurrent addCurrentControlledVoltageSource(
                CircuitPort positivePort,
                CircuitPort negativePort,
                CircuitBranchCurrent controlCurrent,
                CircuitPhasor transimpedanceOhms
        ) {
            Complex transimpedance = requirePhasor(transimpedanceOhms, "transimpedanceOhms");
            CircuitBranchCurrent branchCurrent = nextBranchCurrent();
            currentControlledVoltageSources.add(new AcCurrentControlledVoltageSourceStamp(
                    elementId,
                    branchCurrent,
                    requirePort(positivePort, "positivePort"),
                    requirePort(negativePort, "negativePort"),
                    requireBranchCurrent(controlCurrent, "controlCurrent"),
                    transimpedance
            ));
            return branchCurrent;
        }

        private List<AcAdmittanceStamp> admittances() {
            return List.copyOf(admittances);
        }

        private List<AcCurrentSourceStamp> currentSources() {
            return List.copyOf(currentSources);
        }

        private List<AcVoltageSourceStamp> voltageSources() {
            return List.copyOf(voltageSources);
        }

        private List<AcVoltageControlledCurrentSourceStamp> voltageControlledCurrentSources() {
            return List.copyOf(voltageControlledCurrentSources);
        }

        private List<AcVoltageControlledVoltageSourceStamp> voltageControlledVoltageSources() {
            return List.copyOf(voltageControlledVoltageSources);
        }

        private List<AcCurrentControlledCurrentSourceStamp> currentControlledCurrentSources() {
            return List.copyOf(currentControlledCurrentSources);
        }

        private List<AcCurrentControlledVoltageSourceStamp> currentControlledVoltageSources() {
            return List.copyOf(currentControlledVoltageSources);
        }

        private List<CircuitDiagnostic> diagnostics() {
            return List.copyOf(diagnostics);
        }

        private CircuitPort requirePort(CircuitPort port, String name) {
            Objects.requireNonNull(port, name);
            if (!portIndexes.containsKey(port)) {
                throw new IllegalArgumentException("Stamped port was not returned by ports(): " + port);
            }
            return port;
        }

        private CircuitBranchCurrent requireBranchCurrent(CircuitBranchCurrent branchCurrent, String name) {
            return Objects.requireNonNull(branchCurrent, name);
        }

        private CircuitBranchCurrent nextBranchCurrent() {
            return new CircuitBranchCurrent(ResourceLocation.fromNamespaceAndPath(
                    elementId.getNamespace(),
                    elementId.getPath() + "/ac_branch_current/" + branchCurrentIndex++
            ));
        }

        private static Complex requirePhasor(CircuitPhasor phasor, String name) {
            return Complex.from(Objects.requireNonNull(phasor, name));
        }

        private static Complex requireNonZeroPhasor(CircuitPhasor phasor, String name) {
            Complex value = requirePhasor(phasor, name);
            return value.real() == 0.0 && value.imaginary() == 0.0 ? null : value;
        }

        private static String stampFailureMessage(CircuitElement element, RuntimeException exception) {
            String detail = exception.getMessage();
            if (detail == null || detail.isBlank()) {
                return "Element " + element.id() + " failed while stamping AC equations";
            }
            return "Element " + element.id() + " failed while stamping AC equations: " + detail;
        }

        private static <T> void trim(List<T> values, int size) {
            while (values.size() > size) {
                values.removeLast();
            }
        }
    }

}
