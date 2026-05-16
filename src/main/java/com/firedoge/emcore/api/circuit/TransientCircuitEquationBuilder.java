package com.firedoge.emcore.api.circuit;

/**
 * Builder for fixed-step transient companion-model stamps.
 */
public interface TransientCircuitEquationBuilder {
    /**
     * Current transient step duration in seconds.
     */
    double timeStepSeconds();

    /**
     * Adds an ideal capacitor using a backward-Euler companion model for the current time step.
     */
    void addCapacitance(CircuitPort positivePort, CircuitPort negativePort, double capacitanceFarads);

    /**
     * Adds an ideal inductor using a backward-Euler companion model for the current time step.
     */
    void addInductance(CircuitPort positivePort, CircuitPort negativePort, double inductanceHenries);
}
