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
import com.firedoge.emcore.api.circuit.NonlinearCircuitEquationBuilder;
import com.firedoge.emcore.internal.circuit.CircuitNetwork.ConductanceStamp;
import com.firedoge.emcore.internal.circuit.CircuitNetwork.CurrentControlledCurrentSourceStamp;
import com.firedoge.emcore.internal.circuit.CircuitNetwork.CurrentControlledVoltageSourceStamp;
import com.firedoge.emcore.internal.circuit.CircuitNetwork.CurrentSourceStamp;
import com.firedoge.emcore.internal.circuit.CircuitNetwork.NonlinearCurrentStamp;
import com.firedoge.emcore.internal.circuit.CircuitNetwork.NonlinearElementStamp;
import com.firedoge.emcore.internal.circuit.CircuitNetwork.SolveResult;
import com.firedoge.emcore.internal.circuit.CircuitNetwork.SolveTopology;
import com.firedoge.emcore.internal.circuit.CircuitNetwork.VoltageControlledCurrentSourceStamp;
import com.firedoge.emcore.internal.circuit.CircuitNetwork.VoltageControlledVoltageSourceStamp;
import com.firedoge.emcore.internal.circuit.CircuitNetwork.VoltageSourceStamp;
import net.minecraft.resources.ResourceLocation;

final class MnaSolver {
    private static final double VOLTAGE_EPSILON = 1.0e-9;
    private static final double NEWTON_ABSOLUTE_TOLERANCE = 1.0e-9;
    private static final double NEWTON_RELATIVE_TOLERANCE = 1.0e-7;
    private static final double NEWTON_MAX_NODE_STEP_VOLTS = 0.25;
    private static final int NEWTON_MAX_ITERATIONS = 96;
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

        SolvedComponent solvedComponent = component.nonlinearElements().isEmpty()
                ? solveLinearComponent(
                        topology,
                        component,
                        nodeRows,
                        branchCurrentColumns,
                        unknownCount,
                        diagnostics
                )
                : solveNonlinearComponent(
                        topology,
                        component,
                        nodeRows,
                        branchCurrentColumns,
                        unknownCount,
                        initialEstimate(topology, nodeRows, unknownCount),
                        diagnostics
                );
        if (solvedComponent == null) {
            return;
        }
        double[] solved = solvedComponent.unknowns();

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

