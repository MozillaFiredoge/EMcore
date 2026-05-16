package com.firedoge.emcore.api.circuit;

/**
 * Builder for nonlinear Newton-Raphson linearization stamps.
 */
public interface NonlinearCircuitEquationBuilder {
    /**
     * Returns the current Newton operating-point voltage V(positivePort) - V(negativePort).
     */
    double voltage(CircuitPort positivePort, CircuitPort negativePort);

    /**
     * Adds a two-terminal nonlinear current linearized at the current operating point.
     *
     * <p>The current direction is from positivePort to negativePort. The conductance is dI/dV at the same
     * operating point returned by {@link #voltage(CircuitPort, CircuitPort)}.</p>
     */
    void addLinearizedCurrent(
            CircuitPort positivePort,
            CircuitPort negativePort,
            double currentAmps,
            double conductanceSiemens
    );
}
