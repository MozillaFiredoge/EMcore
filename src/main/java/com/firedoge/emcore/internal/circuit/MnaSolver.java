package com.firedoge.emcore.internal.circuit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.firedoge.emcore.EMcore;
import com.firedoge.emcore.api.circuit.CircuitBranchCurrent;
import com.firedoge.emcore.api.circuit.CircuitDiagnostic;
import com.firedoge.emcore.api.circuit.CircuitDiagnosticSeverity;
import com.firedoge.emcore.api.circuit.CircuitDiagnosticType;
import com.firedoge.emcore.api.circuit.CircuitNode;
import com.firedoge.emcore.api.circuit.CircuitPort;
import com.firedoge.emcore.api.circuit.CircuitSample;
import com.firedoge.emcore.internal.circuit.CircuitNetwork.ConductanceStamp;
import com.firedoge.emcore.internal.circuit.CircuitNetwork.CurrentControlledCurrentSourceStamp;
import com.firedoge.emcore.internal.circuit.CircuitNetwork.CurrentControlledVoltageSourceStamp;
import com.firedoge.emcore.internal.circuit.CircuitNetwork.CurrentSourceStamp;
import com.firedoge.emcore.internal.circuit.CircuitNetwork.SolveResult;
import com.firedoge.emcore.internal.circuit.CircuitNetwork.SolveTopology;
import com.firedoge.emcore.internal.circuit.CircuitNetwork.VoltageControlledCurrentSourceStamp;
import com.firedoge.emcore.internal.circuit.CircuitNetwork.VoltageControlledVoltageSourceStamp;
import com.firedoge.emcore.internal.circuit.CircuitNetwork.VoltageSourceStamp;

final class MnaSolver {
    private static final double VOLTAGE_EPSILON = 1.0e-9;
    private static final int DENSE_SOLVER_WARNING_UNKNOWNS = 128;

    private MnaSolver() {
    }

    static SolveResult solve(SolveTopology topology, List<CircuitNode> solvedNodes) {
        int nodeCount = topology.nodeCount();
        Map<CircuitPort, MutableSample> mutableSamples = new LinkedHashMap<>();
        List<CircuitDiagnostic> solvedDiagnostics = new ArrayList<>(topology.diagnostics());

        for (CircuitPort port : topology.ports()) {
            mutableSamples.put(port, new MutableSample(port));
        }

        if (nodeCount == 0) {
            return finish(solvedNodes, solvedDiagnostics, mutableSamples);
        }

        double[] nodeVoltages = new double[nodeCount];
        for (ComponentTopology component : buildComponents(topology)) {
            solveComponent(topology, component, nodeVoltages, mutableSamples, solvedDiagnostics);
        }

        for (CircuitPort port : topology.ports()) {
            MutableSample sample = mutableSamples.get(port);
            sample.voltageVolts = nodeVoltages[topology.nodeOf(port)];
        }

        return finish(solvedNodes, solvedDiagnostics, mutableSamples);
    }

