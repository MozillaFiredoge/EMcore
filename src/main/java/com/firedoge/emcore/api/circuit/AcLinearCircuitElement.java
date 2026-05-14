package com.firedoge.emcore.api.circuit;

/**
 * Circuit element that contributes linear frequency-domain terms to the AC phasor solver.
 */
public interface AcLinearCircuitElement extends CircuitElement {
    void stamp(AcCircuitEquationBuilder builder);
}
