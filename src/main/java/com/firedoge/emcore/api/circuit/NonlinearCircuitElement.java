package com.firedoge.emcore.api.circuit;

/**
 * Circuit element that contributes nonlinear DC terms through Newton-Raphson linearization.
 */
public interface NonlinearCircuitElement extends CircuitElement {
    void stamp(NonlinearCircuitEquationBuilder builder);
}
