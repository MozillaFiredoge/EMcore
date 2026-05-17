package com.firedoge.emcore.internal.field;

import java.util.Arrays;

public final class PreparedPoissonRegion {
    public static final double VACUUM_PERMITTIVITY_FARADS_PER_METER = 8.854_187_8128e-12;
    public static final double VACUUM_PERMEABILITY_HENRYS_PER_METER = 1.256_637_062_12e-6;
    private static final double MIN_RELATIVE_PERMITTIVITY = 1.0e-9;

    private final int xSize;
    private final int ySize;
    private final int zSize;
    private final int xySize;
    private final int cellCount;
    private final double cellSizeMeters;
    private final double cellSizeSquared;
    private final double[] potentialVolts;
    private final double[] chargeDensityCoulombsPerCubicMeter;
    private final double[] relativePermittivity;
    private final byte[] dirichletBoundary;

    private int lastIterations;
    private double lastMaxDeltaVolts;
    private double lastMaxResidual;

    public PreparedPoissonRegion(int xSize, int ySize, int zSize, double cellSizeMeters) {
        if (xSize < 3 || ySize < 3 || zSize < 3) {
            throw new IllegalArgumentException("Poisson grids need at least 3 cells on each axis");
        }
        if (!Double.isFinite(cellSizeMeters) || cellSizeMeters <= 0.0) {
            throw new IllegalArgumentException("cellSizeMeters must be finite and positive");
        }

        this.xSize = xSize;
        this.ySize = ySize;
        this.zSize = zSize;
        this.xySize = xSize * ySize;
        this.cellCount = xySize * zSize;
        this.cellSizeMeters = cellSizeMeters;
        this.cellSizeSquared = cellSizeMeters * cellSizeMeters;
        this.potentialVolts = new double[cellCount];
        this.chargeDensityCoulombsPerCubicMeter = new double[cellCount];
        this.relativePermittivity = new double[cellCount];
        this.dirichletBoundary = new byte[cellCount];
        Arrays.fill(relativePermittivity, 1.0);
    }

    public int xSize() {
        return xSize;
    }

    public int ySize() {
        return ySize;
    }

    public int zSize() {
        return zSize;
    }

    public int cellCount() {
        return cellCount;
    }

    public int interiorCellCount() {
        return (xSize - 2) * (ySize - 2) * (zSize - 2);
    }

    public double cellSizeMeters() {
        return cellSizeMeters;
    }

    public double lastMaxResidual() {
        return lastMaxResidual;
    }

    public int lastIterations() {
        return lastIterations;
    }

    public double lastMaxDeltaVolts() {
        return lastMaxDeltaVolts;
    }

    public void clearPotential() {
        Arrays.fill(potentialVolts, 0.0);
    }

    public void clearChargeDensity() {
        Arrays.fill(chargeDensityCoulombsPerCubicMeter, 0.0);
    }

    public void clearBoundaries() {
        Arrays.fill(dirichletBoundary, (byte) 0);
    }

    public void fillRelativePermittivity(double epsilonRelative) {
        validateRelativePermittivity(epsilonRelative);
        Arrays.fill(relativePermittivity, epsilonRelative);
    }

    public void setChargeDensity(int x, int y, int z, double value) {
        validateFinite(value, "charge density");
        chargeDensityCoulombsPerCubicMeter[index(x, y, z)] = value;
    }

    public void addChargeDensity(int x, int y, int z, double value) {
        validateFinite(value, "charge density");
        chargeDensityCoulombsPerCubicMeter[index(x, y, z)] += value;
    }

    public void setRelativePermittivity(int x, int y, int z, double epsilonRelative) {
        validateRelativePermittivity(epsilonRelative);
        relativePermittivity[index(x, y, z)] = epsilonRelative;
    }

    public void setPotential(int x, int y, int z, double value) {
        validateFinite(value, "potential");
        potentialVolts[index(x, y, z)] = value;
    }

    public double potential(int x, int y, int z) {
        return potentialVolts[index(x, y, z)];
    }

    public double chargeDensity(int x, int y, int z) {
        return chargeDensityCoulombsPerCubicMeter[index(x, y, z)];
    }

    public double relativePermittivity(int x, int y, int z) {
        return relativePermittivity[index(x, y, z)];
    }

