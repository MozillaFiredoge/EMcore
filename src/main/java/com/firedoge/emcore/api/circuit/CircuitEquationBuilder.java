package com.firedoge.emcore.api.circuit;

/**
 * Builder for linear DC circuit equations. Implementations stamp terms into EMcore's MNA solver.
 */
public interface CircuitEquationBuilder {
    /**
     * Adds a conductance where I = G * (Vpositive - Vnegative).
     */
    void addConductance(CircuitPort positivePort, CircuitPort negativePort, double conductanceSiemens);

    /**
     * Adds an ideal current source flowing from positivePort to negativePort.
     */
    void addCurrentSource(CircuitPort positivePort, CircuitPort negativePort, double currentAmps);

    /**
     * Adds an ideal voltage source enforcing V(positivePort) - V(negativePort).
     * The returned branch current is positive from positivePort to negativePort.
     */
    CircuitBranchCurrent addVoltageSource(CircuitPort positivePort, CircuitPort negativePort, double voltageVolts);

    /**
     * Adds a 0V ideal voltage source that exposes its branch current for current-controlled sources.
     */
    CircuitBranchCurrent addCurrentProbe(CircuitPort positivePort, CircuitPort negativePort);

    /**
     * Adds a voltage-controlled current source. Output current flows from positivePort to negativePort.
     */
    void addVoltageControlledCurrentSource(
            CircuitPort positivePort,
            CircuitPort negativePort,
            CircuitPort controlPositivePort,
            CircuitPort controlNegativePort,
            double transconductanceSiemens
    );

    /**
     * Adds a voltage-controlled voltage source enforcing output voltage as gain times control voltage.
     * The returned branch current is positive from positivePort to negativePort.
     */
    CircuitBranchCurrent addVoltageControlledVoltageSource(
            CircuitPort positivePort,
            CircuitPort negativePort,
            CircuitPort controlPositivePort,
            CircuitPort controlNegativePort,
            double gain
    );

    /**
     * Adds a current-controlled current source. Output current flows from positivePort to negativePort.
     */
    void addCurrentControlledCurrentSource(
            CircuitPort positivePort,
            CircuitPort negativePort,
            CircuitBranchCurrent controlCurrent,
            double gain
    );

    /**
     * Adds a current-controlled voltage source enforcing output voltage as transresistance times control current.
     * The returned branch current is positive from positivePort to negativePort.
     */
    CircuitBranchCurrent addCurrentControlledVoltageSource(
            CircuitPort positivePort,
            CircuitPort negativePort,
            CircuitBranchCurrent controlCurrent,
            double transresistanceOhms
    );
}
