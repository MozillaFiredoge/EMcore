package com.firedoge.emcore.api.circuit;

/**
 * Builder for ideal circuit topology. Connections declared here collapse ports into the same node.
 */
public interface CircuitTopologyBuilder {
    void connectIdeal(CircuitPort firstPort, CircuitPort secondPort);
}