        for (NonlinearCurrentStamp nonlinearCurrent : solvedComponent.nonlinearCurrents()) {
            int positiveNode = topology.nodeOf(nonlinearCurrent.positivePort());
            int negativeNode = topology.nodeOf(nonlinearCurrent.negativePort());
            double voltage = nodeVoltages[positiveNode] - nodeVoltages[negativeNode];
            double current = nonlinearCurrent.currentAmps();
            double power = voltage * current;

            addPortContribution(mutableSamples, nonlinearCurrent.positivePort(), nodeVoltages[positiveNode], current, power);
            addPortContribution(mutableSamples, nonlinearCurrent.negativePort(), nodeVoltages[negativeNode], -current, power);
        }
    }

    private static SolvedComponent solveLinearComponent(
            SolveTopology topology,
            ComponentTopology component,
            Map<Integer, Integer> nodeRows,
            Map<CircuitBranchCurrent, Integer> branchCurrentColumns,
            int unknownCount,
            List<CircuitDiagnostic> diagnostics
    ) {
        LinearizedSystem system = buildLinearizedSystem(
                topology,
                component,
                nodeRows,
                branchCurrentColumns,
                new double[unknownCount],
                unknownCount,
                diagnostics
        );
        if (system == null) {
            return null;
        }

        double[] solved = solveDenseSystem(system.matrix(), system.rhs(), unknownCount, component, diagnostics, "Linear");
        return solved == null ? null : new SolvedComponent(solved, List.of());
    }

    private static SolvedComponent solveNonlinearComponent(
            SolveTopology topology,
            ComponentTopology component,
            Map<Integer, Integer> nodeRows,
            Map<CircuitBranchCurrent, Integer> branchCurrentColumns,
            int unknownCount,
            double[] initialEstimate,
            List<CircuitDiagnostic> diagnostics
    ) {
        double[] estimate = initialEstimate;

        for (int iteration = 0; iteration < NEWTON_MAX_ITERATIONS; iteration++) {
            LinearizedSystem system = buildLinearizedSystem(
                    topology,
                    component,
                    nodeRows,
                    branchCurrentColumns,
                    estimate,
                    unknownCount,
                    diagnostics
            );
            if (system == null) {
                return null;
            }

            double[] candidate = solveDenseSystem(
                    system.matrix(),
                    system.rhs(),
                    unknownCount,
                    component,
                    diagnostics,
                    "Newton linearization"
            );
            if (candidate == null) {
                return null;
            }

            if (hasConverged(estimate, candidate)) {
                LinearizedSystem finalSystem = buildLinearizedSystem(
                        topology,
                        component,
                        nodeRows,
                        branchCurrentColumns,
                        candidate,
                        unknownCount,
                        diagnostics
                );
                return finalSystem == null
                        ? null
                        : new SolvedComponent(candidate, finalSystem.nonlinearCurrents());
            }

            estimate = dampNewtonStep(estimate, candidate, nodeRows.size());
        }

        diagnostics.add(new CircuitDiagnostic(
                CircuitDiagnosticType.NONLINEAR_SOLVE_DID_NOT_CONVERGE,
                CircuitDiagnosticSeverity.ERROR,
                component.nonlinearPorts(),
                "Newton-Raphson solve did not converge after " + NEWTON_MAX_ITERATIONS + " iterations"
        ));
        EMcore.LOGGER.warn(
                "Nonlinear circuit component did not converge after {} iterations; retaining zero samples for {} nodes",
                NEWTON_MAX_ITERATIONS,
                component.nodes().size()
        );
        return null;
    }

    private static LinearizedSystem buildLinearizedSystem(
            SolveTopology topology,
            ComponentTopology component,
            Map<Integer, Integer> nodeRows,
            Map<CircuitBranchCurrent, Integer> branchCurrentColumns,
            double[] estimate,
            int unknownCount,
            List<CircuitDiagnostic> diagnostics
    ) {
        double[][] matrix = new double[unknownCount][unknownCount];
        double[] rhs = new double[unknownCount];

        if (!stampLinearTerms(topology, component, nodeRows, branchCurrentColumns, matrix, rhs, diagnostics)) {
            return null;
        }

        NonlinearEquationRecorder nonlinearRecorder = new NonlinearEquationRecorder(topology, nodeRows, estimate);
        for (NonlinearElementStamp nonlinearElement : component.nonlinearElements()) {
            nonlinearRecorder.record(nonlinearElement, diagnostics);
        }
        if (nonlinearRecorder.failed()) {
            return null;
        }

        for (ConductanceStamp conductance : nonlinearRecorder.conductances()) {
            int positiveNode = topology.nodeOf(conductance.positivePort());
            int negativeNode = topology.nodeOf(conductance.negativePort());
            addConductance(matrix, nodeRows, positiveNode, negativeNode, conductance.conductanceSiemens());
        }
        for (CurrentSourceStamp currentSource : nonlinearRecorder.currentSources()) {
            int positiveNode = topology.nodeOf(currentSource.positivePort());
            int negativeNode = topology.nodeOf(currentSource.negativePort());
            addCurrentSource(rhs, nodeRows, positiveNode, negativeNode, currentSource.currentAmps());
        }

        return new LinearizedSystem(matrix, rhs, nonlinearRecorder.nonlinearCurrents());
    }

    private static boolean stampLinearTerms(
            SolveTopology topology,
            ComponentTopology component,
            Map<Integer, Integer> nodeRows,
            Map<CircuitBranchCurrent, Integer> branchCurrentColumns,
            double[][] matrix,
            double[] rhs,
            List<CircuitDiagnostic> diagnostics
    ) {
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
                return false;
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
                return false;
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

        return true;
    }

    private static double[] solveDenseSystem(
            double[][] matrix,
            double[] rhs,
            int unknownCount,
            ComponentTopology component,
            List<CircuitDiagnostic> diagnostics,
            String solveLabel
    ) {
        double[] solved = unknownCount == 0 ? new double[0] : DenseLinearSolver.solve(matrix, rhs);
        if (solved != null) {
            return solved;
        }

        diagnostics.add(new CircuitDiagnostic(
                CircuitDiagnosticType.SOLVE_FAILED,
                CircuitDiagnosticSeverity.ERROR,
                component.allPorts(),
                solveLabel + " solve failed for circuit component with " + component.nodes().size() + " nodes"
        ));
        EMcore.LOGGER.warn(
                "{} circuit component solve failed; retaining zero samples for {} nodes",
                solveLabel,
                component.nodes().size()
        );
        return null;
    }

    private static boolean hasConverged(double[] previous, double[] candidate) {
        for (int index = 0; index < previous.length; index++) {
            double delta = Math.abs(candidate[index] - previous[index]);
            double scale = Math.max(Math.abs(candidate[index]), Math.abs(previous[index]));
            if (delta > NEWTON_ABSOLUTE_TOLERANCE + NEWTON_RELATIVE_TOLERANCE * scale) {
                return false;
            }
        }
        return true;
    }

    private static double[] dampNewtonStep(double[] previous, double[] candidate, int nodeUnknownCount) {
        double maxNodeDelta = 0.0;
        for (int index = 0; index < nodeUnknownCount; index++) {
            maxNodeDelta = Math.max(maxNodeDelta, Math.abs(candidate[index] - previous[index]));
        }

        if (maxNodeDelta <= NEWTON_MAX_NODE_STEP_VOLTS) {
            return candidate;
        }

        double alpha = NEWTON_MAX_NODE_STEP_VOLTS / maxNodeDelta;
        double[] damped = new double[candidate.length];
        for (int index = 0; index < candidate.length; index++) {
            damped[index] = previous[index] + alpha * (candidate[index] - previous[index]);
        }
        return damped;
    }

    private static double[] initialEstimate(
            SolveTopology topology,
            Map<Integer, Integer> nodeRows,
            int unknownCount
    ) {
        double[] estimate = new double[unknownCount];
        double[] initialNodeVoltages = topology.initialNodeVoltages();
        for (Map.Entry<Integer, Integer> entry : nodeRows.entrySet()) {
            int node = entry.getKey();
            if (node >= 0 && node < initialNodeVoltages.length) {
                estimate[entry.getValue()] = initialNodeVoltages[node];
            }
        }
        return estimate;
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
        for (NonlinearElementStamp nonlinearElement : topology.nonlinearElements()) {
            MnaTopologySupport.connectPorts(componentUnionFind, topology, nonlinearElement.ports());
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
        List<List<NonlinearElementStamp>> nonlinearElementsByComponent = new ArrayList<>();
        for (int index = 0; index < nodesByComponent.size(); index++) {
            conductancesByComponent.add(new ArrayList<>());
            currentSourcesByComponent.add(new ArrayList<>());
            sourcesByComponent.add(new ArrayList<>());
            voltageControlledCurrentSourcesByComponent.add(new ArrayList<>());
            voltageControlledVoltageSourcesByComponent.add(new ArrayList<>());
            currentControlledCurrentSourcesByComponent.add(new ArrayList<>());
            currentControlledVoltageSourcesByComponent.add(new ArrayList<>());
            nonlinearElementsByComponent.add(new ArrayList<>());
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
        for (NonlinearElementStamp nonlinearElement : topology.nonlinearElements()) {
            if (!nonlinearElement.ports().isEmpty()) {
                nonlinearElementsByComponent
                        .get(componentByNode[topology.nodeOf(nonlinearElement.ports().getFirst())])
                        .add(nonlinearElement);
            }
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
                    List.copyOf(currentControlledVoltageSourcesByComponent.get(index)),
                    List.copyOf(nonlinearElementsByComponent.get(index))
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
            List<CurrentControlledVoltageSourceStamp> currentControlledVoltageSources,
            List<NonlinearElementStamp> nonlinearElements
    ) {
        private List<CircuitPort> nonlinearPorts() {
            List<CircuitPort> ports = new ArrayList<>();
            for (NonlinearElementStamp nonlinearElement : nonlinearElements) {
                ports.addAll(nonlinearElement.ports());
            }
            return List.copyOf(ports);
        }

        private List<CircuitPort> allPorts() {
            List<CircuitPort> ports = new ArrayList<>();
            conductances.forEach(stamp -> addPorts(ports, stamp.positivePort(), stamp.negativePort()));
            currentSources.forEach(stamp -> addPorts(ports, stamp.positivePort(), stamp.negativePort()));
            voltageSources.forEach(stamp -> addPorts(ports, stamp.positivePort(), stamp.negativePort()));
            voltageControlledCurrentSources.forEach(stamp -> addPorts(
                    ports,
                    stamp.positivePort(),
                    stamp.negativePort(),
                    stamp.controlPositivePort(),
                    stamp.controlNegativePort()
            ));
            voltageControlledVoltageSources.forEach(stamp -> addPorts(
                    ports,
                    stamp.positivePort(),
                    stamp.negativePort(),
                    stamp.controlPositivePort(),
                    stamp.controlNegativePort()
            ));
            currentControlledCurrentSources.forEach(stamp -> addPorts(ports, stamp.positivePort(), stamp.negativePort()));
            currentControlledVoltageSources.forEach(stamp -> addPorts(ports, stamp.positivePort(), stamp.negativePort()));
            nonlinearElements.forEach(stamp -> ports.addAll(stamp.ports()));
            return List.copyOf(ports);
        }

        private static void addPorts(List<CircuitPort> ports, CircuitPort... addedPorts) {
            ports.addAll(List.of(addedPorts));
        }
    }

    private record LinearizedSystem(
            double[][] matrix,
            double[] rhs,
            List<NonlinearCurrentStamp> nonlinearCurrents
    ) {
    }

    private record SolvedComponent(
            double[] unknowns,
            List<NonlinearCurrentStamp> nonlinearCurrents
    ) {
    }

    private record VoltageConstraint(int node, double deltaVolts, VoltageSourceStamp source) {
    }

    private static final class NonlinearEquationRecorder implements NonlinearCircuitEquationBuilder {
        private final SolveTopology topology;
        private final Map<Integer, Integer> nodeRows;
        private final double[] estimate;
        private final List<ConductanceStamp> conductances = new ArrayList<>();
        private final List<CurrentSourceStamp> currentSources = new ArrayList<>();
        private final List<NonlinearCurrentStamp> nonlinearCurrents = new ArrayList<>();
        private ResourceLocation elementId;
        private List<CircuitPort> elementPorts = List.of();
        private boolean failed;

        private NonlinearEquationRecorder(
                SolveTopology topology,
                Map<Integer, Integer> nodeRows,
                double[] estimate
        ) {
            this.topology = topology;
            this.nodeRows = nodeRows;
            this.estimate = estimate;
        }

        private void record(NonlinearElementStamp nonlinearElement, List<CircuitDiagnostic> diagnostics) {
            ResourceLocation previousElementId = elementId;
            List<CircuitPort> previousElementPorts = elementPorts;
            elementId = nonlinearElement.id();
            elementPorts = nonlinearElement.ports();
            int conductanceSize = conductances.size();
            int currentSourceSize = currentSources.size();
            int nonlinearCurrentSize = nonlinearCurrents.size();

            try {
                nonlinearElement.element().stamp(this);
            } catch (RuntimeException exception) {
                trim(conductances, conductanceSize);
                trim(currentSources, currentSourceSize);
                trim(nonlinearCurrents, nonlinearCurrentSize);
                diagnostics.add(new CircuitDiagnostic(
                        CircuitDiagnosticType.ELEMENT_STAMP_FAILED,
                        CircuitDiagnosticSeverity.ERROR,
                        nonlinearElement.ports(),
                        stampFailureMessage(nonlinearElement, exception)
                ));
                EMcore.LOGGER.warn(
                        "Nonlinear circuit element {} failed while stamping equations",
                        nonlinearElement.id(),
                        exception
                );
                failed = true;
            } finally {
                elementId = previousElementId;
                elementPorts = previousElementPorts;
            }
        }

        @Override
        public double voltage(CircuitPort positivePort, CircuitPort negativePort) {
            CircuitPort checkedPositivePort = requirePort(positivePort, "positivePort");
            CircuitPort checkedNegativePort = requirePort(negativePort, "negativePort");
            return nodeVoltage(topology.nodeOf(checkedPositivePort)) - nodeVoltage(topology.nodeOf(checkedNegativePort));
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

            CircuitPort checkedPositivePort = requirePort(positivePort, "positivePort");
            CircuitPort checkedNegativePort = requirePort(negativePort, "negativePort");
            double operatingVoltage = voltage(checkedPositivePort, checkedNegativePort);
            double equivalentCurrentAmps = currentAmps - conductanceSiemens * operatingVoltage;

            if (conductanceSiemens != 0.0) {
                conductances.add(new ConductanceStamp(
                        elementId,
                        checkedPositivePort,
                        checkedNegativePort,
                        conductanceSiemens
                ));
            }
            if (equivalentCurrentAmps != 0.0) {
                currentSources.add(new CurrentSourceStamp(
                        elementId,
                        checkedPositivePort,
                        checkedNegativePort,
                        equivalentCurrentAmps
                ));
            }
            nonlinearCurrents.add(new NonlinearCurrentStamp(
                    elementId,
                    checkedPositivePort,
                    checkedNegativePort,
                    currentAmps,
                    conductanceSiemens
            ));
        }

        private List<ConductanceStamp> conductances() {
            return List.copyOf(conductances);
        }

        private List<CurrentSourceStamp> currentSources() {
            return List.copyOf(currentSources);
        }

        private List<NonlinearCurrentStamp> nonlinearCurrents() {
            return List.copyOf(nonlinearCurrents);
        }

        private boolean failed() {
            return failed;
        }

        private double nodeVoltage(int node) {
            int row = rowOf(nodeRows, node);
            return row < 0 ? 0.0 : estimate[row];
        }

        private CircuitPort requirePort(CircuitPort port, String name) {
            CircuitPort checkedPort = java.util.Objects.requireNonNull(port, name);
            if (!elementPorts.contains(checkedPort)) {
                throw new IllegalArgumentException("Nonlinear stamped port was not returned by ports(): " + checkedPort);
            }
            topology.nodeOf(checkedPort);
            return checkedPort;
        }

        private static void requireFinite(double value, String name) {
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException(name + " must be finite");
            }
        }

        private static String stampFailureMessage(NonlinearElementStamp element, RuntimeException exception) {
            String detail = exception.getMessage();
            if (detail == null || detail.isBlank()) {
                return "Element " + element.id() + " failed while stamping nonlinear equations";
            }
            return "Element " + element.id() + " failed while stamping nonlinear equations: " + detail;
        }

        private static <T> void trim(List<T> values, int size) {
            while (values.size() > size) {
                values.removeLast();
            }
        }
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
