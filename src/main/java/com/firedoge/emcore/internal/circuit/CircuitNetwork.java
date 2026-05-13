package com.firedoge.emcore.internal.circuit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import com.firedoge.emcore.EMcore;
import com.firedoge.emcore.api.circuit.CircuitElement;
import com.firedoge.emcore.api.circuit.CircuitNode;
import com.firedoge.emcore.api.circuit.CircuitPort;
import com.firedoge.emcore.api.circuit.CircuitSample;
import com.firedoge.emcore.api.circuit.CircuitSnapshot;
import com.firedoge.emcore.api.circuit.CircuitTerminal;
import com.firedoge.emcore.api.circuit.ResistorElement;
import com.firedoge.emcore.api.circuit.VoltageSourceElement;
import com.firedoge.emcore.api.circuit.WireElement;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

public final class CircuitNetwork {
    private static final double PIVOT_EPSILON = 1.0e-12;

    private final Map<CircuitPort, CircuitPort> ports = new LinkedHashMap<>();
    private final Map<CircuitPort, Integer> portReferences = new HashMap<>();
    private final Map<CircuitTerminal, Integer> terminalReferences = new HashMap<>();
    private final Map<BlockPos, List<CircuitTerminal>> terminalsByNode = new LinkedHashMap<>();
    private final Map<ResourceLocation, CircuitElement> elements = new LinkedHashMap<>();
    private final Map<CircuitPort, CircuitSample> samples = new LinkedHashMap<>();
    private List<CircuitNode> nodes = List.of();
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
                List.copyOf(samples.values()),
                simulatedTimeSeconds
        );
    }

    public Optional<CircuitSample> samplePort(CircuitPort port) {
        Objects.requireNonNull(port, "port");
        solveIfDirty();
        return Optional.ofNullable(samples.get(port));
    }

    private void solveIfDirty() {
        if (!dirty) {
            return;
        }

        SolveTopology topology = buildTopology();
        SolveResult result = solve(topology);
        nodes = result.nodes();
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

        UnionFind unionFind = new UnionFind(portList.size());
        List<ResistorElement> resistors = new ArrayList<>();
        List<VoltageSourceElement> voltageSources = new ArrayList<>();

        for (CircuitElement element : elements.values()) {
            if (element instanceof WireElement wire) {
                union(unionFind, portIndexes, wire.firstPort(), wire.secondPort());
            } else if (element instanceof ResistorElement resistor) {
                resistors.add(resistor);
            } else if (element instanceof VoltageSourceElement voltageSource) {
                voltageSources.add(voltageSource);
            }
        }
        for (List<CircuitTerminal> terminals : terminalsByNode.values()) {
            unionTerminals(unionFind, portIndexes, terminals);
        }

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

        return new SolveTopology(portList, portIndexes, nodeByPort, nodeByRoot.size(), resistors, voltageSources);
    }

    private Integer preferredGroundRoot(
            Map<CircuitPort, Integer> portIndexes,
            UnionFind unionFind,
            List<VoltageSourceElement> voltageSources
    ) {
        if (voltageSources.isEmpty()) {
            return null;
        }

        Integer negativePortIndex = portIndexes.get(voltageSources.getFirst().negativePort());
        return negativePortIndex == null ? null : unionFind.find(negativePortIndex);
    }

    private SolveResult solve(SolveTopology topology) {
        int nodeCount = topology.nodeCount();
        List<CircuitNode> solvedNodes = buildNodes(topology);
        Map<CircuitPort, MutableSample> mutableSamples = new LinkedHashMap<>();

        for (CircuitPort port : topology.ports()) {
            mutableSamples.put(port, new MutableSample(port));
        }

        if (nodeCount == 0) {
            return finish(solvedNodes, mutableSamples);
        }

        double[] nodeVoltages = new double[nodeCount];
        for (ComponentTopology component : buildComponents(topology)) {
            solveComponent(topology, component, nodeVoltages, mutableSamples);
        }

        for (CircuitPort port : topology.ports()) {
            MutableSample sample = mutableSamples.get(port);
            sample.voltageVolts = nodeVoltages[topology.nodeOf(port)];
        }

        return finish(solvedNodes, mutableSamples);
    }

    private void solveComponent(
            SolveTopology topology,
            ComponentTopology component,
            double[] nodeVoltages,
            Map<CircuitPort, MutableSample> mutableSamples
    ) {
        int groundNode = chooseGroundNode(topology, component);
        Map<Integer, Integer> nodeRows = new HashMap<>();

        for (int node : component.nodes()) {
            if (node != groundNode) {
                nodeRows.put(node, nodeRows.size());
            }
        }

        int voltageSourceOffset = nodeRows.size();
        int unknownCount = nodeRows.size() + component.voltageSources().size();
        double[][] matrix = new double[unknownCount][unknownCount];
        double[] rhs = new double[unknownCount];

        for (ResistorElement resistor : component.resistors()) {
            int positiveNode = topology.nodeOf(resistor.positivePort());
            int negativeNode = topology.nodeOf(resistor.negativePort());
            addConductance(matrix, nodeRows, positiveNode, negativeNode, 1.0 / resistor.resistanceOhms());
        }

        for (int sourceIndex = 0; sourceIndex < component.voltageSources().size(); sourceIndex++) {
            VoltageSourceElement source = component.voltageSources().get(sourceIndex);
            int positiveNode = topology.nodeOf(source.positivePort());
            int negativeNode = topology.nodeOf(source.negativePort());
            int sourceColumn = voltageSourceOffset + sourceIndex;

            addVoltageSource(matrix, rhs, nodeRows, positiveNode, negativeNode, sourceColumn, source.voltageVolts());
        }

        double[] solved = unknownCount == 0 ? new double[0] : solveLinearSystem(matrix, rhs);
        if (solved == null) {
            EMcore.LOGGER.warn("Circuit component solve failed; retaining zero samples for {} nodes", component.nodes().size());
            return;
        }

        for (Map.Entry<Integer, Integer> entry : nodeRows.entrySet()) {
            nodeVoltages[entry.getKey()] = solved[entry.getValue()];
        }

        for (ResistorElement resistor : component.resistors()) {
            int positiveNode = topology.nodeOf(resistor.positivePort());
            int negativeNode = topology.nodeOf(resistor.negativePort());
            double voltage = nodeVoltages[positiveNode] - nodeVoltages[negativeNode];
            double current = voltage / resistor.resistanceOhms();
            double power = voltage * current;

            addPortContribution(mutableSamples, resistor.positivePort(), nodeVoltages[positiveNode], current, power);
            addPortContribution(mutableSamples, resistor.negativePort(), nodeVoltages[negativeNode], -current, power);
        }

        for (int sourceIndex = 0; sourceIndex < component.voltageSources().size(); sourceIndex++) {
            VoltageSourceElement source = component.voltageSources().get(sourceIndex);
            int positiveNode = topology.nodeOf(source.positivePort());
            int negativeNode = topology.nodeOf(source.negativePort());
            double current = solved[voltageSourceOffset + sourceIndex];
            double power = source.voltageVolts() * current;

            addPortContribution(mutableSamples, source.positivePort(), nodeVoltages[positiveNode], current, power);
            addPortContribution(mutableSamples, source.negativePort(), nodeVoltages[negativeNode], -current, power);
        }
    }

    private List<ComponentTopology> buildComponents(SolveTopology topology) {
        UnionFind componentUnionFind = new UnionFind(topology.nodeCount());

        for (ResistorElement resistor : topology.resistors()) {
            connectNodes(componentUnionFind, topology, resistor.positivePort(), resistor.negativePort());
        }
        for (VoltageSourceElement source : topology.voltageSources()) {
            connectNodes(componentUnionFind, topology, source.positivePort(), source.negativePort());
        }

        Map<Integer, Integer> componentIndexes = new LinkedHashMap<>();
        List<List<Integer>> nodesByComponent = new ArrayList<>();
        int[] componentByNode = new int[topology.nodeCount()];

        for (int node = 0; node < topology.nodeCount(); node++) {
            int root = componentUnionFind.find(node);
            Integer componentIndex = componentIndexes.get(root);
            if (componentIndex == null) {
                componentIndex = nodesByComponent.size();
                componentIndexes.put(root, componentIndex);
                nodesByComponent.add(new ArrayList<>());
            }

            nodesByComponent.get(componentIndex).add(node);
            componentByNode[node] = componentIndex;
        }

        List<List<ResistorElement>> resistorsByComponent = new ArrayList<>();
        List<List<VoltageSourceElement>> sourcesByComponent = new ArrayList<>();
        for (int index = 0; index < nodesByComponent.size(); index++) {
            resistorsByComponent.add(new ArrayList<>());
            sourcesByComponent.add(new ArrayList<>());
        }

        for (ResistorElement resistor : topology.resistors()) {
            resistorsByComponent.get(componentByNode[topology.nodeOf(resistor.positivePort())]).add(resistor);
        }
        for (VoltageSourceElement source : topology.voltageSources()) {
            sourcesByComponent.get(componentByNode[topology.nodeOf(source.positivePort())]).add(source);
        }

        List<ComponentTopology> components = new ArrayList<>();
        for (int index = 0; index < nodesByComponent.size(); index++) {
            components.add(new ComponentTopology(
                    List.copyOf(nodesByComponent.get(index)),
                    List.copyOf(resistorsByComponent.get(index)),
                    List.copyOf(sourcesByComponent.get(index))
            ));
        }
        return components;
    }

    private static void connectNodes(
            UnionFind unionFind,
            SolveTopology topology,
            CircuitPort firstPort,
            CircuitPort secondPort
    ) {
        unionFind.union(topology.nodeOf(firstPort), topology.nodeOf(secondPort));
    }

    private static int chooseGroundNode(SolveTopology topology, ComponentTopology component) {
        for (VoltageSourceElement source : component.voltageSources()) {
            return topology.nodeOf(source.negativePort());
        }

        return component.nodes().getFirst();
    }

    private List<CircuitNode> buildNodes(SolveTopology topology) {
        List<CircuitNode> solvedNodes = new ArrayList<>();
        for (int node = 0; node < topology.nodeCount(); node++) {
            CircuitPort representative = topology.representativePort(node);
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(EMcore.MODID, "circuit_node/" + node);
            solvedNodes.add(new CircuitNode(id, centerOf(representative)));
        }
        return List.copyOf(solvedNodes);
    }

    private SolveResult finish(List<CircuitNode> solvedNodes, Map<CircuitPort, MutableSample> mutableSamples) {
        Map<CircuitPort, CircuitSample> solvedSamples = new LinkedHashMap<>();
        for (MutableSample sample : mutableSamples.values()) {
            solvedSamples.put(sample.port, sample.toSample());
        }
        return new SolveResult(solvedNodes, solvedSamples);
    }

    private static void union(
            UnionFind unionFind,
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
            UnionFind unionFind,
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

    private static void addConductance(
            double[][] matrix,
            Map<Integer, Integer> nodeRows,
            int positiveNode,
            int negativeNode,
            double conductance
    ) {
        int positiveRow = rowOf(nodeRows, positiveNode);
        int negativeRow = rowOf(nodeRows, negativeNode);

        if (positiveRow >= 0) {
            matrix[positiveRow][positiveRow] += conductance;
        }
        if (negativeRow >= 0) {
            matrix[negativeRow][negativeRow] += conductance;
        }
        if (positiveRow >= 0 && negativeRow >= 0) {
            matrix[positiveRow][negativeRow] -= conductance;
            matrix[negativeRow][positiveRow] -= conductance;
        }
    }

    private static void addVoltageSource(
            double[][] matrix,
            double[] rhs,
            Map<Integer, Integer> nodeRows,
            int positiveNode,
            int negativeNode,
            int sourceColumn,
            double voltage
    ) {
        int positiveRow = rowOf(nodeRows, positiveNode);
        int negativeRow = rowOf(nodeRows, negativeNode);

        if (positiveRow >= 0) {
            matrix[positiveRow][sourceColumn] += 1.0;
            matrix[sourceColumn][positiveRow] += 1.0;
        }
        if (negativeRow >= 0) {
            matrix[negativeRow][sourceColumn] -= 1.0;
            matrix[sourceColumn][negativeRow] -= 1.0;
        }

        rhs[sourceColumn] = voltage;
    }

    private static int rowOf(Map<Integer, Integer> nodeRows, int node) {
        return nodeRows.getOrDefault(node, -1);
    }

    private static void addPortContribution(
            Map<CircuitPort, MutableSample> samples,
            CircuitPort port,
            double voltage,
            double current,
            double power
    ) {
        MutableSample sample = samples.get(port);
        if (sample == null) {
            return;
        }

        sample.voltageVolts = voltage;
        sample.currentAmps += current;
        sample.powerWatts += power;
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

    private static double[] solveLinearSystem(double[][] matrix, double[] rhs) {
        int size = rhs.length;
        double[][] a = new double[size][size];
        double[] b = rhs.clone();

        for (int row = 0; row < size; row++) {
            a[row] = matrix[row].clone();
        }

        for (int column = 0; column < size; column++) {
            int pivotRow = column;
            double pivotValue = Math.abs(a[column][column]);

            for (int row = column + 1; row < size; row++) {
                double candidate = Math.abs(a[row][column]);
                if (candidate > pivotValue) {
                    pivotRow = row;
                    pivotValue = candidate;
                }
            }

            if (pivotValue < PIVOT_EPSILON) {
                return null;
            }

            if (pivotRow != column) {
                double[] row = a[column];
                a[column] = a[pivotRow];
                a[pivotRow] = row;

                double value = b[column];
                b[column] = b[pivotRow];
                b[pivotRow] = value;
            }

            double pivot = a[column][column];
            for (int row = column + 1; row < size; row++) {
                double factor = a[row][column] / pivot;
                if (factor == 0.0) {
                    continue;
                }

                a[row][column] = 0.0;
                for (int nextColumn = column + 1; nextColumn < size; nextColumn++) {
                    a[row][nextColumn] -= factor * a[column][nextColumn];
                }
                b[row] -= factor * b[column];
            }
        }

        double[] solution = new double[size];
        for (int row = size - 1; row >= 0; row--) {
            double value = b[row];
            for (int column = row + 1; column < size; column++) {
                value -= a[row][column] * solution[column];
            }
            solution[row] = value / a[row][row];
        }

        return solution;
    }

    private record SolveTopology(
            List<CircuitPort> ports,
            Map<CircuitPort, Integer> portIndexes,
            int[] nodeByPort,
            int nodeCount,
            List<ResistorElement> resistors,
            List<VoltageSourceElement> voltageSources
    ) {
        int nodeOf(CircuitPort port) {
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

    private record SolveResult(List<CircuitNode> nodes, Map<CircuitPort, CircuitSample> samples) {
    }

    private record ComponentTopology(
            List<Integer> nodes,
            List<ResistorElement> resistors,
            List<VoltageSourceElement> voltageSources
    ) {
    }

    private static final class MutableSample {
        private final CircuitPort port;
        private double voltageVolts;
        private double currentAmps;
        private double powerWatts;

        private MutableSample(CircuitPort port) {
            this.port = port;
        }

        private CircuitSample toSample() {
            return new CircuitSample(port, voltageVolts, currentAmps, powerWatts, 0.0, false);
        }
    }

    private static final class UnionFind {
        private final int[] parent;

        private UnionFind(int size) {
            parent = new int[size];
            for (int index = 0; index < size; index++) {
                parent[index] = index;
            }
        }

        private int find(int value) {
            int root = value;
            while (parent[root] != root) {
                root = parent[root];
            }

            while (parent[value] != value) {
                int next = parent[value];
                parent[value] = root;
                value = next;
            }

            return root;
        }

        private void union(int first, int second) {
            int firstRoot = find(first);
            int secondRoot = find(second);

            if (firstRoot != secondRoot) {
                parent[secondRoot] = firstRoot;
            }
        }
    }
}
