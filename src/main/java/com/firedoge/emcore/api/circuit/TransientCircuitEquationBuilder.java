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
     * Absolute time for the end of the current transient step, in seconds.
     */
    double timeSeconds();

    /**
     * Adds a conductance where I = G * (Vpositive - Vnegative) for this transient step.
     */
    void addConductance(CircuitPort positivePort, CircuitPort negativePort, double conductanceSiemens);

    /**
     * Adds an ideal current source flowing from positivePort to negativePort for this transient step.
     */
    void addCurrentSource(CircuitPort positivePort, CircuitPort negativePort, double currentAmps);

    /**
     * Adds an ideal voltage source enforcing V(positivePort) - V(negativePort) for this transient step.
     * The returned branch current is positive from positivePort to negativePort.
     */
    CircuitBranchCurrent addVoltageSource(CircuitPort positivePort, CircuitPort negativePort, double voltageVolts);

    /**
     * Adds an ideal capacitor using a backward-Euler companion model for the current time step.
     */
    void addCapacitance(CircuitPort positivePort, CircuitPort negativePort, double capacitanceFarads);

    /**
     * Adds an ideal inductor using a backward-Euler companion model for the current time step.
     */
    void addInductance(CircuitPort positivePort, CircuitPort negativePort, double inductanceHenries);
}
