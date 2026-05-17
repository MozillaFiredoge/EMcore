package com.firedoge.emcore.internal.field;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class PreparedPoissonRegionBenchmark {
    private static final int[] DEFAULT_SIZES = {16, 32, 64};
    private static final int DEFAULT_MAX_ITERATIONS = 256;
    private static final double DEFAULT_TOLERANCE_VOLTS = 1.0e-4;
    private static final double DEFAULT_RELAXATION_OMEGA = 1.85;

    private PreparedPoissonRegionBenchmark() {
    }

    public static void main(String[] args) {
        Options options = Options.parse(args);

        System.out.println(String.format(
                Locale.ROOT,
                "PreparedPoissonRegion red-black Gauss-Seidel benchmark: iterations=%d tolerance=%.3gV omega=%.3g",
                options.maxIterations(),
                options.toleranceVolts(),
                options.relaxationOmega()
        ));
        System.out.println("size,cells,interiorCells,mode,iterations,converged,maxDeltaVolts,maxResidual,elapsedMs,megaCellIterationsPerSecond");

        for (int size : options.sizes()) {
            runCase(size, options, false);
            runCase(size, options, true);
        }
    }

    private static void runCase(int size, Options options, boolean warmStart) {
        PreparedPoissonRegion region = new PreparedPoissonRegion(size, size, size, 1.0);
        prepareBenchmarkRegion(region);

        if (warmStart) {
            region.solveRedBlackGaussSeidel(
                    Math.max(8, options.maxIterations() / 4),
                    options.toleranceVolts(),
                    options.relaxationOmega()
            );
        }

        long startNanos = System.nanoTime();
        PreparedPoissonRegion.SolveStats stats = region.solveRedBlackGaussSeidel(
                options.maxIterations(),
                options.toleranceVolts(),
                options.relaxationOmega()
        );
        long elapsedNanos = System.nanoTime() - startNanos;
        double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
        double megaCellIterationsPerSecond = elapsedSeconds == 0.0
                ? Double.POSITIVE_INFINITY
                : region.interiorCellCount() * (double) stats.iterations() / elapsedSeconds / 1_000_000.0;

        System.out.println(String.format(
                Locale.ROOT,
                "%d,%d,%d,%s,%d,%s,%.9g,%.9g,%.3f,%.3f",
                size,
                region.cellCount(),
                region.interiorCellCount(),
                warmStart ? "warm" : "cold",
                stats.iterations(),
                stats.converged(),
                stats.maxDeltaVolts(),
                stats.maxResidual(),
                elapsedNanos / 1_000_000.0,
                megaCellIterationsPerSecond
        ));
    }

    private static void prepareBenchmarkRegion(PreparedPoissonRegion region) {
        region.setOuterBoundary(0.0);

        int centerX = region.xSize() / 2;
        int centerY = region.ySize() / 2;
        int centerZ = region.zSize() / 2;
        int radius = Math.max(1, region.xSize() / 12);
        double density = 1.0e-12;

        for (int z = centerZ - radius; z <= centerZ + radius; z++) {
            for (int y = centerY - radius; y <= centerY + radius; y++) {
                for (int x = centerX - radius; x <= centerX + radius; x++) {
                    int dx = x - centerX;
                    int dy = y - centerY;
                    int dz = z - centerZ;
                    if (dx * dx + dy * dy + dz * dz <= radius * radius) {
                        region.setChargeDensity(x, y, z, density);
                    }
                }
            }
        }
    }

    private record Options(
            int[] sizes,
            int maxIterations,
            double toleranceVolts,
            double relaxationOmega
    ) {
        private static Options parse(String[] args) {
            int[] sizes = DEFAULT_SIZES;
            int maxIterations = DEFAULT_MAX_ITERATIONS;
            double toleranceVolts = DEFAULT_TOLERANCE_VOLTS;
            double relaxationOmega = DEFAULT_RELAXATION_OMEGA;

            for (String arg : args) {
                if (arg.startsWith("--sizes=")) {
                    sizes = parseSizes(arg.substring("--sizes=".length()));
                } else if (arg.startsWith("--iterations=")) {
                    maxIterations = Integer.parseInt(arg.substring("--iterations=".length()));
                } else if (arg.startsWith("--tolerance=")) {
                    toleranceVolts = Double.parseDouble(arg.substring("--tolerance=".length()));
                } else if (arg.startsWith("--omega=")) {
                    relaxationOmega = Double.parseDouble(arg.substring("--omega=".length()));
                } else {
                    throw new IllegalArgumentException("Unknown benchmark option: " + arg);
                }
            }

            return new Options(sizes, maxIterations, toleranceVolts, relaxationOmega);
        }

        private static int[] parseSizes(String rawSizes) {
            String[] parts = rawSizes.split(",");
            List<Integer> parsed = new ArrayList<>();
            for (String part : parts) {
                if (part.isBlank()) {
                    continue;
                }

                int size = Integer.parseInt(part.trim());
                if (size < 3) {
                    throw new IllegalArgumentException("Poisson benchmark sizes must be at least 3");
                }
                parsed.add(size);
            }
            if (parsed.isEmpty()) {
                throw new IllegalArgumentException("At least one benchmark size is required");
            }

            int[] sizes = new int[parsed.size()];
            for (int index = 0; index < parsed.size(); index++) {
                sizes[index] = parsed.get(index);
            }
            return sizes;
        }
    }
}
