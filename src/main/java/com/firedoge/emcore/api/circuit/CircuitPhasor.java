package com.firedoge.emcore.api.circuit;

/**
 * Complex-valued AC phasor used by frequency-domain circuit APIs.
 */
public record CircuitPhasor(double real, double imaginary) {
    public static final CircuitPhasor ZERO = new CircuitPhasor(0.0, 0.0);
    public static final CircuitPhasor ONE = new CircuitPhasor(1.0, 0.0);
    public static final CircuitPhasor I = new CircuitPhasor(0.0, 1.0);

    public CircuitPhasor {
        if (!Double.isFinite(real) || !Double.isFinite(imaginary)) {
            throw new IllegalArgumentException("Phasor components must be finite");
        }
    }

    public static CircuitPhasor of(double real, double imaginary) {
        return new CircuitPhasor(real, imaginary);
    }

    public static CircuitPhasor real(double real) {
        return new CircuitPhasor(real, 0.0);
    }

    public CircuitPhasor add(CircuitPhasor other) {
        return new CircuitPhasor(real + other.real, imaginary + other.imaginary);
    }

    public CircuitPhasor subtract(CircuitPhasor other) {
        return new CircuitPhasor(real - other.real, imaginary - other.imaginary);
    }

    public CircuitPhasor multiply(CircuitPhasor other) {
        return new CircuitPhasor(
                real * other.real - imaginary * other.imaginary,
                real * other.imaginary + imaginary * other.real
        );
    }

    public CircuitPhasor multiply(double scalar) {
        return new CircuitPhasor(real * scalar, imaginary * scalar);
    }

    public CircuitPhasor divide(CircuitPhasor other) {
        double denominator = other.real * other.real + other.imaginary * other.imaginary;
        if (denominator == 0.0) {
            throw new ArithmeticException("Cannot divide by zero phasor");
        }

        return new CircuitPhasor(
                (real * other.real + imaginary * other.imaginary) / denominator,
                (imaginary * other.real - real * other.imaginary) / denominator
        );
    }

    public CircuitPhasor negate() {
        return new CircuitPhasor(-real, -imaginary);
    }

    public CircuitPhasor conjugate() {
        return new CircuitPhasor(real, -imaginary);
    }

    public double magnitude() {
        return Math.hypot(real, imaginary);
    }
}
