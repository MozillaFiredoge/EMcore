package com.firedoge.emcore.internal.field;

final class PreparedPoissonRegionSmokeTest {
    private PreparedPoissonRegionSmokeTest() {
    }

    static void runAll() {
        zeroBoundaryAndNoChargeRemainZero();
        positiveChargeCreatesPositivePotential();
        fixedPlateBoundaryCreatesPotentialGradient();
    }

    private static void zeroBoundaryAndNoChargeRemainZero() {
        PreparedPoissonRegion region = new PreparedPoissonRegion(9, 9, 9, 1.0);
        region.setOuterBoundary(0.0);

        PreparedPoissonRegion.SolveStats stats = region.solveRedBlackGaussSeidel(32, 1.0e-12, 1.5);

        assertTrue(stats.converged(), "zero field converged");
        assertClose(0.0, region.potential(4, 4, 4), 1.0e-12, "zero center potential");
        assertClose(0.0, stats.maxDeltaVolts(), 1.0e-12, "zero max delta");
    }

    private static void positiveChargeCreatesPositivePotential() {
        PreparedPoissonRegion region = new PreparedPoissonRegion(11, 11, 11, 1.0);
        region.setOuterBoundary(0.0);
        region.setChargeDensity(5, 5, 5, 1.0e-12);

        PreparedPoissonRegion.SolveStats stats = region.solveRedBlackGaussSeidel(512, 1.0e-9, 1.7);

        assertTrue(stats.maxDeltaVolts() < 1.0e-6, "charged solve settled");
        assertTrue(region.potential(5, 5, 5) > 0.0, "positive charge creates positive potential");
        assertTrue(region.potential(5, 5, 5) > region.potential(4, 5, 5), "center is above neighbor");
    }

    private static void fixedPlateBoundaryCreatesPotentialGradient() {
        PreparedPoissonRegion region = new PreparedPoissonRegion(13, 9, 9, 1.0);
        region.setOuterBoundary(0.0);
        for (int z = 0; z < region.zSize(); z++) {
            for (int y = 0; y < region.ySize(); y++) {
                region.setDirichletBoundary(0, y, z, 1.0);
                region.setDirichletBoundary(region.xSize() - 1, y, z, 0.0);
            }
        }

        region.solveRedBlackGaussSeidel(768, 1.0e-9, 1.8);

        double nearPositivePlate = region.potential(1, 4, 4);
        double center = region.potential(6, 4, 4);
        double nearGroundPlate = region.potential(11, 4, 4);
        assertTrue(nearPositivePlate > center, "potential falls away from positive plate");
        assertTrue(center > nearGroundPlate, "potential falls toward ground plate");
        assertTrue(center > 0.0 && center < 1.0, "center potential remains between plates");
    }

    private static void assertClose(double expected, double actual, double tolerance, String label) {
        if (Math.abs(expected - actual) > tolerance) {
            throw new AssertionError(label + ": expected " + expected + " but got " + actual);
        }
    }

    private static void assertTrue(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
    }
}
