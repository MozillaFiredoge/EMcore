package com.firedoge.emcore.internal.circuit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.firedoge.emcore.api.circuit.BatchTransientRequest;
import com.firedoge.emcore.api.circuit.BatchTransientResult;
import com.firedoge.emcore.api.circuit.BatchTransientStep;
import com.firedoge.emcore.api.circuit.CircuitDiagnostic;
import com.firedoge.emcore.api.circuit.CircuitNetlist;
import com.firedoge.emcore.api.circuit.CircuitPort;
import com.firedoge.emcore.api.circuit.CircuitSample;
import com.firedoge.emcore.api.circuit.CircuitSnapshot;

public final class BatchTransientSolver {
    private BatchTransientSolver() {
    }

    public static BatchTransientResult solve(BatchTransientRequest request) {
        Objects.requireNonNull(request, "request");

        CircuitNetwork network = new CircuitNetwork();
        CircuitNetlist netlist = request.netlist();
        netlist.ports().forEach(network::registerPort);
        netlist.terminals().forEach(network::registerTerminal);
        netlist.elements().forEach(network::registerElement);
        CircuitNetwork.PreparedTransientPlan plan = network.prepareTransient(
                request.timeStepSeconds(),
                request.startTimeSeconds()
        );

        List<BatchTransientStep> steps = new ArrayList<>(request.steps());
        List<CircuitDiagnostic> diagnostics = new ArrayList<>();
        for (int stepIndex = 0; stepIndex < request.steps(); stepIndex++) {
            double timeSeconds = request.startTimeSeconds() + request.timeStepSeconds() * (stepIndex + 1);
            CircuitSnapshot snapshot = plan.step(timeSeconds);
            List<CircuitSample> probeSamples = probeSamples(snapshot, request.probes());
            if (!snapshot.diagnostics().isEmpty()) {
                diagnostics.addAll(snapshot.diagnostics());
            }

            steps.add(new BatchTransientStep(
                    stepIndex,
                    timeSeconds,
                    snapshot.nodes(),
                    probeSamples,
                    snapshot.diagnostics()
            ));
        }

        return new BatchTransientResult(
                request.timeStepSeconds(),
                request.startTimeSeconds(),
                request.probes(),
                steps,
                diagnostics
        );
    }

    private static List<CircuitSample> probeSamples(CircuitSnapshot snapshot, List<CircuitPort> probes) {
        if (probes.isEmpty()) {
            return snapshot.samples();
        }

        Map<CircuitPort, CircuitSample> samplesByPort = new LinkedHashMap<>();
        for (CircuitSample sample : snapshot.samples()) {
            samplesByPort.put(sample.port(), sample);
        }

        List<CircuitSample> samples = new ArrayList<>(probes.size());
        for (CircuitPort probe : probes) {
            CircuitSample sample = samplesByPort.get(probe);
            if (sample != null) {
                samples.add(sample);
            }
        }
        return List.copyOf(samples);
    }
}
