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
import com.firedoge.emcore.api.circuit.AcCircuitSample;
import com.firedoge.emcore.api.circuit.CircuitBranchCurrent;
import com.firedoge.emcore.api.circuit.CircuitDiagnostic;
import com.firedoge.emcore.api.circuit.CircuitDiagnosticSeverity;
import com.firedoge.emcore.api.circuit.CircuitDiagnosticType;
import com.firedoge.emcore.api.circuit.CircuitNode;
import com.firedoge.emcore.api.circuit.CircuitPort;
import com.firedoge.emcore.internal.circuit.CircuitNetwork.AcAdmittanceStamp;
import com.firedoge.emcore.internal.circuit.CircuitNetwork.AcCurrentControlledCurrentSourceStamp;
import com.firedoge.emcore.internal.circuit.CircuitNetwork.AcCurrentControlledVoltageSourceStamp;
import com.firedoge.emcore.internal.circuit.CircuitNetwork.AcCurrentSourceStamp;
import com.firedoge.emcore.internal.circuit.CircuitNetwork.AcSolveResult;
import com.firedoge.emcore.internal.circuit.CircuitNetwork.AcSolveTopology;
import com.firedoge.emcore.internal.circuit.CircuitNetwork.AcVoltageControlledCurrentSourceStamp;
import com.firedoge.emcore.internal.circuit.CircuitNetwork.AcVoltageControlledVoltageSourceStamp;
import com.firedoge.emcore.internal.circuit.CircuitNetwork.AcVoltageSourceStamp;

final class AcMnaSolver {
    private static final double VOLTAGE_EPSILON = 1.0e-9;
    private static final int DENSE_SOLVER_WARNING_UNKNOWNS = 128;

    private AcMnaSolver() {
    }

    static AcSolveResult solve(AcSolveTopology topology, List<CircuitNode> solvedNodes) {
        int nodeCount = topology.nodeCount();
        Map<CircuitPort, MutableAcSample> mutableSamples = new LinkedHashMap<>();
        List<CircuitDiagnostic> solvedDiagnostics = new ArrayList<>(topology.diagnostics());

        for (CircuitPort port : topology.ports()) {
            mutableSamples.put(port, new MutableAcSample(port));
        }

        if (nodeCount == 0) {
            return finish(solvedNodes, solvedDiagnostics, mutableSamples);
        }

        Complex[] nodeVoltages = newZeroVector(nodeCount);
        for (ComponentTopology component : buildComponents(topology)) {
            solveComponent(topology, component, nodeVoltages, mutableSamples, solvedDiagnostics);
        }

        for (CircuitPort port : topology.ports()) {
            MutableAcSample sample = mutableSamples.get(port);
            sample.voltageVolts = nodeVoltages[topology.nodeOf(port)];
        }

        return finish(solvedNodes, solvedDiagnostics, mutableSamples);
    }

