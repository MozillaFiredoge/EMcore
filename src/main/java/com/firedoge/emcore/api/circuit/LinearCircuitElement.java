package com.firedoge.emcore.api.circuit;

/**
 * Circuit element that contributes linear terms to the DC solver.
 */
public interface LinearCircuitElement extends CircuitElement {
    void stamp(CircuitEquationBuilder builder);
}
