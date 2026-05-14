package com.firedoge.emcore.internal.circuit;

import com.firedoge.emcore.api.circuit.CircuitPhasor;

record Complex(double real, double imaginary) {
    static final Complex ZERO = new Complex(0.0, 0.0);
    static final Complex ONE = new Complex(1.0, 0.0);
    static final Complex I = new Complex(0.0, 1.0);

    Complex {
        if (!Double.isFinite(real) || !Double.isFinite(imaginary)) {
            throw new IllegalArgumentException("Complex components must be finite");
        }
    }

    static Complex of(double real, double imaginary) {
        return new Complex(real, imaginary);
    }

    static Complex real(double real) {
        return new Complex(real, 0.0);
    }

    static Complex from(CircuitPhasor phasor) {
        return new Complex(phasor.real(), phasor.imaginary());
    }

    Complex add(Complex other) {
        return new Complex(real + other.real, imaginary + other.imaginary);
    }

    Complex subtract(Complex other) {
        return new Complex(real - other.real, imaginary - other.imaginary);
    }

    Complex multiply(Complex other) {
        return new Complex(
                real * other.real - imaginary * other.imaginary,
                real * other.imaginary + imaginary * other.real
        );
    }

    Complex multiply(double scalar) {
        return new Complex(real * scalar, imaginary * scalar);
    }

    Complex divide(Complex other) {
        double denominator = other.real * other.real + other.imaginary * other.imaginary;
        if (denominator == 0.0) {
            throw new ArithmeticException("Cannot divide by zero complex value");
        }

        return new Complex(
                (real * other.real + imaginary * other.imaginary) / denominator,
                (imaginary * other.real - real * other.imaginary) / denominator
        );
    }

    Complex negate() {
        return new Complex(-real, -imaginary);
    }

    Complex conjugate() {
        return new Complex(real, -imaginary);
    }

    double abs() {
        return Math.hypot(real, imaginary);
    }

    CircuitPhasor toPhasor() {
        return new CircuitPhasor(real, imaginary);
    }
}