    public double[] copyPotentialVolts() {
        return potentialVolts.clone();
    }

    public double[] copyChargeDensityCoulombsPerCubicMeter() {
        return chargeDensityCoulombsPerCubicMeter.clone();
    }

    public double[] copyRelativePermittivity() {
        return relativePermittivity.clone();
    }

    public void setDirichletBoundary(int x, int y, int z, double potentialVolts) {
        validateFinite(potentialVolts, "boundary potential");
        int index = index(x, y, z);
        dirichletBoundary[index] = 1;
        this.potentialVolts[index] = potentialVolts;
    }

    public void clearDirichletBoundary(int x, int y, int z) {
        dirichletBoundary[index(x, y, z)] = 0;
    }

    public void setOuterBoundary(double potentialVolts) {
        validateFinite(potentialVolts, "boundary potential");
        for (int z = 0; z < zSize; z++) {
            for (int y = 0; y < ySize; y++) {
                setDirichletBoundary(0, y, z, potentialVolts);
                setDirichletBoundary(xSize - 1, y, z, potentialVolts);
            }
        }
        for (int z = 0; z < zSize; z++) {
            for (int x = 0; x < xSize; x++) {
                setDirichletBoundary(x, 0, z, potentialVolts);
                setDirichletBoundary(x, ySize - 1, z, potentialVolts);
            }
        }
        for (int y = 0; y < ySize; y++) {
            for (int x = 0; x < xSize; x++) {
                setDirichletBoundary(x, y, 0, potentialVolts);
                setDirichletBoundary(x, y, zSize - 1, potentialVolts);
            }
        }
    }

    public SolveStats solveRedBlackGaussSeidel(int maxIterations, double toleranceVolts, double relaxationOmega) {
        return solveRedBlackGaussSeidel(
                maxIterations,
                toleranceVolts,
                relaxationOmega,
                1.0 / VACUUM_PERMITTIVITY_FARADS_PER_METER
        );
    }

    public SolveStats solveRedBlackGaussSeidel(
            int maxIterations,
            double toleranceVolts,
            double relaxationOmega,
            double sourceCoefficient
    ) {
        if (maxIterations < 1) {
            throw new IllegalArgumentException("maxIterations must be positive");
        }
        if (!Double.isFinite(toleranceVolts) || toleranceVolts < 0.0) {
            throw new IllegalArgumentException("toleranceVolts must be finite and non-negative");
        }
        if (!Double.isFinite(relaxationOmega) || relaxationOmega <= 0.0 || relaxationOmega >= 2.0) {
            throw new IllegalArgumentException("relaxationOmega must be finite and in (0, 2)");
        }
        if (!Double.isFinite(sourceCoefficient)) {
            throw new IllegalArgumentException("sourceCoefficient must be finite");
        }

        int iterations = 0;
        double maxDelta = Double.POSITIVE_INFINITY;
        while (iterations < maxIterations && maxDelta > toleranceVolts) {
            maxDelta = 0.0;
            maxDelta = Math.max(maxDelta, sweepColor(0, relaxationOmega, sourceCoefficient));
            maxDelta = Math.max(maxDelta, sweepColor(1, relaxationOmega, sourceCoefficient));
            iterations++;
        }

        lastIterations = iterations;
        lastMaxDeltaVolts = maxDelta;
        lastMaxResidual = computeMaxResidual(sourceCoefficient);
        return new SolveStats(iterations, maxDelta <= toleranceVolts, maxDelta, lastMaxResidual);
    }

    public double computeMaxResidual() {
        return computeMaxResidual(1.0 / VACUUM_PERMITTIVITY_FARADS_PER_METER);
    }

