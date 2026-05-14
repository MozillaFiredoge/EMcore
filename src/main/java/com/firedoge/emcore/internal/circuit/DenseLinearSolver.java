package com.firedoge.emcore.internal.circuit;

final class DenseLinearSolver {
    static final double DEFAULT_PIVOT_EPSILON = 1.0e-12;

    private DenseLinearSolver() {
    }

    static double[] solve(double[][] matrix, double[] rhs) {
        int size = rhs.length;
        double[][] a = new double[size][size];
        double[] b = rhs.clone();

        for (int row = 0; row < size; row++) {
            a[row] = matrix[row].clone();
        }

        for (int column = 0; column < size; column++) {
            int pivotRow = column;
            double pivotValue = Math.abs(a[column][column]);

            for (int row = column + 1; row < size; row++) {
                double candidate = Math.abs(a[row][column]);
                if (candidate > pivotValue) {
                    pivotRow = row;
                    pivotValue = candidate;
                }
            }

            if (pivotValue < DEFAULT_PIVOT_EPSILON) {
                return null;
            }

            if (pivotRow != column) {
                double[] row = a[column];
                a[column] = a[pivotRow];
                a[pivotRow] = row;

                double value = b[column];
                b[column] = b[pivotRow];
                b[pivotRow] = value;
            }

            double pivot = a[column][column];
            for (int row = column + 1; row < size; row++) {
                double factor = a[row][column] / pivot;
                if (factor == 0.0) {
                    continue;
                }

                a[row][column] = 0.0;
                for (int nextColumn = column + 1; nextColumn < size; nextColumn++) {
                    a[row][nextColumn] -= factor * a[column][nextColumn];
                }
                b[row] -= factor * b[column];
            }
        }

        double[] solution = new double[size];
        for (int row = size - 1; row >= 0; row--) {
            double value = b[row];
            for (int column = row + 1; column < size; column++) {
                value -= a[row][column] * solution[column];
            }
            solution[row] = value / a[row][row];
        }

        return solution;
    }

    static Complex[] solve(Complex[][] matrix, Complex[] rhs) {
        int size = rhs.length;
        Complex[][] a = new Complex[size][size];
        Complex[] b = rhs.clone();

        for (int row = 0; row < size; row++) {
            a[row] = matrix[row].clone();
        }

        for (int column = 0; column < size; column++) {
            int pivotRow = column;
            double pivotValue = a[column][column].abs();

            for (int row = column + 1; row < size; row++) {
                double candidate = a[row][column].abs();
                if (candidate > pivotValue) {
                    pivotRow = row;
                    pivotValue = candidate;
                }
            }

            if (pivotValue < DEFAULT_PIVOT_EPSILON) {
                return null;
            }

            if (pivotRow != column) {
                Complex[] row = a[column];
                a[column] = a[pivotRow];
                a[pivotRow] = row;

                Complex value = b[column];
                b[column] = b[pivotRow];
                b[pivotRow] = value;
            }

            Complex pivot = a[column][column];
            for (int row = column + 1; row < size; row++) {
                Complex factor = a[row][column].divide(pivot);
                if (factor.abs() == 0.0) {
                    continue;
                }

                a[row][column] = Complex.ZERO;
                for (int nextColumn = column + 1; nextColumn < size; nextColumn++) {
                    a[row][nextColumn] = a[row][nextColumn].subtract(factor.multiply(a[column][nextColumn]));
                }
                b[row] = b[row].subtract(factor.multiply(b[column]));
            }
        }

        Complex[] solution = new Complex[size];
        for (int row = size - 1; row >= 0; row--) {
            Complex value = b[row];
            for (int column = row + 1; column < size; column++) {
                value = value.subtract(a[row][column].multiply(solution[column]));
            }
            solution[row] = value.divide(a[row][row]);
        }

        return solution;
    }
}
