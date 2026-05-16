package com.firedoge.emcore.api.circuit;

/**
 * Circuit element that contributes fixed-step transient companion-model terms.
 */
public interface TransientCircuitElement extends CircuitElement {
    void stamp(TransientCircuitEquationBuilder builder);
}