    private static void solveComponent(
            SolveTopology topology,
            ComponentTopology component,
            double[] nodeVoltages,
            Map<CircuitPort, MutableSample> mutableSamples,
            List<CircuitDiagnostic> diagnostics
    ) {
        if (!validateVoltageSources(topology, component, diagnostics)) {
            return;
        }

        int groundNode = chooseGroundNode(topology, component);
        Map<Integer, Integer> nodeRows = new HashMap<>();

        for (int node : component.nodes()) {
            if (node != groundNode) {
                nodeRows.put(node, nodeRows.size());
            }
        }

        int voltageSourceOffset = nodeRows.size();
        int controlledVoltageSourceOffset = voltageSourceOffset + component.voltageSources().size();
        int currentControlledVoltageSourceOffset = controlledVoltageSourceOffset
                + component.voltageControlledVoltageSources().size();
        int unknownCount = nodeRows.size()
                + component.voltageSources().size()
                + component.voltageControlledVoltageSources().size()
                + component.currentControlledVoltageSources().size();
        warnIfDenseSolveIsLarge(component, unknownCount, diagnostics);

        double[][] matrix = new double[unknownCount][unknownCount];
        double[] rhs = new double[unknownCount];
        Map<CircuitBranchCurrent, Integer> branchCurrentColumns = branchCurrentColumns(
                component,
                voltageSourceOffset,
                controlledVoltageSourceOffset,
                currentControlledVoltageSourceOffset,
                diagnostics
        );
        if (branchCurrentColumns == null) {
            return;
        }

        for (ConductanceStamp conductance : component.conductances()) {
            int positiveNode = topology.nodeOf(conductance.positivePort());
            int negativeNode = topology.nodeOf(conductance.negativePort());
            addConductance(matrix, nodeRows, positiveNode, negativeNode, conductance.conductanceSiemens());
        }

        for (int sourceIndex = 0; sourceIndex < component.voltageSources().size(); sourceIndex++) {
            VoltageSourceStamp source = component.voltageSources().get(sourceIndex);
            int positiveNode = topology.nodeOf(source.positivePort());
            int negativeNode = topology.nodeOf(source.negativePort());
            int sourceColumn = branchCurrentColumns.get(source.branchCurrent());

            addVoltageSource(matrix, rhs, nodeRows, positiveNode, negativeNode, sourceColumn, source.voltageVolts());
        }

        for (int sourceIndex = 0; sourceIndex < component.voltageControlledVoltageSources().size(); sourceIndex++) {
            VoltageControlledVoltageSourceStamp source = component.voltageControlledVoltageSources().get(sourceIndex);
            int positiveNode = topology.nodeOf(source.positivePort());
            int negativeNode = topology.nodeOf(source.negativePort());
            int controlPositiveNode = topology.nodeOf(source.controlPositivePort());
            int controlNegativeNode = topology.nodeOf(source.controlNegativePort());
            int sourceColumn = branchCurrentColumns.get(source.branchCurrent());

            addVoltageControlledVoltageSource(
                    matrix,
                    nodeRows,
                    positiveNode,
                    negativeNode,
                    controlPositiveNode,
                    controlNegativeNode,
                    sourceColumn,
                    source.gain()
            );
        }

        for (int sourceIndex = 0; sourceIndex < component.currentControlledVoltageSources().size(); sourceIndex++) {
            CurrentControlledVoltageSourceStamp source = component.currentControlledVoltageSources().get(sourceIndex);
            int positiveNode = topology.nodeOf(source.positivePort());
            int negativeNode = topology.nodeOf(source.negativePort());
            int sourceColumn = branchCurrentColumns.get(source.branchCurrent());
            Integer controlColumn = branchCurrentColumns.get(source.controlCurrent());
            if (controlColumn == null) {
                MnaBranchCurrentColumns.addMissingDiagnostic(diagnostics, source.controlCurrent(), "Branch current");
                return;
            }

            addCurrentControlledVoltageSource(
                    matrix,
                    nodeRows,
                    positiveNode,
                    negativeNode,
                    sourceColumn,
                    controlColumn,
                    source.transresistanceOhms()
            );
        }

        for (CurrentSourceStamp currentSource : component.currentSources()) {
            int positiveNode = topology.nodeOf(currentSource.positivePort());
            int negativeNode = topology.nodeOf(currentSource.negativePort());
            addCurrentSource(rhs, nodeRows, positiveNode, negativeNode, currentSource.currentAmps());
        }
        for (VoltageControlledCurrentSourceStamp currentSource : component.voltageControlledCurrentSources()) {
            int positiveNode = topology.nodeOf(currentSource.positivePort());
            int negativeNode = topology.nodeOf(currentSource.negativePort());
            int controlPositiveNode = topology.nodeOf(currentSource.controlPositivePort());
            int controlNegativeNode = topology.nodeOf(currentSource.controlNegativePort());

            addVoltageControlledCurrentSource(
                    matrix,
                    nodeRows,
                    positiveNode,
                    negativeNode,
                    controlPositiveNode,
                    controlNegativeNode,
                    currentSource.transconductanceSiemens()
            );
        }
        for (CurrentControlledCurrentSourceStamp currentSource : component.currentControlledCurrentSources()) {
            int positiveNode = topology.nodeOf(currentSource.positivePort());
            int negativeNode = topology.nodeOf(currentSource.negativePort());
            Integer controlColumn = branchCurrentColumns.get(currentSource.controlCurrent());
            if (controlColumn == null) {
                MnaBranchCurrentColumns.addMissingDiagnostic(diagnostics, currentSource.controlCurrent(), "Branch current");
                return;
            }

            addCurrentControlledCurrentSource(
                    matrix,
                    nodeRows,
                    positiveNode,
                    negativeNode,
                    controlColumn,
                    currentSource.gain()
            );
        }

        double[] solved = unknownCount == 0 ? new double[0] : DenseLinearSolver.solve(matrix, rhs);
        if (solved == null) {
            diagnostics.add(new CircuitDiagnostic(
                    CircuitDiagnosticType.SOLVE_FAILED,
                    CircuitDiagnosticSeverity.ERROR,
                    List.of(),
                    "Linear solve failed for circuit component with " + component.nodes().size() + " nodes"
            ));
            EMcore.LOGGER.warn("Circuit component solve failed; retaining zero samples for {} nodes", component.nodes().size());
            return;
        }

        for (Map.Entry<Integer, Integer> entry : nodeRows.entrySet()) {
            nodeVoltages[entry.getKey()] = solved[entry.getValue()];
        }

        for (ConductanceStamp conductance : component.conductances()) {
            int positiveNode = topology.nodeOf(conductance.positivePort());
            int negativeNode = topology.nodeOf(conductance.negativePort());
            double voltage = nodeVoltages[positiveNode] - nodeVoltages[negativeNode];
            double current = voltage * conductance.conductanceSiemens();
            double power = voltage * current;

            addPortContribution(mutableSamples, conductance.positivePort(), nodeVoltages[positiveNode], current, power);
            addPortContribution(mutableSamples, conductance.negativePort(), nodeVoltages[negativeNode], -current, power);
        }

        for (int sourceIndex = 0; sourceIndex < component.voltageSources().size(); sourceIndex++) {
            VoltageSourceStamp source = component.voltageSources().get(sourceIndex);
            int positiveNode = topology.nodeOf(source.positivePort());
            int negativeNode = topology.nodeOf(source.negativePort());
            double current = solved[branchCurrentColumns.get(source.branchCurrent())];
            double power = source.voltageVolts() * current;

            addPortContribution(mutableSamples, source.positivePort(), nodeVoltages[positiveNode], current, power);
            addPortContribution(mutableSamples, source.negativePort(), nodeVoltages[negativeNode], -current, power);
        }

        for (int sourceIndex = 0; sourceIndex < component.voltageControlledVoltageSources().size(); sourceIndex++) {
            VoltageControlledVoltageSourceStamp source = component.voltageControlledVoltageSources().get(sourceIndex);
            int positiveNode = topology.nodeOf(source.positivePort());
            int negativeNode = topology.nodeOf(source.negativePort());
            double current = solved[branchCurrentColumns.get(source.branchCurrent())];
            double voltage = nodeVoltages[positiveNode] - nodeVoltages[negativeNode];
            double power = voltage * current;

            addPortContribution(mutableSamples, source.positivePort(), nodeVoltages[positiveNode], current, power);
            addPortContribution(mutableSamples, source.negativePort(), nodeVoltages[negativeNode], -current, power);
        }

        for (int sourceIndex = 0; sourceIndex < component.currentControlledVoltageSources().size(); sourceIndex++) {
            CurrentControlledVoltageSourceStamp source = component.currentControlledVoltageSources().get(sourceIndex);
            int positiveNode = topology.nodeOf(source.positivePort());
            int negativeNode = topology.nodeOf(source.negativePort());
            double current = solved[branchCurrentColumns.get(source.branchCurrent())];
            double voltage = nodeVoltages[positiveNode] - nodeVoltages[negativeNode];
            double power = voltage * current;

            addPortContribution(mutableSamples, source.positivePort(), nodeVoltages[positiveNode], current, power);
            addPortContribution(mutableSamples, source.negativePort(), nodeVoltages[negativeNode], -current, power);
        }

        for (CurrentSourceStamp currentSource : component.currentSources()) {
            int positiveNode = topology.nodeOf(currentSource.positivePort());
            int negativeNode = topology.nodeOf(currentSource.negativePort());
            double voltage = nodeVoltages[positiveNode] - nodeVoltages[negativeNode];
            double current = currentSource.currentAmps();
            double power = voltage * current;

            addPortContribution(mutableSamples, currentSource.positivePort(), nodeVoltages[positiveNode], current, power);
            addPortContribution(mutableSamples, currentSource.negativePort(), nodeVoltages[negativeNode], -current, power);
        }

        for (VoltageControlledCurrentSourceStamp currentSource : component.voltageControlledCurrentSources()) {
            int positiveNode = topology.nodeOf(currentSource.positivePort());
            int negativeNode = topology.nodeOf(currentSource.negativePort());
            int controlPositiveNode = topology.nodeOf(currentSource.controlPositivePort());
            int controlNegativeNode = topology.nodeOf(currentSource.controlNegativePort());
            double outputVoltage = nodeVoltages[positiveNode] - nodeVoltages[negativeNode];
            double controlVoltage = nodeVoltages[controlPositiveNode] - nodeVoltages[controlNegativeNode];
            double current = controlVoltage * currentSource.transconductanceSiemens();
            double power = outputVoltage * current;

            addPortContribution(mutableSamples, currentSource.positivePort(), nodeVoltages[positiveNode], current, power);
            addPortContribution(mutableSamples, currentSource.negativePort(), nodeVoltages[negativeNode], -current, power);
        }

        for (CurrentControlledCurrentSourceStamp currentSource : component.currentControlledCurrentSources()) {
            int positiveNode = topology.nodeOf(currentSource.positivePort());
            int negativeNode = topology.nodeOf(currentSource.negativePort());
            double outputVoltage = nodeVoltages[positiveNode] - nodeVoltages[negativeNode];
            double current = solved[branchCurrentColumns.get(currentSource.controlCurrent())] * currentSource.gain();
            double power = outputVoltage * current;

            addPortContribution(mutableSamples, currentSource.positivePort(), nodeVoltages[positiveNode], current, power);
            addPortContribution(mutableSamples, currentSource.negativePort(), nodeVoltages[negativeNode], -current, power);
        }
    }

