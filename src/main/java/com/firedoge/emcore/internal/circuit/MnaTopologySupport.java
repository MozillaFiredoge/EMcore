package com.firedoge.emcore.internal.circuit;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.firedoge.emcore.api.circuit.CircuitBranchCurrent;
import com.firedoge.emcore.api.circuit.CircuitPort;

final class MnaTopologySupport {
    private MnaTopologySupport() {
    }

    interface NodeLookup {
        int nodeOf(CircuitPort port);
    }

    interface PortPairSource {
        CircuitPort positivePort();

        CircuitPort negativePort();
    }

    interface VoltageLikeBranch extends PortPairSource {
        CircuitBranchCurrent branchCurrent();
    }

    record PortPair(CircuitPort positivePort, CircuitPort negativePort) {
    }

    @SafeVarargs
    static Map<CircuitBranchCurrent, PortPair> branchEndpoints(
            List<? extends VoltageLikeBranch>... voltageLikeBranches
    ) {
        Map<CircuitBranchCurrent, PortPair> endpoints = new HashMap<>();
        for (List<? extends VoltageLikeBranch> branches : voltageLikeBranches) {
            for (VoltageLikeBranch branch : branches) {
                endpoints.put(branch.branchCurrent(), new PortPair(branch.positivePort(), branch.negativePort()));
            }
        }
        return endpoints;
    }

    static void connectControlledSourceNodes(
            DisjointSet disjointSet,
            NodeLookup topology,
            CircuitPort positivePort,
            CircuitPort negativePort,
            CircuitPort controlPositivePort,
            CircuitPort controlNegativePort
    ) {
        connectNodes(disjointSet, topology, positivePort, negativePort);
        connectNodes(disjointSet, topology, positivePort, controlPositivePort);
        connectNodes(disjointSet, topology, positivePort, controlNegativePort);
    }

    static void connectCurrentControlledSourceNodes(
            DisjointSet disjointSet,
            NodeLookup topology,
            Map<CircuitBranchCurrent, PortPair> branchEndpoints,
            CircuitPort positivePort,
            CircuitPort negativePort,
            CircuitBranchCurrent controlCurrent
    ) {
        connectNodes(disjointSet, topology, positivePort, negativePort);

        PortPair endpoints = branchEndpoints.get(controlCurrent);
        if (endpoints == null) {
            return;
        }

        connectNodes(disjointSet, topology, positivePort, endpoints.positivePort());
        connectNodes(disjointSet, topology, positivePort, endpoints.negativePort());
    }

    static void connectNodes(
            DisjointSet disjointSet,
            NodeLookup topology,
            CircuitPort firstPort,
            CircuitPort secondPort
    ) {
        disjointSet.union(topology.nodeOf(firstPort), topology.nodeOf(secondPort));
    }

    static void connectPorts(
            DisjointSet disjointSet,
            NodeLookup topology,
            List<CircuitPort> ports
    ) {
        if (ports.size() < 2) {
            return;
        }

        CircuitPort firstPort = ports.getFirst();
        for (int index = 1; index < ports.size(); index++) {
            connectNodes(disjointSet, topology, firstPort, ports.get(index));
        }
    }
}
