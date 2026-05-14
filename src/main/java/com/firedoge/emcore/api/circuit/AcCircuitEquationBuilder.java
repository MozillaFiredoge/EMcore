package com.firedoge.emcore.api.circuit;

/**
 * Builder for linear AC phasor circuit equations at one fixed frequency.
 */
public interface AcCircuitEquationBuilder {
    /**
     * Frequency for the current phasor solve, in hertz.
     */
    double frequencyHertz();

    /**
     * Angular frequency for the current phasor solve, in radians per second.
     */
    default double angularFrequencyRadiansPerSecond() {
        return 2.0 * Math.PI * frequencyHertz();
    }

    /**
     * Adds an admittance where I = Y * (Vpositive - Vnegative).
     */
    void addAdmittance(CircuitPort positivePort, CircuitPort negativePort, CircuitPhasor admittanceSiemens);

    /**
     * Adds an ideal current source flowing from positivePort to negativePort.
     */
    void addCurrentSource(CircuitPort positivePort, CircuitPort negativePort, CircuitPhasor currentAmps);

    /**
     * Adds an ideal voltage source enforcing V(positivePort) - V(negativePort).
     * The returned branch current is positive from positivePort to negativePort.
     */
    CircuitBranchCurrent addVoltageSource(CircuitPort positivePort, CircuitPort negativePort, CircuitPhasor voltageVolts);

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
            CircuitPhasor transconductanceSiemens
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
            CircuitPhasor gain
    );

    /**
     * Adds a current-controlled current source. Output current flows from positivePort to negativePort.
     */
    void addCurrentControlledCurrentSource(
            CircuitPort positivePort,
            CircuitPort negativePort,
            CircuitBranchCurrent controlCurrent,
            CircuitPhasor gain
    );

    /**
     * Adds a current-controlled voltage source enforcing output voltage as transimpedance times control current.
     * The returned branch current is positive from positivePort to negativePort.
     */
    CircuitBranchCurrent addCurrentControlledVoltageSource(
            CircuitPort positivePort,
            CircuitPort negativePort,
            CircuitBranchCurrent controlCurrent,
            CircuitPhasor transimpedanceOhms
    );
}