    private static void warnIfDenseSolveIsLarge(
            ComponentTopology component,
            int unknownCount,
            List<CircuitDiagnostic> diagnostics
    ) {
        if (unknownCount < DENSE_SOLVER_WARNING_UNKNOWNS) {
            return;
        }

        diagnostics.add(new CircuitDiagnostic(
                CircuitDiagnosticType.DENSE_SOLVER_SCALE_WARNING,
                CircuitDiagnosticSeverity.WARNING,
                List.of(),
                "Dense circuit solve has " + unknownCount + " unknowns in one component; current solver is O(n^3)"
        ));
        EMcore.LOGGER.debug(
                "Dense circuit solve warning: {} unknowns across {} nodes",
                unknownCount,
                component.nodes().size()
        );
    }

    private static Map<CircuitBranchCurrent, Integer> branchCurrentColumns(
            ComponentTopology component,
            int voltageSourceOffset,
            int controlledVoltageSourceOffset,
            int currentControlledVoltageSourceOffset,
            List<CircuitDiagnostic> diagnostics
    ) {
        return MnaBranchCurrentColumns.assign(
                component.voltageSources(),
                voltageSourceOffset,
                component.voltageControlledVoltageSources(),
                controlledVoltageSourceOffset,
                component.currentControlledVoltageSources(),
                currentControlledVoltageSourceOffset,
                diagnostics,
                "Branch current"
        );
    }

