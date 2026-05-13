package com.firedoge.emcore;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue DEBUG_LOGGING = BUILDER
            .comment("Whether Electromagnetics Core should write additional diagnostic logs.")
            .define("debugLogging", false);

    public static final ModConfigSpec.IntValue CIRCUIT_TICKS_PER_SECOND = BUILDER
            .comment("How often circuit networks are solved per second.")
            .defineInRange("circuitTicksPerSecond", 20, 1, 200);

    public static final ModConfigSpec.IntValue MAX_CIRCUIT_SOLVER_ITERATIONS = BUILDER
            .comment("Maximum iterations a circuit solve may use before reporting a non-converged state.")
            .defineInRange("maxCircuitSolverIterations", 64, 1, 4096);

    static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {
    }
}
