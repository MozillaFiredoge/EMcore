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
import com.firedoge.emcore.api.circuit.ResistorElement;
import com.firedoge.emcore.api.circuit.VoltageSourceElement;
import com.firedoge.emcore.api.circuit.WireElement;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

public final class CircuitNetwork {
    private static final double PIVOT_EPSILON = 1.0e-12;

    private final Map<CircuitPort, CircuitPort> ports = new LinkedHashMap<>();
    private final Map<ResourceLocation, CircuitElement> elements = new LinkedHashMap<>();
    private final Map<CircuitPort, CircuitSample> samples = new LinkedHashMap<>();
    private List<CircuitNode> nodes = List.of();
    private boolean dirty = true;

    public void registerPort(CircuitPort port) {
        Objects.requireNonNull(port, "port");
        ports.put(port, port);
        samples.putIfAbsent(port, zeroSample(port));
        dirty = true;
    }

    public void unregisterPort(CircuitPort port) {
        Objects.requireNonNull(port, "port");
        ports.remove(port);
        samples.remove(port);
        elements.entrySet().removeIf(entry -> entry.getValue().ports().contains(port));
        dirty = true;
    }

    public void registerElement(CircuitElement element) {
        Objects.requireNonNull(element, "element");
        element.ports().forEach(this::registerPort);
        elements.put(element.id(), element);
        dirty = true;
    }

    public void unregisterElement(ResourceLocation elementId) {
        Objects.requireNonNull(elementId, "elementId");
        elements.remove(elementId);
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

        Map<Integer, Integer> nodeByRoot = new LinkedHashMap<>();
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

        int nonGroundNodes = Math.max(0, nodeCount - 1);
        int voltageSourceOffset = nonGroundNodes;
        int unknownCount = nonGroundNodes + topology.voltageSources().size();
        double[][] matrix = new double[unknownCount][unknownCount];
        double[] rhs = new double[unknownCount];

        for (ResistorElement resistor : topology.resistors()) {
            int positiveNode = topology.nodeOf(resistor.positivePort());
            int negativeNode = topology.nodeOf(resistor.negativePort());
            addConductance(matrix, positiveNode, negativeNode, 1.0 / resistor.resistanceOhms());
        }

        for (int sourceIndex = 0; sourceIndex < topology.voltageSources().size(); sourceIndex++) {
            VoltageSourceElement source = topology.voltageSources().get(sourceIndex);
            int positiveNode = topology.nodeOf(source.positivePort());
            int negativeNode = topology.nodeOf(source.negativePort());
            int sourceColumn = voltageSourceOffset + sourceIndex;

            addVoltageSource(matrix, rhs, positiveNode, negativeNode, sourceColumn, source.voltageVolts());
        }

        double[] solved = unknownCount == 0 ? new double[0] : solveLinearSystem(matrix, rhs);
        if (solved == null) {
            EMcore.LOGGER.warn("Circuit solve failed; retaining zero samples for {} ports", topology.ports().size());
            return finish(solvedNodes, mutableSamples);
        }

        double[] nodeVoltages = new double[nodeCount];
        for (int node = 1; node < nodeCount; node++) {
            nodeVoltages[node] = solved[node - 1];
        }

        for (ResistorElement resistor : topology.resistors()) {
            int positiveNode = topology.nodeOf(resistor.positivePort());
            int negativeNode = topology.nodeOf(resistor.negativePort());
            double voltage = nodeVoltages[positiveNode] - nodeVoltages[negativeNode];
            double current = voltage / resistor.resistanceOhms();
            double power = voltage * current;

            addPortContribution(mutableSamples, resistor.positivePort(), nodeVoltages[positiveNode], current, power);
            addPortContribution(mutableSamples, resistor.negativePort(), nodeVoltages[negativeNode], -current, power);
        }

        for (int sourceIndex = 0; sourceIndex < topology.voltageSources().size(); sourceIndex++) {
            VoltageSourceElement source = topology.voltageSources().get(sourceIndex);
            int positiveNode = topology.nodeOf(source.positivePort());
            int negativeNode = topology.nodeOf(source.negativePort());
            double current = solved[voltageSourceOffset + sourceIndex];
            double power = source.voltageVolts() * current;

            addPortContribution(mutableSamples, source.positivePort(), nodeVoltages[positiveNode], current, power);
            addPortContribution(mutableSamples, source.negativePort(), nodeVoltages[negativeNode], -current, power);
        }

        for (CircuitPort port : topology.ports()) {
            MutableSample sample = mutableSamples.get(port);
            sample.voltageVolts = nodeVoltages[topology.nodeOf(port)];
        }

        return finish(solvedNodes, mutableSamples);
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

    private static void addConductance(double[][] matrix, int positiveNode, int negativeNode, double conductance) {
        int positiveRow = rowOf(positiveNode);
        int negativeRow = rowOf(negativeNode);

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
            int positiveNode,
            int negativeNode,
            int sourceColumn,
            double voltage
    ) {
        int positiveRow = rowOf(positiveNode);
        int negativeRow = rowOf(negativeNode);

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

    private static int rowOf(int node) {
        return node == 0 ? -1 : node - 1;
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