    private static boolean validateVoltageSources(
            SolveTopology topology,
            ComponentTopology component,
            List<CircuitDiagnostic> diagnostics
    ) {
        boolean valid = true;

        for (VoltageSourceStamp source : component.voltageSources()) {
            int positiveNode = topology.nodeOf(source.positivePort());
            int negativeNode = topology.nodeOf(source.negativePort());
            if (positiveNode == negativeNode && Math.abs(source.voltageVolts()) > VOLTAGE_EPSILON) {
                diagnostics.add(new CircuitDiagnostic(
                        CircuitDiagnosticType.VOLTAGE_SOURCE_SHORT,
                        CircuitDiagnosticSeverity.ERROR,
                        List.of(source.positivePort(), source.negativePort()),
                        "Ideal voltage source " + source.id() + " is shorted by ideal conductors"
                ));
                valid = false;
            }
        }

        return diagnoseVoltageSourceConflicts(topology, component, diagnostics) && valid;
    }

    private static boolean diagnoseVoltageSourceConflicts(
            SolveTopology topology,
            ComponentTopology component,
            List<CircuitDiagnostic> diagnostics
    ) {
        Map<Integer, List<VoltageConstraint>> constraintsByNode = new HashMap<>();
        for (VoltageSourceStamp source : component.voltageSources()) {
            int positiveNode = topology.nodeOf(source.positivePort());
            int negativeNode = topology.nodeOf(source.negativePort());
            if (positiveNode == negativeNode) {
                continue;
            }

            constraintsByNode.computeIfAbsent(negativeNode, ignored -> new ArrayList<>())
                    .add(new VoltageConstraint(positiveNode, source.voltageVolts(), source));
            constraintsByNode.computeIfAbsent(positiveNode, ignored -> new ArrayList<>())
                    .add(new VoltageConstraint(negativeNode, -source.voltageVolts(), source));
        }

        Map<Integer, Double> potentials = new HashMap<>();
        Set<Integer> visited = new HashSet<>();
        for (int startNode : component.nodes()) {
            if (visited.contains(startNode) || !constraintsByNode.containsKey(startNode)) {
                continue;
            }

            potentials.put(startNode, 0.0);
            Deque<Integer> pending = new ArrayDeque<>();
            pending.add(startNode);

            while (!pending.isEmpty()) {
                int node = pending.removeFirst();
                if (!visited.add(node)) {
                    continue;
                }

                double basePotential = potentials.get(node);
                for (VoltageConstraint constraint : constraintsByNode.getOrDefault(node, List.of())) {
                    double expectedPotential = basePotential + constraint.deltaVolts();
                    Double existingPotential = potentials.get(constraint.node());
                    if (existingPotential == null) {
                        potentials.put(constraint.node(), expectedPotential);
                        pending.add(constraint.node());
                    } else if (Math.abs(existingPotential - expectedPotential) > VOLTAGE_EPSILON) {
                        VoltageSourceStamp source = constraint.source();
                        diagnostics.add(new CircuitDiagnostic(
                                CircuitDiagnosticType.VOLTAGE_SOURCE_CONFLICT,
                                CircuitDiagnosticSeverity.ERROR,
                                List.of(source.positivePort(), source.negativePort()),
                                "Ideal voltage sources impose inconsistent node voltages in one component"
                        ));
                        return false;
                    }
                }
            }
        }

        return true;
    }