    private static void solveComponent(
            AcSolveTopology topology,
            ComponentTopology component,
            Complex[] nodeVoltages,
            Map<CircuitPort, MutableAcSample> mutableSamples,
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

        Complex[][] matrix = newZeroMatrix(unknownCount);
        Complex[] rhs = newZeroVector(unknownCount);
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

        for (AcAdmittanceStamp admittance : component.admittances()) {
            int positiveNode = topology.nodeOf(admittance.positivePort());
            int negativeNode = topology.nodeOf(admittance.negativePort());
            addAdmittance(matrix, nodeRows, positiveNode, negativeNode, admittance.admittanceSiemens());
        }

        for (AcVoltageSourceStamp source : component.voltageSources()) {
            int positiveNode = topology.nodeOf(source.positivePort());
            int negativeNode = topology.nodeOf(source.negativePort());
            int sourceColumn = branchCurrentColumns.get(source.branchCurrent());
            addVoltageSource(matrix, rhs, nodeRows, positiveNode, negativeNode, sourceColumn, source.voltageVolts());
        }

        for (AcVoltageControlledVoltageSourceStamp source : component.voltageControlledVoltageSources()) {
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

        for (AcCurrentControlledVoltageSourceStamp source : component.currentControlledVoltageSources()) {
            int positiveNode = topology.nodeOf(source.positivePort());
            int negativeNode = topology.nodeOf(source.negativePort());
            int sourceColumn = branchCurrentColumns.get(source.branchCurrent());
            Integer controlColumn = branchCurrentColumns.get(source.controlCurrent());
            if (controlColumn == null) {
                MnaBranchCurrentColumns.addMissingDiagnostic(diagnostics, source.controlCurrent(), "AC branch current");
                return;
            }

            addCurrentControlledVoltageSource(
                    matrix,
                    nodeRows,
                    positiveNode,
                    negativeNode,
                    sourceColumn,
                    controlColumn,
                    source.transimpedanceOhms()
            );
        }

        for (AcCurrentSourceStamp currentSource : component.currentSources()) {
            int positiveNode = topology.nodeOf(currentSource.positivePort());
            int negativeNode = topology.nodeOf(currentSource.negativePort());
            addCurrentSource(rhs, nodeRows, positiveNode, negativeNode, currentSource.currentAmps());
        }
        for (AcVoltageControlledCurrentSourceStamp currentSource : component.voltageControlledCurrentSources()) {
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
        for (AcCurrentControlledCurrentSourceStamp currentSource : component.currentControlledCurrentSources()) {
            int positiveNode = topology.nodeOf(currentSource.positivePort());
            int negativeNode = topology.nodeOf(currentSource.negativePort());
            Integer controlColumn = branchCurrentColumns.get(currentSource.controlCurrent());
            if (controlColumn == null) {
                MnaBranchCurrentColumns.addMissingDiagnostic(
                        diagnostics,
                        currentSource.controlCurrent(),
                        "AC branch current"
                );
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

        Complex[] solved = unknownCount == 0 ? new Complex[0] : DenseLinearSolver.solve(matrix, rhs);
        if (solved == null) {
            diagnostics.add(new CircuitDiagnostic(
                    CircuitDiagnosticType.SOLVE_FAILED,
                    CircuitDiagnosticSeverity.ERROR,
                    List.of(),
                    "AC linear solve failed for circuit component with " + component.nodes().size() + " nodes"
            ));
            EMcore.LOGGER.warn(
                    "AC circuit component solve failed; retaining zero samples for {} nodes",
                    component.nodes().size()
            );
            return;
        }

        for (Map.Entry<Integer, Integer> entry : nodeRows.entrySet()) {
            nodeVoltages[entry.getKey()] = solved[entry.getValue()];
        }

        for (AcAdmittanceStamp admittance : component.admittances()) {
            int positiveNode = topology.nodeOf(admittance.positivePort());
            int negativeNode = topology.nodeOf(admittance.negativePort());
            Complex voltage = nodeVoltages[positiveNode].subtract(nodeVoltages[negativeNode]);
            Complex current = voltage.multiply(admittance.admittanceSiemens());
            Complex power = voltage.multiply(current.conjugate());

            addPortContribution(mutableSamples, admittance.positivePort(), nodeVoltages[positiveNode], current, power);
            addPortContribution(mutableSamples, admittance.negativePort(), nodeVoltages[negativeNode], current.negate(), power);
        }

        for (AcVoltageSourceStamp source : component.voltageSources()) {
            int positiveNode = topology.nodeOf(source.positivePort());
            int negativeNode = topology.nodeOf(source.negativePort());
            Complex current = solved[branchCurrentColumns.get(source.branchCurrent())];
            Complex power = source.voltageVolts().multiply(current.conjugate());

            addPortContribution(mutableSamples, source.positivePort(), nodeVoltages[positiveNode], current, power);
            addPortContribution(mutableSamples, source.negativePort(), nodeVoltages[negativeNode], current.negate(), power);
        }

        for (AcVoltageControlledVoltageSourceStamp source : component.voltageControlledVoltageSources()) {
            int positiveNode = topology.nodeOf(source.positivePort());
            int negativeNode = topology.nodeOf(source.negativePort());
            Complex current = solved[branchCurrentColumns.get(source.branchCurrent())];
            Complex voltage = nodeVoltages[positiveNode].subtract(nodeVoltages[negativeNode]);
            Complex power = voltage.multiply(current.conjugate());

            addPortContribution(mutableSamples, source.positivePort(), nodeVoltages[positiveNode], current, power);
            addPortContribution(mutableSamples, source.negativePort(), nodeVoltages[negativeNode], current.negate(), power);
        }

        for (AcCurrentControlledVoltageSourceStamp source : component.currentControlledVoltageSources()) {
            int positiveNode = topology.nodeOf(source.positivePort());
            int negativeNode = topology.nodeOf(source.negativePort());
            Complex current = solved[branchCurrentColumns.get(source.branchCurrent())];
            Complex voltage = nodeVoltages[positiveNode].subtract(nodeVoltages[negativeNode]);
            Complex power = voltage.multiply(current.conjugate());

            addPortContribution(mutableSamples, source.positivePort(), nodeVoltages[positiveNode], current, power);
            addPortContribution(mutableSamples, source.negativePort(), nodeVoltages[negativeNode], current.negate(), power);
        }

        for (AcCurrentSourceStamp currentSource : component.currentSources()) {
            int positiveNode = topology.nodeOf(currentSource.positivePort());
            int negativeNode = topology.nodeOf(currentSource.negativePort());
            Complex voltage = nodeVoltages[positiveNode].subtract(nodeVoltages[negativeNode]);
            Complex current = currentSource.currentAmps();
            Complex power = voltage.multiply(current.conjugate());

            addPortContribution(mutableSamples, currentSource.positivePort(), nodeVoltages[positiveNode], current, power);
            addPortContribution(mutableSamples, currentSource.negativePort(), nodeVoltages[negativeNode], current.negate(), power);
        }

        for (AcVoltageControlledCurrentSourceStamp currentSource : component.voltageControlledCurrentSources()) {
            int positiveNode = topology.nodeOf(currentSource.positivePort());
            int negativeNode = topology.nodeOf(currentSource.negativePort());
            int controlPositiveNode = topology.nodeOf(currentSource.controlPositivePort());
            int controlNegativeNode = topology.nodeOf(currentSource.controlNegativePort());
            Complex outputVoltage = nodeVoltages[positiveNode].subtract(nodeVoltages[negativeNode]);
            Complex controlVoltage = nodeVoltages[controlPositiveNode].subtract(nodeVoltages[controlNegativeNode]);
            Complex current = controlVoltage.multiply(currentSource.transconductanceSiemens());
            Complex power = outputVoltage.multiply(current.conjugate());

            addPortContribution(mutableSamples, currentSource.positivePort(), nodeVoltages[positiveNode], current, power);
            addPortContribution(mutableSamples, currentSource.negativePort(), nodeVoltages[negativeNode], current.negate(), power);
        }

        for (AcCurrentControlledCurrentSourceStamp currentSource : component.currentControlledCurrentSources()) {
            int positiveNode = topology.nodeOf(currentSource.positivePort());
            int negativeNode = topology.nodeOf(currentSource.negativePort());
            Complex outputVoltage = nodeVoltages[positiveNode].subtract(nodeVoltages[negativeNode]);
            Complex current = solved[branchCurrentColumns.get(currentSource.controlCurrent())].multiply(currentSource.gain());
            Complex power = outputVoltage.multiply(current.conjugate());

            addPortContribution(mutableSamples, currentSource.positivePort(), nodeVoltages[positiveNode], current, power);
            addPortContribution(mutableSamples, currentSource.negativePort(), nodeVoltages[negativeNode], current.negate(), power);
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
                "Dense AC circuit solve has " + unknownCount + " unknowns in one component; current solver is O(n^3)"
        ));
        EMcore.LOGGER.debug(
                "Dense AC circuit solve warning: {} unknowns across {} nodes",
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
                "AC branch current"
        );
    }

    private static boolean validateVoltageSources(
            AcSolveTopology topology,
            ComponentTopology component,
            List<CircuitDiagnostic> diagnostics
    ) {
        boolean valid = true;

        for (AcVoltageSourceStamp source : component.voltageSources()) {
            int positiveNode = topology.nodeOf(source.positivePort());
            int negativeNode = topology.nodeOf(source.negativePort());
            if (positiveNode == negativeNode && source.voltageVolts().abs() > VOLTAGE_EPSILON) {
                diagnostics.add(new CircuitDiagnostic(
                        CircuitDiagnosticType.VOLTAGE_SOURCE_SHORT,
                        CircuitDiagnosticSeverity.ERROR,
                        List.of(source.positivePort(), source.negativePort()),
                        "Ideal AC voltage source " + source.id() + " is shorted by ideal conductors"
                ));
                valid = false;
            }
        }

        return diagnoseVoltageSourceConflicts(topology, component, diagnostics) && valid;
    }

    private static boolean diagnoseVoltageSourceConflicts(
            AcSolveTopology topology,
            ComponentTopology component,
            List<CircuitDiagnostic> diagnostics
    ) {
        Map<Integer, List<VoltageConstraint>> constraintsByNode = new HashMap<>();
        for (AcVoltageSourceStamp source : component.voltageSources()) {
            int positiveNode = topology.nodeOf(source.positivePort());
            int negativeNode = topology.nodeOf(source.negativePort());
            if (positiveNode == negativeNode) {
                continue;
            }

            constraintsByNode.computeIfAbsent(negativeNode, ignored -> new ArrayList<>())
                    .add(new VoltageConstraint(positiveNode, source.voltageVolts(), source));
            constraintsByNode.computeIfAbsent(positiveNode, ignored -> new ArrayList<>())
                    .add(new VoltageConstraint(negativeNode, source.voltageVolts().negate(), source));
        }

        Map<Integer, Complex> potentials = new HashMap<>();
        Set<Integer> visited = new HashSet<>();
        for (int startNode : component.nodes()) {
            if (visited.contains(startNode) || !constraintsByNode.containsKey(startNode)) {
                continue;
            }

            potentials.put(startNode, Complex.ZERO);
            Deque<Integer> pending = new ArrayDeque<>();
            pending.add(startNode);

            while (!pending.isEmpty()) {
                int node = pending.removeFirst();
                if (!visited.add(node)) {
                    continue;
                }

                Complex basePotential = potentials.get(node);
                for (VoltageConstraint constraint : constraintsByNode.getOrDefault(node, List.of())) {
                    Complex expectedPotential = basePotential.add(constraint.deltaVolts());
                    Complex existingPotential = potentials.get(constraint.node());
                    if (existingPotential == null) {
                        potentials.put(constraint.node(), expectedPotential);
                        pending.add(constraint.node());
                    } else if (existingPotential.subtract(expectedPotential).abs() > VOLTAGE_EPSILON) {
                        AcVoltageSourceStamp source = constraint.source();
                        diagnostics.add(new CircuitDiagnostic(
                                CircuitDiagnosticType.VOLTAGE_SOURCE_CONFLICT,
                                CircuitDiagnosticSeverity.ERROR,
                                List.of(source.positivePort(), source.negativePort()),
                                "Ideal AC voltage sources impose inconsistent node voltages in one component"
                        ));
                        return false;
                    }
                }
            }
        }

        return true;
    }

    private static List<ComponentTopology> buildComponents(AcSolveTopology topology) {
        DisjointSet componentUnionFind = new DisjointSet(topology.nodeCount());
        Map<CircuitBranchCurrent, MnaTopologySupport.PortPair> branchEndpoints = MnaTopologySupport.branchEndpoints(
                topology.voltageSources(),
                topology.voltageControlledVoltageSources(),
                topology.currentControlledVoltageSources()
        );

        for (AcAdmittanceStamp admittance : topology.admittances()) {
            MnaTopologySupport.connectNodes(
                    componentUnionFind,
                    topology,
                    admittance.positivePort(),
                    admittance.negativePort()
            );
        }
        for (AcCurrentSourceStamp currentSource : topology.currentSources()) {
            MnaTopologySupport.connectNodes(
                    componentUnionFind,
                    topology,
                    currentSource.positivePort(),
                    currentSource.negativePort()
            );
        }
        for (AcVoltageSourceStamp source : topology.voltageSources()) {
            MnaTopologySupport.connectNodes(componentUnionFind, topology, source.positivePort(), source.negativePort());
        }
        for (AcVoltageControlledCurrentSourceStamp source : topology.voltageControlledCurrentSources()) {
            MnaTopologySupport.connectControlledSourceNodes(
                    componentUnionFind,
                    topology,
                    source.positivePort(),
                    source.negativePort(),
                    source.controlPositivePort(),
                    source.controlNegativePort()
            );
        }
        for (AcVoltageControlledVoltageSourceStamp source : topology.voltageControlledVoltageSources()) {
            MnaTopologySupport.connectControlledSourceNodes(
                    componentUnionFind,
                    topology,
                    source.positivePort(),
                    source.negativePort(),
                    source.controlPositivePort(),
                    source.controlNegativePort()
            );
        }
        for (AcCurrentControlledCurrentSourceStamp source : topology.currentControlledCurrentSources()) {
            MnaTopologySupport.connectCurrentControlledSourceNodes(
                    componentUnionFind,
                    topology,
                    branchEndpoints,
                    source.positivePort(),
                    source.negativePort(),
                    source.controlCurrent()
            );
        }
        for (AcCurrentControlledVoltageSourceStamp source : topology.currentControlledVoltageSources()) {
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

        List<List<AcAdmittanceStamp>> admittancesByComponent = new ArrayList<>();
        List<List<AcCurrentSourceStamp>> currentSourcesByComponent = new ArrayList<>();
        List<List<AcVoltageSourceStamp>> sourcesByComponent = new ArrayList<>();
        List<List<AcVoltageControlledCurrentSourceStamp>> voltageControlledCurrentSourcesByComponent = new ArrayList<>();
        List<List<AcVoltageControlledVoltageSourceStamp>> voltageControlledVoltageSourcesByComponent = new ArrayList<>();
        List<List<AcCurrentControlledCurrentSourceStamp>> currentControlledCurrentSourcesByComponent = new ArrayList<>();
        List<List<AcCurrentControlledVoltageSourceStamp>> currentControlledVoltageSourcesByComponent = new ArrayList<>();
        for (int index = 0; index < nodesByComponent.size(); index++) {
            admittancesByComponent.add(new ArrayList<>());
            currentSourcesByComponent.add(new ArrayList<>());
            sourcesByComponent.add(new ArrayList<>());
            voltageControlledCurrentSourcesByComponent.add(new ArrayList<>());
            voltageControlledVoltageSourcesByComponent.add(new ArrayList<>());
            currentControlledCurrentSourcesByComponent.add(new ArrayList<>());
            currentControlledVoltageSourcesByComponent.add(new ArrayList<>());
        }

        for (AcAdmittanceStamp admittance : topology.admittances()) {
            admittancesByComponent.get(componentByNode[topology.nodeOf(admittance.positivePort())]).add(admittance);
        }
        for (AcCurrentSourceStamp currentSource : topology.currentSources()) {
            currentSourcesByComponent.get(componentByNode[topology.nodeOf(currentSource.positivePort())])
                    .add(currentSource);
        }
        for (AcVoltageSourceStamp source : topology.voltageSources()) {
            sourcesByComponent.get(componentByNode[topology.nodeOf(source.positivePort())]).add(source);
        }
        for (AcVoltageControlledCurrentSourceStamp source : topology.voltageControlledCurrentSources()) {
            voltageControlledCurrentSourcesByComponent
                    .get(componentByNode[topology.nodeOf(source.positivePort())])
                    .add(source);
        }
        for (AcVoltageControlledVoltageSourceStamp source : topology.voltageControlledVoltageSources()) {
            voltageControlledVoltageSourcesByComponent
                    .get(componentByNode[topology.nodeOf(source.positivePort())])
                    .add(source);
        }
        for (AcCurrentControlledCurrentSourceStamp source : topology.currentControlledCurrentSources()) {
            currentControlledCurrentSourcesByComponent
                    .get(componentByNode[topology.nodeOf(source.positivePort())])
                    .add(source);
        }
        for (AcCurrentControlledVoltageSourceStamp source : topology.currentControlledVoltageSources()) {
            currentControlledVoltageSourcesByComponent
                    .get(componentByNode[topology.nodeOf(source.positivePort())])
                    .add(source);
        }

        List<ComponentTopology> components = new ArrayList<>();
        for (int index = 0; index < nodesByComponent.size(); index++) {
            components.add(new ComponentTopology(
                    List.copyOf(nodesByComponent.get(index)),
                    List.copyOf(admittancesByComponent.get(index)),
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

    private static int chooseGroundNode(AcSolveTopology topology, ComponentTopology component) {
        for (AcVoltageSourceStamp source : component.voltageSources()) {
            return topology.nodeOf(source.negativePort());
        }
        for (AcVoltageControlledVoltageSourceStamp source : component.voltageControlledVoltageSources()) {
            return topology.nodeOf(source.negativePort());
        }
        for (AcCurrentControlledVoltageSourceStamp source : component.currentControlledVoltageSources()) {
            return topology.nodeOf(source.negativePort());
        }

        return component.nodes().getFirst();
    }

    private static AcSolveResult finish(
            List<CircuitNode> solvedNodes,
            List<CircuitDiagnostic> solvedDiagnostics,
            Map<CircuitPort, MutableAcSample> mutableSamples
    ) {
        List<AcCircuitSample> solvedSamples = new ArrayList<>();
        for (MutableAcSample sample : mutableSamples.values()) {
            solvedSamples.add(sample.toSample());
        }
        return new AcSolveResult(solvedNodes, List.copyOf(solvedDiagnostics), List.copyOf(solvedSamples));
    }

    private static void addAdmittance(
            Complex[][] matrix,
            Map<Integer, Integer> nodeRows,
            int positiveNode,
            int negativeNode,
            Complex admittance
    ) {
        int positiveRow = rowOf(nodeRows, positiveNode);
        int negativeRow = rowOf(nodeRows, negativeNode);

        if (positiveRow >= 0) {
            matrix[positiveRow][positiveRow] = matrix[positiveRow][positiveRow].add(admittance);
        }
        if (negativeRow >= 0) {
            matrix[negativeRow][negativeRow] = matrix[negativeRow][negativeRow].add(admittance);
        }
        if (positiveRow >= 0 && negativeRow >= 0) {
            matrix[positiveRow][negativeRow] = matrix[positiveRow][negativeRow].subtract(admittance);
            matrix[negativeRow][positiveRow] = matrix[negativeRow][positiveRow].subtract(admittance);
        }
    }

    private static void addVoltageSource(
            Complex[][] matrix,
            Complex[] rhs,
            Map<Integer, Integer> nodeRows,
            int positiveNode,
            int negativeNode,
            int sourceColumn,
            Complex voltage
    ) {
        int positiveRow = rowOf(nodeRows, positiveNode);
        int negativeRow = rowOf(nodeRows, negativeNode);

        if (positiveRow >= 0) {
            matrix[positiveRow][sourceColumn] = matrix[positiveRow][sourceColumn].add(Complex.ONE);
            matrix[sourceColumn][positiveRow] = matrix[sourceColumn][positiveRow].add(Complex.ONE);
        }
        if (negativeRow >= 0) {
            matrix[negativeRow][sourceColumn] = matrix[negativeRow][sourceColumn].subtract(Complex.ONE);
            matrix[sourceColumn][negativeRow] = matrix[sourceColumn][negativeRow].subtract(Complex.ONE);
        }

        rhs[sourceColumn] = voltage;
    }

    private static void addVoltageControlledVoltageSource(
            Complex[][] matrix,
            Map<Integer, Integer> nodeRows,
            int positiveNode,
            int negativeNode,
            int controlPositiveNode,
            int controlNegativeNode,
            int sourceColumn,
            Complex gain
    ) {
        int positiveRow = rowOf(nodeRows, positiveNode);
        int negativeRow = rowOf(nodeRows, negativeNode);
        int controlPositiveRow = rowOf(nodeRows, controlPositiveNode);
        int controlNegativeRow = rowOf(nodeRows, controlNegativeNode);

        if (positiveRow >= 0) {
            matrix[positiveRow][sourceColumn] = matrix[positiveRow][sourceColumn].add(Complex.ONE);
            matrix[sourceColumn][positiveRow] = matrix[sourceColumn][positiveRow].add(Complex.ONE);
        }
        if (negativeRow >= 0) {
            matrix[negativeRow][sourceColumn] = matrix[negativeRow][sourceColumn].subtract(Complex.ONE);
            matrix[sourceColumn][negativeRow] = matrix[sourceColumn][negativeRow].subtract(Complex.ONE);
        }

        addMatrixTerm(matrix, sourceColumn, controlPositiveRow, gain.negate());
        addMatrixTerm(matrix, sourceColumn, controlNegativeRow, gain);
    }

    private static void addCurrentControlledVoltageSource(
            Complex[][] matrix,
            Map<Integer, Integer> nodeRows,
            int positiveNode,
            int negativeNode,
            int sourceColumn,
            int controlColumn,
            Complex transimpedance
    ) {
        int positiveRow = rowOf(nodeRows, positiveNode);
        int negativeRow = rowOf(nodeRows, negativeNode);

        if (positiveRow >= 0) {
            matrix[positiveRow][sourceColumn] = matrix[positiveRow][sourceColumn].add(Complex.ONE);
            matrix[sourceColumn][positiveRow] = matrix[sourceColumn][positiveRow].add(Complex.ONE);
        }
        if (negativeRow >= 0) {
            matrix[negativeRow][sourceColumn] = matrix[negativeRow][sourceColumn].subtract(Complex.ONE);
            matrix[sourceColumn][negativeRow] = matrix[sourceColumn][negativeRow].subtract(Complex.ONE);
        }

        addMatrixTerm(matrix, sourceColumn, controlColumn, transimpedance.negate());
    }

    private static void addCurrentSource(
            Complex[] rhs,
            Map<Integer, Integer> nodeRows,
            int positiveNode,
            int negativeNode,
            Complex current
    ) {
        int positiveRow = rowOf(nodeRows, positiveNode);
        int negativeRow = rowOf(nodeRows, negativeNode);

        if (positiveRow >= 0) {
            rhs[positiveRow] = rhs[positiveRow].subtract(current);
        }
        if (negativeRow >= 0) {
            rhs[negativeRow] = rhs[negativeRow].add(current);
        }
    }

    private static void addVoltageControlledCurrentSource(
            Complex[][] matrix,
            Map<Integer, Integer> nodeRows,
            int positiveNode,
            int negativeNode,
            int controlPositiveNode,
            int controlNegativeNode,
            Complex transconductance
    ) {
        int positiveRow = rowOf(nodeRows, positiveNode);
        int negativeRow = rowOf(nodeRows, negativeNode);
        int controlPositiveRow = rowOf(nodeRows, controlPositiveNode);
        int controlNegativeRow = rowOf(nodeRows, controlNegativeNode);

        addMatrixTerm(matrix, positiveRow, controlPositiveRow, transconductance);
        addMatrixTerm(matrix, positiveRow, controlNegativeRow, transconductance.negate());
        addMatrixTerm(matrix, negativeRow, controlPositiveRow, transconductance.negate());
        addMatrixTerm(matrix, negativeRow, controlNegativeRow, transconductance);
    }

    private static void addCurrentControlledCurrentSource(
            Complex[][] matrix,
            Map<Integer, Integer> nodeRows,
            int positiveNode,
            int negativeNode,
            int controlColumn,
            Complex gain
    ) {
        int positiveRow = rowOf(nodeRows, positiveNode);
        int negativeRow = rowOf(nodeRows, negativeNode);

        addMatrixTerm(matrix, positiveRow, controlColumn, gain);
        addMatrixTerm(matrix, negativeRow, controlColumn, gain.negate());
    }

    private static void addMatrixTerm(Complex[][] matrix, int row, int column, Complex value) {
        if (row >= 0 && column >= 0) {
            matrix[row][column] = matrix[row][column].add(value);
        }
    }

    private static int rowOf(Map<Integer, Integer> nodeRows, int node) {
        return nodeRows.getOrDefault(node, -1);
    }

    private static void addPortContribution(
            Map<CircuitPort, MutableAcSample> samples,
            CircuitPort port,
            Complex voltage,
            Complex current,
            Complex power
    ) {
        MutableAcSample sample = samples.get(port);
        if (sample == null) {
            return;
        }

        sample.voltageVolts = voltage;
        sample.currentAmps = sample.currentAmps.add(current);
        sample.complexPowerVoltAmps = sample.complexPowerVoltAmps.add(power);
    }

    private static Complex[][] newZeroMatrix(int size) {
        Complex[][] matrix = new Complex[size][size];
        for (int row = 0; row < size; row++) {
            for (int column = 0; column < size; column++) {
                matrix[row][column] = Complex.ZERO;
            }
        }
        return matrix;
    }

    private static Complex[] newZeroVector(int size) {
        Complex[] vector = new Complex[size];
        for (int index = 0; index < size; index++) {
            vector[index] = Complex.ZERO;
        }
        return vector;
    }

    private record ComponentTopology(
            List<Integer> nodes,
            List<AcAdmittanceStamp> admittances,
            List<AcCurrentSourceStamp> currentSources,
            List<AcVoltageSourceStamp> voltageSources,
            List<AcVoltageControlledCurrentSourceStamp> voltageControlledCurrentSources,
            List<AcVoltageControlledVoltageSourceStamp> voltageControlledVoltageSources,
            List<AcCurrentControlledCurrentSourceStamp> currentControlledCurrentSources,
            List<AcCurrentControlledVoltageSourceStamp> currentControlledVoltageSources
    ) {
    }

    private record VoltageConstraint(int node, Complex deltaVolts, AcVoltageSourceStamp source) {
    }

    private static final class MutableAcSample {
        private final CircuitPort port;
        private Complex voltageVolts = Complex.ZERO;
        private Complex currentAmps = Complex.ZERO;
        private Complex complexPowerVoltAmps = Complex.ZERO;

        private MutableAcSample(CircuitPort port) {
            this.port = port;
        }

        private AcCircuitSample toSample() {
            return new AcCircuitSample(
                    port,
                    voltageVolts.toPhasor(),
                    currentAmps.toPhasor(),
                    complexPowerVoltAmps.toPhasor()
            );
        }
    }

}