    public double computeMaxResidual(double sourceCoefficient) {
        double maxResidual = 0.0;
        for (int z = 1; z < zSize - 1; z++) {
            for (int y = 1; y < ySize - 1; y++) {
                int base = z * xySize + y * xSize;
                for (int x = 1; x < xSize - 1; x++) {
                    int index = base + x;
                    if (dirichletBoundary[index] != 0) {
                        continue;
                    }

                    double center = potentialVolts[index];
                    double centerEpsilon = relativePermittivity[index];
                    double residual = facePermittivity(centerEpsilon, relativePermittivity[index - 1])
                            * (potentialVolts[index - 1] - center);
                    residual += facePermittivity(centerEpsilon, relativePermittivity[index + 1])
                            * (potentialVolts[index + 1] - center);
                    residual += facePermittivity(centerEpsilon, relativePermittivity[index - xSize])
                            * (potentialVolts[index - xSize] - center);
                    residual += facePermittivity(centerEpsilon, relativePermittivity[index + xSize])
                            * (potentialVolts[index + xSize] - center);
                    residual += facePermittivity(centerEpsilon, relativePermittivity[index - xySize])
                            * (potentialVolts[index - xySize] - center);
                    residual += facePermittivity(centerEpsilon, relativePermittivity[index + xySize])
                            * (potentialVolts[index + xySize] - center);
                    residual = residual / cellSizeSquared
                            + chargeDensityCoulombsPerCubicMeter[index] * sourceCoefficient;
                    maxResidual = Math.max(maxResidual, Math.abs(residual));
                }
            }
        }
        return maxResidual;
    }

    private double sweepColor(int color, double relaxationOmega, double sourceCoefficient) {
        double maxDelta = 0.0;
        for (int z = 1; z < zSize - 1; z++) {
            for (int y = 1; y < ySize - 1; y++) {
                int base = z * xySize + y * xSize;
                int xStart = 1 + ((color - y - z) & 1);
                for (int x = xStart; x < xSize - 1; x += 2) {
                    int index = base + x;
                    if (dirichletBoundary[index] != 0) {
                        continue;
                    }

                    double centerEpsilon = relativePermittivity[index];
                    double xMinusWeight = facePermittivity(centerEpsilon, relativePermittivity[index - 1]);
                    double xPlusWeight = facePermittivity(centerEpsilon, relativePermittivity[index + 1]);
                    double yMinusWeight = facePermittivity(centerEpsilon, relativePermittivity[index - xSize]);
                    double yPlusWeight = facePermittivity(centerEpsilon, relativePermittivity[index + xSize]);
                    double zMinusWeight = facePermittivity(centerEpsilon, relativePermittivity[index - xySize]);
                    double zPlusWeight = facePermittivity(centerEpsilon, relativePermittivity[index + xySize]);
                    double weightSum = xMinusWeight + xPlusWeight
                            + yMinusWeight + yPlusWeight
                            + zMinusWeight + zPlusWeight;

                    double source = cellSizeSquared
                            * chargeDensityCoulombsPerCubicMeter[index]
                            * sourceCoefficient;
                    double candidate = (xMinusWeight * potentialVolts[index - 1]
                            + xPlusWeight * potentialVolts[index + 1]
                            + yMinusWeight * potentialVolts[index - xSize]
                            + yPlusWeight * potentialVolts[index + xSize]
                            + zMinusWeight * potentialVolts[index - xySize]
                            + zPlusWeight * potentialVolts[index + xySize]
                            + source) / weightSum;
                    double previous = potentialVolts[index];
                    double relaxed = previous + relaxationOmega * (candidate - previous);
                    potentialVolts[index] = relaxed;
                    maxDelta = Math.max(maxDelta, Math.abs(relaxed - previous));
                }
            }
        }
        return maxDelta;
    }

    private int index(int x, int y, int z) {
        if (x < 0 || x >= xSize || y < 0 || y >= ySize || z < 0 || z >= zSize) {
            throw new IndexOutOfBoundsException("cell is outside Poisson region: " + x + ", " + y + ", " + z);
        }
        return z * xySize + y * xSize + x;
    }

    private static double facePermittivity(double first, double second) {
        return 0.5 * (first + second);
    }

    private static void validateRelativePermittivity(double epsilonRelative) {
        if (!Double.isFinite(epsilonRelative) || epsilonRelative < MIN_RELATIVE_PERMITTIVITY) {
            throw new IllegalArgumentException("relative permittivity must be finite and positive");
        }
    }

    private static void validateFinite(double value, String label) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(label + " must be finite");
        }
    }

    public record SolveStats(
            int iterations,
            boolean converged,
            double maxDeltaVolts,
            double maxResidual
    ) {
    }
}
