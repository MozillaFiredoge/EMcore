package com.firedoge.emcore.api.circuit;

/**
 * Circuit element that contributes ideal topology before linear equations are built.
 */
public interface CircuitTopologyElement extends CircuitElement {
    void buildTopology(CircuitTopologyBuilder builder);
}