    private static List<ComponentTopology> buildComponents(SolveTopology topology) {
        DisjointSet componentUnionFind = new DisjointSet(topology.nodeCount());
        Map<CircuitBranchCurrent, MnaTopologySupport.PortPair> branchEndpoints = MnaTopologySupport.branchEndpoints(
                topology.voltageSources(),
                topology.voltageControlledVoltageSources(),
                topology.currentControlledVoltageSources()
        );

        for (ConductanceStamp conductance : topology.conductances()) {
            MnaTopologySupport.connectNodes(
                    componentUnionFind,
                    topology,
                    conductance.positivePort(),
                    conductance.negativePort()
            );
        }
        for (CurrentSourceStamp currentSource : topology.currentSources()) {
            MnaTopologySupport.connectNodes(
                    componentUnionFind,
                    topology,
                    currentSource.positivePort(),
                    currentSource.negativePort()
            );
        }
        for (VoltageSourceStamp source : topology.voltageSources()) {
            MnaTopologySupport.connectNodes(componentUnionFind, topology, source.positivePort(), source.negativePort());
        }
        for (VoltageControlledCurrentSourceStamp source : topology.voltageControlledCurrentSources()) {
            MnaTopologySupport.connectControlledSourceNodes(
                    componentUnionFind,
                    topology,
                    source.positivePort(),
                    source.negativePort(),
                    source.controlPositivePort(),
                    source.controlNegativePort()
            );
        }
        for (VoltageControlledVoltageSourceStamp source : topology.voltageControlledVoltageSources()) {
            MnaTopologySupport.connectControlledSourceNodes(
                    componentUnionFind,
                    topology,
                    source.positivePort(),
                    source.negativePort(),
                    source.controlPositivePort(),
                    source.controlNegativePort()
            );
        }
        for (CurrentControlledCurrentSourceStamp source : topology.currentControlledCurrentSources()) {
            MnaTopologySupport.connectCurrentControlledSourceNodes(
                    componentUnionFind,
                    topology,
                    branchEndpoints,
                    source.positivePort(),
                    source.negativePort(),
                    source.controlCurrent()
            );
        }
        for (CurrentControlledVoltageSourceStamp source : topology.currentControlledVoltageSources()) {
            MnaTopologySupport.connectCurrentControlledSourceNodes(
                    componentUnionFind,
                    topology,
                    branchEndpoints,
                    source.positivePort(),
                    source.negativePort(),
                    source.controlCurrent()
            );
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

        List<List<ConductanceStamp>> conductancesByComponent = new ArrayList<>();
        List<List<CurrentSourceStamp>> currentSourcesByComponent = new ArrayList<>();
        List<List<VoltageSourceStamp>> sourcesByComponent = new ArrayList<>();
        List<List<VoltageControlledCurrentSourceStamp>> voltageControlledCurrentSourcesByComponent = new ArrayList<>();
        List<List<VoltageControlledVoltageSourceStamp>> voltageControlledVoltageSourcesByComponent = new ArrayList<>();
        List<List<CurrentControlledCurrentSourceStamp>> currentControlledCurrentSourcesByComponent = new ArrayList<>();
        List<List<CurrentControlledVoltageSourceStamp>> currentControlledVoltageSourcesByComponent = new ArrayList<>();
        for (int index = 0; index < nodesByComponent.size(); index++) {
            conductancesByComponent.add(new ArrayList<>());
            currentSourcesByComponent.add(new ArrayList<>());
            sourcesByComponent.add(new ArrayList<>());
            voltageControlledCurrentSourcesByComponent.add(new ArrayList<>());
            voltageControlledVoltageSourcesByComponent.add(new ArrayList<>());
            currentControlledCurrentSourcesByComponent.add(new ArrayList<>());
            currentControlledVoltageSourcesByComponent.add(new ArrayList<>());
        }

        for (ConductanceStamp conductance : topology.conductances()) {
            conductancesByComponent.get(componentByNode[topology.nodeOf(conductance.positivePort())]).add(conductance);
        }
        for (CurrentSourceStamp currentSource : topology.currentSources()) {
            currentSourcesByComponent.get(componentByNode[topology.nodeOf(currentSource.positivePort())]).add(currentSource);
        }
        for (VoltageSourceStamp source : topology.voltageSources()) {
            sourcesByComponent.get(componentByNode[topology.nodeOf(source.positivePort())]).add(source);
        }
        for (VoltageControlledCurrentSourceStamp source : topology.voltageControlledCurrentSources()) {
            voltageControlledCurrentSourcesByComponent
                    .get(componentByNode[topology.nodeOf(source.positivePort())])
                    .add(source);
        }
        for (VoltageControlledVoltageSourceStamp source : topology.voltageControlledVoltageSources()) {
            voltageControlledVoltageSourcesByComponent
                    .get(componentByNode[topology.nodeOf(source.positivePort())])
                    .add(source);
        }
        for (CurrentControlledCurrentSourceStamp source : topology.currentControlledCurrentSources()) {
            currentControlledCurrentSourcesByComponent
                    .get(componentByNode[topology.nodeOf(source.positivePort())])
                    .add(source);
        }
        for (CurrentControlledVoltageSourceStamp source : topology.currentControlledVoltageSources()) {
            currentControlledVoltageSourcesByComponent
                    .get(componentByNode[topology.nodeOf(source.positivePort())])
                    .add(source);
        }

        List<ComponentTopology> components = new ArrayList<>();
        for (int index = 0; index < nodesByComponent.size(); index++) {
            components.add(new ComponentTopology(
                    List.copyOf(nodesByComponent.get(index)),
                    List.copyOf(conductancesByComponent.get(index)),
                    List.copyOf(currentSourcesByComponent.get(index)),
                    List.copyOf(sourcesByComponent.get(index)),
                    List.copyOf(voltageControlledCurrentSourcesByComponent.get(index)),
                    List.copyOf(voltageControlledVoltageSourcesByComponent.get(index)),
                    List.copyOf(currentControlledCurrentSourcesByComponent.get(index)),
                    List.copyOf(currentControlledVoltageSourcesByComponent.get(index))
            ));
        }
        return components;
    }

    private static int chooseGroundNode(SolveTopology topology, ComponentTopology component) {
        for (VoltageSourceStamp source : component.voltageSources()) {
            return topology.nodeOf(source.negativePort());
        }
        for (VoltageControlledVoltageSourceStamp source : component.voltageControlledVoltageSources()) {
            return topology.nodeOf(source.negativePort());
        }
        for (CurrentControlledVoltageSourceStamp source : component.currentControlledVoltageSources()) {
            return topology.nodeOf(source.negativePort());
        }

        return component.nodes().getFirst();
    }

    private static SolveResult finish(
            List<CircuitNode> solvedNodes,
            List<CircuitDiagnostic> solvedDiagnostics,
            Map<CircuitPort, MutableSample> mutableSamples
    ) {
        Map<CircuitPort, CircuitSample> solvedSamples = new LinkedHashMap<>();
        for (MutableSample sample : mutableSamples.values()) {
            solvedSamples.put(sample.port, sample.toSample());
        }
        return new SolveResult(solvedNodes, List.copyOf(solvedDiagnostics), solvedSamples);
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

    private static void addVoltageControlledVoltageSource(
            double[][] matrix,
            Map<Integer, Integer> nodeRows,
            int positiveNode,
            int negativeNode,
            int controlPositiveNode,
            int controlNegativeNode,
            int sourceColumn,
            double gain
    ) {
        int positiveRow = rowOf(nodeRows, positiveNode);
        int negativeRow = rowOf(nodeRows, negativeNode);
        int controlPositiveRow = rowOf(nodeRows, controlPositiveNode);
        int controlNegativeRow = rowOf(nodeRows, controlNegativeNode);

        if (positiveRow >= 0) {
            matrix[positiveRow][sourceColumn] += 1.0;
            matrix[sourceColumn][positiveRow] += 1.0;
        }
        if (negativeRow >= 0) {
            matrix[negativeRow][sourceColumn] -= 1.0;
            matrix[sourceColumn][negativeRow] -= 1.0;
        }

        addMatrixTerm(matrix, sourceColumn, controlPositiveRow, -gain);
        addMatrixTerm(matrix, sourceColumn, controlNegativeRow, gain);
    }

    private static void addCurrentControlledVoltageSource(
            double[][] matrix,
            Map<Integer, Integer> nodeRows,
            int positiveNode,
            int negativeNode,
            int sourceColumn,
            int controlColumn,
            double transresistance
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

        matrix[sourceColumn][controlColumn] -= transresistance;
    }

    private static void addCurrentSource(
            double[] rhs,
            Map<Integer, Integer> nodeRows,
            int positiveNode,
            int negativeNode,
            double current
    ) {
        int positiveRow = rowOf(nodeRows, positiveNode);
        int negativeRow = rowOf(nodeRows, negativeNode);

        if (positiveRow >= 0) {
            rhs[positiveRow] -= current;
        }
        if (negativeRow >= 0) {
            rhs[negativeRow] += current;
        }
    }

    private static void addVoltageControlledCurrentSource(
            double[][] matrix,
            Map<Integer, Integer> nodeRows,
            int positiveNode,
            int negativeNode,
            int controlPositiveNode,
            int controlNegativeNode,
            double transconductance
    ) {
        int positiveRow = rowOf(nodeRows, positiveNode);
        int negativeRow = rowOf(nodeRows, negativeNode);
        int controlPositiveRow = rowOf(nodeRows, controlPositiveNode);
        int controlNegativeRow = rowOf(nodeRows, controlNegativeNode);

        addMatrixTerm(matrix, positiveRow, controlPositiveRow, transconductance);
        addMatrixTerm(matrix, positiveRow, controlNegativeRow, -transconductance);
        addMatrixTerm(matrix, negativeRow, controlPositiveRow, -transconductance);
        addMatrixTerm(matrix, negativeRow, controlNegativeRow, transconductance);
    }

    private static void addCurrentControlledCurrentSource(
            double[][] matrix,
            Map<Integer, Integer> nodeRows,
            int positiveNode,
            int negativeNode,
            int controlColumn,
            double gain
    ) {
        int positiveRow = rowOf(nodeRows, positiveNode);
        int negativeRow = rowOf(nodeRows, negativeNode);

        addMatrixTerm(matrix, positiveRow, controlColumn, gain);
        addMatrixTerm(matrix, negativeRow, controlColumn, -gain);
    }

    private static void addMatrixTerm(double[][] matrix, int row, int column, double value) {
        if (row >= 0 && column >= 0) {
            matrix[row][column] += value;
        }
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

    private record ComponentTopology(
            List<Integer> nodes,
            List<ConductanceStamp> conductances,
            List<CurrentSourceStamp> currentSources,
            List<VoltageSourceStamp> voltageSources,
            List<VoltageControlledCurrentSourceStamp> voltageControlledCurrentSources,
            List<VoltageControlledVoltageSourceStamp> voltageControlledVoltageSources,
            List<CurrentControlledCurrentSourceStamp> currentControlledCurrentSources,
            List<CurrentControlledVoltageSourceStamp> currentControlledVoltageSources
    ) {
    }

    private record VoltageConstraint(int node, double deltaVolts, VoltageSourceStamp source) {
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

}
