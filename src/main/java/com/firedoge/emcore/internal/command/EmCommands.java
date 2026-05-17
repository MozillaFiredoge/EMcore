package com.firedoge.emcore.internal.command;

import com.firedoge.emcore.EMcore;
import com.firedoge.emcore.api.Electromagnetics;
import com.firedoge.emcore.api.circuit.CircuitAccess;
import com.firedoge.emcore.api.circuit.CircuitDiagnostic;
import com.firedoge.emcore.api.circuit.CircuitPort;
import com.firedoge.emcore.api.circuit.CircuitSample;
import com.firedoge.emcore.api.circuit.CircuitSnapshot;
import com.firedoge.emcore.api.circuit.FieldInducedVoltageSourceElement;
import com.firedoge.emcore.api.circuit.ResistorElement;
import com.firedoge.emcore.api.circuit.VoltageSourceElement;
import com.firedoge.emcore.api.circuit.WireElement;
import com.firedoge.emcore.api.field.CoilRegion;
import com.firedoge.emcore.api.field.FieldAccess;
import com.firedoge.emcore.api.field.FieldDiagnostic;
import com.firedoge.emcore.api.field.FieldRegion;
import com.firedoge.emcore.api.field.FieldSample;
import com.firedoge.emcore.api.field.FieldSnapshot;
import com.firedoge.emcore.api.field.FieldSolveResult;
import com.firedoge.emcore.api.field.FieldSource;
import com.firedoge.emcore.api.field.FluxSample;
import com.firedoge.emcore.api.field.MagneticFieldSample;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public final class EmCommands {
    private static final double TEST_VOLTAGE_VOLTS = 12.0;
    private static final double CONFLICT_TEST_VOLTAGE_VOLTS = 5.0;
    private static final double TEST_RESISTANCE_OHMS = 6.0;
    private static final double TEST_FIELD_CHARGE_COULOMBS = 1.0e-12;
    private static final double TEST_FIELD_CURRENT_DENSITY_AMPS_PER_SQUARE_METER = 10_000.0;
    private static final int MAX_TRANSIENT_DEBUG_STEPS = 10_000;
    private static final ResourceLocation TEST_OWNER = ResourceLocation.fromNamespaceAndPath(EMcore.MODID, "debug_test");

    private final Map<ResourceKey<Level>, TestCircuit> testCircuits = new HashMap<>();
    private final Map<ResourceKey<Level>, TestField> testFields = new HashMap<>();

    @SubscribeEvent
    public void register(RegisterCommandsEvent event) {
        registerCommands(event.getDispatcher());
    }

    private void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("emcore")
                .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("circuit")
                        .then(Commands.literal("list")
                                .executes(EmCommands::listCircuit))
                        .then(Commands.literal("probe")
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(EmCommands::probeCircuit)))
                        .then(Commands.literal("transient")
                                .then(Commands.literal("step")
                                        .then(Commands.argument("dtSeconds", DoubleArgumentType.doubleArg(Double.MIN_VALUE))
                                                .executes(context -> stepTransientCircuit(context, 1))
                                                .then(Commands.argument("steps", IntegerArgumentType.integer(1, MAX_TRANSIENT_DEBUG_STEPS))
                                                        .executes(context -> stepTransientCircuit(
                                                                context,
                                                                IntegerArgumentType.getInteger(context, "steps")
                                                        )))))
                                .then(Commands.literal("probe")
                                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                                .then(Commands.argument("dtSeconds", DoubleArgumentType.doubleArg(Double.MIN_VALUE))
                                                        .executes(context -> probeTransientCircuit(context, 1))
                                                        .then(Commands.argument("steps", IntegerArgumentType.integer(1, MAX_TRANSIENT_DEBUG_STEPS))
                                                                .executes(context -> probeTransientCircuit(
                                                                        context,
                                                                        IntegerArgumentType.getInteger(context, "steps")
                                                                )))))))
                        .then(Commands.literal("test")
                                .then(Commands.literal("create")
                                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                                .executes(this::createTestCircuit)))
                                .then(Commands.literal("short")
                                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                                .executes(this::createShortTestCircuit)))
                                .then(Commands.literal("conflict")
                                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                                .executes(this::createConflictTestCircuit)))
                                .then(Commands.literal("coil")
                                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                                .executes(this::createFieldCoupledCoilCircuit)))
                                .then(Commands.literal("clear")
                                        .executes(this::clearTestCircuit))))
                .then(Commands.literal("field")
                        .then(Commands.literal("regions")
                                .executes(EmCommands::listFieldRegions))
                        .then(Commands.literal("coils")
                                .executes(EmCommands::listFieldCoils))
                        .then(Commands.literal("sample")
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(EmCommands::sampleField)))
                        .then(Commands.literal("flux")
                                .then(Commands.argument("coil", StringArgumentType.greedyString())
                                        .executes(EmCommands::sampleFieldFlux)))
                        .then(Commands.literal("solve")
                                .then(Commands.argument("region", StringArgumentType.greedyString())
                                        .executes(EmCommands::solveFieldRegion)))
                        .then(Commands.literal("request")
                                .then(Commands.argument("region", StringArgumentType.greedyString())
                                        .executes(EmCommands::requestFieldSolve)))
                        .then(Commands.literal("test")
                                .then(Commands.literal("create")
                                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                                .executes(this::createTestField)))
                                .then(Commands.literal("magnetic")
                                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                                .executes(this::createTestMagneticField)))
                                .then(Commands.literal("current")
                                        .then(Commands.argument(
                                                        "ampsPerSquareMeter",
                                                        DoubleArgumentType.doubleArg()
                                                )
                                                .executes(this::setTestMagneticCurrent)))
                                .then(Commands.literal("clear")
                                        .executes(this::clearTestField)))));
    }

    private static int listCircuit(CommandContext<CommandSourceStack> context) {
        ServerLevel level = context.getSource().getLevel();
        CircuitSnapshot snapshot = Electromagnetics.api().circuits().snapshot(level);

        context.getSource().sendSuccess(() -> Component.literal(String.format(
                Locale.ROOT,
                "EMcore circuit %s: %d nodes, %d ports, %d terminals, %d elements, %d diagnostics, %d samples, t=%s s",
                level.dimension().location(),
                snapshot.nodes().size(),
                snapshot.ports().size(),
                snapshot.terminals().size(),
                snapshot.elements().size(),
                snapshot.diagnostics().size(),
                snapshot.samples().size(),
                format(snapshot.simulatedTimeSeconds())
        )).withStyle(ChatFormatting.AQUA), false);

        if (!snapshot.diagnostics().isEmpty()) {
            context.getSource().sendSuccess(() -> Component.literal(
                    "Diagnostics: " + snapshot.diagnostics().size()).withStyle(ChatFormatting.RED), false);
            for (CircuitDiagnostic diagnostic : snapshot.diagnostics()) {
                context.getSource().sendSuccess(() -> describeDiagnostic(diagnostic), false);
            }
        }

        return Command.SINGLE_SUCCESS;
    }

    private static int listFieldRegions(CommandContext<CommandSourceStack> context) {
        ServerLevel level = context.getSource().getLevel();
        FieldSnapshot snapshot = Electromagnetics.api().fields().snapshot(level);

        context.getSource().sendSuccess(() -> Component.literal(String.format(
                Locale.ROOT,
                "EMcore fields %s: %d regions, %d sources, %d dirty regions, %d diagnostics, version=%d",
                level.dimension().location(),
                snapshot.regions().size(),
                snapshot.sources().size(),
                snapshot.dirtyRegionIds().size(),
                snapshot.diagnostics().size(),
                snapshot.version()
        )).withStyle(ChatFormatting.AQUA), false);

        snapshot.regions().forEach(region -> context.getSource().sendSuccess(() -> Component.literal(String.format(
                Locale.ROOT,
                "%s bounds=[%s,%s,%s -> %s,%s,%s] cell=%sm estimatedCells=%d%s",
                region.id(),
                format(region.bounds().minX),
                format(region.bounds().minY),
                format(region.bounds().minZ),
                format(region.bounds().maxX),
                format(region.bounds().maxY),
                format(region.bounds().maxZ),
                format(region.cellSizeMeters()),
                region.estimatedCellCount(),
                snapshot.dirtyRegionIds().contains(region.id()) ? " dirty" : ""
        )).withStyle(snapshot.dirtyRegionIds().contains(region.id())
                ? ChatFormatting.YELLOW
                : ChatFormatting.GRAY), false));

        if (!snapshot.diagnostics().isEmpty()) {
            context.getSource().sendSuccess(() -> Component.literal(
                    "Diagnostics: " + snapshot.diagnostics().size()).withStyle(ChatFormatting.RED), false);
            for (FieldDiagnostic diagnostic : snapshot.diagnostics()) {
                context.getSource().sendSuccess(() -> describeFieldDiagnostic(diagnostic), false);
            }
        }

        return Command.SINGLE_SUCCESS;
    }

    private static int listFieldCoils(CommandContext<CommandSourceStack> context) {
        ServerLevel level = context.getSource().getLevel();
        List<CoilRegion> coils = Electromagnetics.api().fields().coils(level);

        context.getSource().sendSuccess(() -> Component.literal(String.format(
                Locale.ROOT,
                "EMcore field coils %s: %d coils",
                level.dimension().location(),
                coils.size()
        )).withStyle(ChatFormatting.AQUA), false);

        coils.forEach(coil -> context.getSource().sendSuccess(() -> Component.literal(String.format(
                Locale.ROOT,
                "%s region=%s center=(%s,%s,%s) normal=%s area=%sm^2 turns=%d",
                coil.id(),
                coil.regionId(),
                format(coil.center().x),
                format(coil.center().y),
                format(coil.center().z),
                coil.normal(),
                format(coil.areaSquareMeters()),
                coil.turns()
        )).withStyle(ChatFormatting.GRAY), false));

        return Command.SINGLE_SUCCESS;
    }

    private static int sampleField(CommandContext<CommandSourceStack> context) {
        ServerLevel level = context.getSource().getLevel();
        BlockPos position = BlockPosArgument.getBlockPos(context, "pos");
        Vec3 samplePosition = Vec3.atCenterOf(position);

        FieldSample sample = Electromagnetics.api().fields().sample(level, samplePosition).orElse(null);
        if (sample == null) {
            context.getSource().sendSuccess(() -> Component.literal("No EMcore field region contains "
                    + format(position)).withStyle(ChatFormatting.YELLOW), false);
            return 0;
        }
        MagneticFieldSample magneticSample = Electromagnetics.api().fields().sampleMagneticField(level, samplePosition);

        context.getSource().sendSuccess(() -> Component.literal(String.format(
                Locale.ROOT,
                "%s E=(%s,%s,%s)V/m B=(%s,%s,%s)T phi=%sV rho=%sC/m^3 u=%sJ/m^3 regions=%d%s",
                format(position),
                format(sample.electricFieldVoltsPerMeter().x),
                format(sample.electricFieldVoltsPerMeter().y),
                format(sample.electricFieldVoltsPerMeter().z),
                format(magneticSample.fluxDensityTesla().x),
                format(magneticSample.fluxDensityTesla().y),
                format(magneticSample.fluxDensityTesla().z),
                format(sample.potentialVolts()),
                format(sample.chargeDensityCoulombsPerCubicMeter()),
                format(sample.energyDensityJoulesPerCubicMeter()),
                sample.regionIds().size(),
                sample.stale() ? " stale" : ""
        )).withStyle(sample.stale() ? ChatFormatting.YELLOW : ChatFormatting.AQUA), false);

        return Command.SINGLE_SUCCESS;
    }

    private static int sampleFieldFlux(CommandContext<CommandSourceStack> context) {
        ServerLevel level = context.getSource().getLevel();
        ResourceLocation coilId = parseId(StringArgumentType.getString(context, "coil"));
        FluxSample sample = Electromagnetics.api().fields().sampleCoil(level, coilId).orElse(null);
        if (sample == null) {
            context.getSource().sendSuccess(() -> Component.literal("No EMcore field coil named " + coilId)
                    .withStyle(ChatFormatting.YELLOW), false);
            return 0;
        }

        String inducedVoltage = sample.inducedVoltageVolts().isPresent()
                ? format(sample.inducedVoltageVolts().getAsDouble()) + "V"
                : "n/a";
        context.getSource().sendSuccess(() -> Component.literal(String.format(
                Locale.ROOT,
                "%s region=%s normal=%s Bn=%sT flux=%sWb linkage=%sWb induced=%s%s",
                sample.probeId(),
                sample.regionId(),
                sample.normal(),
                format(sample.normalFluxDensityTesla()),
                format(sample.fluxWebers()),
                format(sample.fluxLinkageWebers()),
                inducedVoltage,
                sample.stale() ? " stale" : ""
        )).withStyle(sample.stale() ? ChatFormatting.YELLOW : ChatFormatting.AQUA), false);

        return Command.SINGLE_SUCCESS;
    }

    private static int solveFieldRegion(CommandContext<CommandSourceStack> context) {
        ServerLevel level = context.getSource().getLevel();
        ResourceLocation regionId = parseId(StringArgumentType.getString(context, "region"));
        FieldAccess fields = Electromagnetics.api().fields();
        FieldSolveResult result = fields.solve(level, regionId).orElse(null);

        if (result == null) {
            context.getSource().sendSuccess(() -> Component.literal("No EMcore field region named " + regionId)
                    .withStyle(ChatFormatting.YELLOW), false);
            return 0;
        }

        context.getSource().sendSuccess(() -> Component.literal(String.format(
                Locale.ROOT,
                "Solved EMcore field %s: %dx%dx%d cells=%d sources=%d iterations=%d converged=%s maxDelta=%sV residual=%s elapsed=%sms",
                result.regionId(),
                result.xSize(),
                result.ySize(),
                result.zSize(),
                result.cellCount(),
                result.sourceCount(),
                result.iterations(),
                result.converged(),
                format(result.maxDeltaVolts()),
                format(result.maxResidual()),
                format(result.elapsedMillis())
        )).withStyle(result.converged() ? ChatFormatting.AQUA : ChatFormatting.YELLOW), false);

        return Command.SINGLE_SUCCESS;
    }

    private static int requestFieldSolve(CommandContext<CommandSourceStack> context) {
        ServerLevel level = context.getSource().getLevel();
        ResourceLocation regionId = parseId(StringArgumentType.getString(context, "region"));
        boolean accepted = Electromagnetics.api().fields().requestSolve(level, regionId);

        context.getSource().sendSuccess(() -> Component.literal(accepted
                ? "Queued EMcore field solve for " + regionId
                : "No EMcore field region named " + regionId).withStyle(accepted
                        ? ChatFormatting.AQUA
                        : ChatFormatting.YELLOW), false);

        return accepted ? Command.SINGLE_SUCCESS : 0;
    }

    private int createTestField(CommandContext<CommandSourceStack> context) {
        ServerLevel level = context.getSource().getLevel();
        BlockPos base = BlockPosArgument.getBlockPos(context, "pos");
        FieldAccess fields = Electromagnetics.api().fields();

        unregisterTestField(level);

        ResourceLocation regionId = testId("field/region");
        ResourceLocation chargeId = testId("field/charge");
        AABB bounds = new AABB(
                base.getX() - 8.0,
                base.getY() - 8.0,
                base.getZ() - 8.0,
                base.getX() + 9.0,
                base.getY() + 9.0,
                base.getZ() + 9.0
        );
        FieldRegion region = new FieldRegion(regionId, bounds, 1.0);
        FieldSource charge = FieldSource.pointCharge(
                chargeId,
                regionId,
                Vec3.atCenterOf(base),
                TEST_FIELD_CHARGE_COULOMBS
        );

        fields.registerRegion(level, region);
        fields.registerSource(level, charge);
        testFields.put(level.dimension(), new TestField(regionId, List.of(chargeId), List.of()));

        context.getSource().sendSuccess(() -> Component.literal(String.format(
                Locale.ROOT,
                "Created EMcore test field %s at %s with %.6gC point charge; run /emcore field solve %s",
                regionId,
                format(base),
                TEST_FIELD_CHARGE_COULOMBS,
                regionId
        )).withStyle(ChatFormatting.AQUA), false);

        return Command.SINGLE_SUCCESS;
    }

    private int createTestMagneticField(CommandContext<CommandSourceStack> context) {
        ServerLevel level = context.getSource().getLevel();
        BlockPos base = BlockPosArgument.getBlockPos(context, "pos");
        FieldAccess fields = Electromagnetics.api().fields();

        unregisterTestField(level);

        ResourceLocation regionId = testId("field/region");
        ResourceLocation currentId = testId("field/current");
        ResourceLocation coilId = testId("field/coil");
        AABB bounds = new AABB(
                base.getX() - 8.0,
                base.getY() - 8.0,
                base.getZ() - 8.0,
                base.getX() + 9.0,
                base.getY() + 9.0,
                base.getZ() + 9.0
        );
        FieldRegion region = new FieldRegion(regionId, bounds, 1.0);
        FieldSource current = FieldSource.currentDensity(
                currentId,
                regionId,
                Vec3.atCenterOf(base),
                1.0,
                new Vec3(0.0, TEST_FIELD_CURRENT_DENSITY_AMPS_PER_SQUARE_METER, 0.0)
        );
        CoilRegion coil = new CoilRegion(
                coilId,
                regionId,
                Vec3.atCenterOf(base.east()),
                Direction.NORTH,
                1.0,
                10
        );

        fields.registerRegion(level, region);
        fields.registerSource(level, current);
        fields.registerCoil(level, coil);
        testFields.put(level.dimension(), new TestField(regionId, List.of(currentId), List.of(coilId)));

        context.getSource().sendSuccess(() -> Component.literal(String.format(
                Locale.ROOT,
                "Created EMcore magnetic test field %s at %s with J=(0,%s,0)A/m^2 and coil %s; run /emcore field solve %s then /emcore field flux %s",
                regionId,
                format(base),
                format(TEST_FIELD_CURRENT_DENSITY_AMPS_PER_SQUARE_METER),
                coilId,
                regionId,
                coilId
        )).withStyle(ChatFormatting.AQUA), false);

        return Command.SINGLE_SUCCESS;
    }

    private int clearTestField(CommandContext<CommandSourceStack> context) {
        ServerLevel level = context.getSource().getLevel();
        boolean removed = unregisterTestField(level);

        context.getSource().sendSuccess(() -> Component.literal(removed
                ? "Cleared EMcore test field"
                : "No EMcore test field is registered in this dimension").withStyle(ChatFormatting.YELLOW), false);

        return Command.SINGLE_SUCCESS;
    }

    private int setTestMagneticCurrent(CommandContext<CommandSourceStack> context) {
        ServerLevel level = context.getSource().getLevel();
        double currentDensity = DoubleArgumentType.getDouble(context, "ampsPerSquareMeter");
        if (!Double.isFinite(currentDensity)) {
            throw new IllegalArgumentException("ampsPerSquareMeter must be finite");
        }

        TestField previous = testFields.get(level.dimension());
        ResourceLocation currentId = testId("field/current");
        if (previous == null || !previous.sourceIds().contains(currentId)) {
            context.getSource().sendSuccess(() -> Component.literal(
                    "No EMcore magnetic test field is registered; run /emcore field test magnetic <pos> first"
            ).withStyle(ChatFormatting.YELLOW), false);
            return 0;
        }

        FieldRegion region = Electromagnetics.api().fields().regions(level).stream()
                .filter(candidate -> candidate.id().equals(previous.regionId()))
                .findFirst()
                .orElse(null);
        if (region == null) {
            context.getSource().sendSuccess(() -> Component.literal("Missing EMcore test field region "
                    + previous.regionId()).withStyle(ChatFormatting.YELLOW), false);
            return 0;
        }

        Vec3 center = new Vec3(
                (region.bounds().minX + region.bounds().maxX) * 0.5,
                (region.bounds().minY + region.bounds().maxY) * 0.5,
                (region.bounds().minZ + region.bounds().maxZ) * 0.5
        );
        Electromagnetics.api().fields().registerSource(level, FieldSource.currentDensity(
                currentId,
                region.id(),
                center,
                1.0,
                new Vec3(0.0, currentDensity, 0.0)
        ));

        context.getSource().sendSuccess(() -> Component.literal(String.format(
                Locale.ROOT,
                "Updated EMcore magnetic test current to J=(0,%s,0)A/m^2; run /emcore field solve %s",
                format(currentDensity),
                region.id()
        )).withStyle(ChatFormatting.AQUA), false);

        return Command.SINGLE_SUCCESS;
    }

    private int createShortTestCircuit(CommandContext<CommandSourceStack> context) {
        ServerLevel level = context.getSource().getLevel();
        BlockPos base = BlockPosArgument.getBlockPos(context, "pos");
        CircuitAccess circuits = Electromagnetics.api().circuits();

        unregisterTestCircuit(level);

        CircuitPort positive = testPort("short_positive", base, Direction.UP);
        CircuitPort negative = testPort("short_negative", base.east(), Direction.UP);
        ResourceLocation voltageSourceId = testId("short_voltage_source");
        ResourceLocation shortWireId = testId("short_wire");

        circuits.registerElement(level, new VoltageSourceElement(
                voltageSourceId,
                positive,
                negative,
                TEST_VOLTAGE_VOLTS
        ));
        circuits.registerElement(level, new WireElement(shortWireId, positive, negative));

        testCircuits.put(level.dimension(), new TestCircuit(
                List.of(positive, negative),
                List.of(voltageSourceId, shortWireId)
        ));

        context.getSource().sendSuccess(() -> Component.literal(
                "Created EMcore short test circuit; /emcore circuit list should report VOLTAGE_SOURCE_SHORT"
        ).withStyle(ChatFormatting.YELLOW), false);

        return Command.SINGLE_SUCCESS;
    }

    private int createConflictTestCircuit(CommandContext<CommandSourceStack> context) {
        ServerLevel level = context.getSource().getLevel();
        BlockPos base = BlockPosArgument.getBlockPos(context, "pos");
        CircuitAccess circuits = Electromagnetics.api().circuits();

        unregisterTestCircuit(level);

        CircuitPort positive = testPort("conflict_positive", base, Direction.UP);
        CircuitPort negative = testPort("conflict_negative", base.east(), Direction.UP);
        ResourceLocation firstSourceId = testId("conflict_voltage_source_12v");
        ResourceLocation secondSourceId = testId("conflict_voltage_source_5v");

        circuits.registerElement(level, new VoltageSourceElement(
                firstSourceId,
                positive,
                negative,
                TEST_VOLTAGE_VOLTS
        ));
        circuits.registerElement(level, new VoltageSourceElement(
                secondSourceId,
                positive,
                negative,
                CONFLICT_TEST_VOLTAGE_VOLTS
        ));

        testCircuits.put(level.dimension(), new TestCircuit(
                List.of(positive, negative),
                List.of(firstSourceId, secondSourceId)
        ));

        context.getSource().sendSuccess(() -> Component.literal(
                "Created EMcore conflict test circuit; /emcore circuit list should report VOLTAGE_SOURCE_CONFLICT"
        ).withStyle(ChatFormatting.YELLOW), false);

        return Command.SINGLE_SUCCESS;
    }

    private int createFieldCoupledCoilCircuit(CommandContext<CommandSourceStack> context) {
        ServerLevel level = context.getSource().getLevel();
        BlockPos base = BlockPosArgument.getBlockPos(context, "pos");
        CircuitAccess circuits = Electromagnetics.api().circuits();

        unregisterTestCircuit(level);

        CircuitPort sourcePositive = testPort("coil_source_positive", base, Direction.UP);
        CircuitPort sourceNegative = testPort("coil_source_negative", base.south(), Direction.UP);
        CircuitPort resistorPositive = testPort("coil_resistor_positive", base.east(), Direction.UP);
        CircuitPort resistorNegative = testPort("coil_resistor_negative", base.east().south(), Direction.UP);

        ResourceLocation coilId = testId("field/coil");
        ResourceLocation sourceId = testId("coil_induced_source");
        ResourceLocation resistorId = testId("coil_resistor");
        ResourceLocation positiveWireId = testId("coil_positive_wire");
        ResourceLocation negativeWireId = testId("coil_negative_wire");

        circuits.registerElement(level, new FieldInducedVoltageSourceElement(
                sourceId,
                sourcePositive,
                sourceNegative,
                coilId
        ));
        circuits.registerElement(level, new ResistorElement(
                resistorId,
                resistorPositive,
                resistorNegative,
                TEST_RESISTANCE_OHMS
        ));
        circuits.registerElement(level, new WireElement(positiveWireId, sourcePositive, resistorPositive));
        circuits.registerElement(level, new WireElement(negativeWireId, sourceNegative, resistorNegative));

        testCircuits.put(level.dimension(), new TestCircuit(
                List.of(sourcePositive, sourceNegative, resistorPositive, resistorNegative),
                List.of(sourceId, resistorId, positiveWireId, negativeWireId)
        ));

        context.getSource().sendSuccess(() -> Component.literal(String.format(
                Locale.ROOT,
                "Created EMcore coil-coupled test circuit at %s using coil %s and %.6g ohm load",
                format(base),
                coilId,
                TEST_RESISTANCE_OHMS
        )).withStyle(ChatFormatting.AQUA), false);
        context.getSource().sendSuccess(() -> Component.literal(
                "Prime/update the coil with /emcore field flux " + coilId
                        + ", /emcore field test current <J>, /emcore field solve emcore:debug_test/field/region"
        ).withStyle(ChatFormatting.GRAY), false);

        return Command.SINGLE_SUCCESS;
    }

    private static int probeCircuit(CommandContext<CommandSourceStack> context) {
        ServerLevel level = context.getSource().getLevel();
        BlockPos position = BlockPosArgument.getBlockPos(context, "pos");
        CircuitSnapshot snapshot = Electromagnetics.api().circuits().snapshot(level);
        Map<CircuitPort, CircuitSample> samples = samplesByPort(snapshot);

        int matches = 0;
        for (CircuitPort port : snapshot.ports()) {
            if (!port.position().equals(position)) {
                continue;
            }

            matches++;
            CircuitSample sample = samples.get(port);
            context.getSource().sendSuccess(() -> describePort(port, sample), false);
        }

        if (matches == 0) {
            context.getSource().sendSuccess(() -> Component.literal("No EMcore circuit ports at " + format(position))
                    .withStyle(ChatFormatting.YELLOW), false);
        }

        return matches;
    }

    private static int stepTransientCircuit(CommandContext<CommandSourceStack> context, int steps) {
        ServerLevel level = context.getSource().getLevel();
        double timeStepSeconds = DoubleArgumentType.getDouble(context, "dtSeconds");
        CircuitSnapshot snapshot = runTransientSteps(level, timeStepSeconds, steps);

        context.getSource().sendSuccess(() -> Component.literal(String.format(
                Locale.ROOT,
                "EMcore transient %s: dt=%s s, steps=%d, nodes=%d, ports=%d, terminals=%d, elements=%d, diagnostics=%d, samples=%d, t=%s s",
                level.dimension().location(),
                format(timeStepSeconds),
                steps,
                snapshot.nodes().size(),
                snapshot.ports().size(),
                snapshot.terminals().size(),
                snapshot.elements().size(),
                snapshot.diagnostics().size(),
                snapshot.samples().size(),
                format(snapshot.simulatedTimeSeconds())
        )).withStyle(ChatFormatting.LIGHT_PURPLE), false);

        if (!snapshot.diagnostics().isEmpty()) {
            context.getSource().sendSuccess(() -> Component.literal(
                    "Diagnostics: " + snapshot.diagnostics().size()).withStyle(ChatFormatting.RED), false);
            for (CircuitDiagnostic diagnostic : snapshot.diagnostics()) {
                context.getSource().sendSuccess(() -> describeDiagnostic(diagnostic), false);
            }
        }

        return Command.SINGLE_SUCCESS;
    }

    private static int probeTransientCircuit(CommandContext<CommandSourceStack> context, int steps) {
        ServerLevel level = context.getSource().getLevel();
        BlockPos position = BlockPosArgument.getBlockPos(context, "pos");
        double timeStepSeconds = DoubleArgumentType.getDouble(context, "dtSeconds");
        CircuitSnapshot snapshot = runTransientSteps(level, timeStepSeconds, steps);
        Map<CircuitPort, CircuitSample> samples = samplesByPort(snapshot);

        context.getSource().sendSuccess(() -> Component.literal(String.format(
                Locale.ROOT,
                "EMcore transient probe %s: dt=%s s, steps=%d, t=%s s",
                format(position),
                format(timeStepSeconds),
                steps,
                format(snapshot.simulatedTimeSeconds())
        )).withStyle(ChatFormatting.LIGHT_PURPLE), false);

        int matches = 0;
        for (CircuitPort port : snapshot.ports()) {
            if (!port.position().equals(position)) {
                continue;
            }

            matches++;
            CircuitSample sample = samples.get(port);
            context.getSource().sendSuccess(() -> describePort(port, sample), false);
        }

        if (!snapshot.diagnostics().isEmpty()) {
            context.getSource().sendSuccess(() -> Component.literal(
                    "Diagnostics: " + snapshot.diagnostics().size()).withStyle(ChatFormatting.RED), false);
            for (CircuitDiagnostic diagnostic : snapshot.diagnostics()) {
                context.getSource().sendSuccess(() -> describeDiagnostic(diagnostic), false);
            }
        }

        if (matches == 0) {
            context.getSource().sendSuccess(() -> Component.literal("No EMcore transient circuit ports at "
                    + format(position)).withStyle(ChatFormatting.YELLOW), false);
        }

        return matches;
    }

    private static CircuitSnapshot runTransientSteps(ServerLevel level, double timeStepSeconds, int steps) {
        CircuitSnapshot snapshot = null;
        CircuitAccess circuits = Electromagnetics.api().circuits();
        for (int step = 0; step < steps; step++) {
            snapshot = circuits.stepTransient(level, timeStepSeconds);
        }
        if (snapshot == null) {
            throw new IllegalArgumentException("steps must be greater than zero");
        }
        return snapshot;
    }

    private int createTestCircuit(CommandContext<CommandSourceStack> context) {
        ServerLevel level = context.getSource().getLevel();
        BlockPos base = BlockPosArgument.getBlockPos(context, "pos");
        CircuitAccess circuits = Electromagnetics.api().circuits();

        unregisterTestCircuit(level);

        CircuitPort sourcePositive = testPort("source_positive", base, Direction.UP);
        CircuitPort sourceNegative = testPort("source_negative", base.south(), Direction.UP);
        CircuitPort resistorPositive = testPort("resistor_positive", base.east(), Direction.UP);
        CircuitPort resistorNegative = testPort("resistor_negative", base.east().south(), Direction.UP);

        ResourceLocation voltageSourceId = testId("voltage_source");
        ResourceLocation resistorId = testId("resistor");
        ResourceLocation positiveWireId = testId("positive_wire");
        ResourceLocation negativeWireId = testId("negative_wire");

        circuits.registerElement(level, new VoltageSourceElement(
                voltageSourceId,
                sourcePositive,
                sourceNegative,
                TEST_VOLTAGE_VOLTS
        ));
        circuits.registerElement(level, new ResistorElement(
                resistorId,
                resistorPositive,
                resistorNegative,
                TEST_RESISTANCE_OHMS
        ));
        circuits.registerElement(level, new WireElement(positiveWireId, sourcePositive, resistorPositive));
        circuits.registerElement(level, new WireElement(negativeWireId, sourceNegative, resistorNegative));

        testCircuits.put(level.dimension(), new TestCircuit(
                List.of(sourcePositive, sourceNegative, resistorPositive, resistorNegative),
                List.of(voltageSourceId, resistorId, positiveWireId, negativeWireId)
        ));

        context.getSource().sendSuccess(() -> Component.literal(String.format(
                Locale.ROOT,
                "Created EMcore test circuit at %s: %.6gV source, %.6g ohm resistor, expected current %.6gA",
                format(base),
                TEST_VOLTAGE_VOLTS,
                TEST_RESISTANCE_OHMS,
                TEST_VOLTAGE_VOLTS / TEST_RESISTANCE_OHMS
        )).withStyle(ChatFormatting.AQUA), false);
        context.getSource().sendSuccess(() -> Component.literal("Probe source+: " + format(sourcePositive.position())
                + ", source-: " + format(sourceNegative.position())
                + ", resistor+: " + format(resistorPositive.position())
                + ", resistor-: " + format(resistorNegative.position())), false);

        return Command.SINGLE_SUCCESS;
    }

    private int clearTestCircuit(CommandContext<CommandSourceStack> context) {
        ServerLevel level = context.getSource().getLevel();
        boolean removed = unregisterTestCircuit(level);

        context.getSource().sendSuccess(() -> Component.literal(removed
                ? "Cleared EMcore test circuit"
                : "No EMcore test circuit is registered in this dimension").withStyle(ChatFormatting.YELLOW), false);

        return Command.SINGLE_SUCCESS;
    }

    private boolean unregisterTestCircuit(ServerLevel level) {
        TestCircuit previous = testCircuits.remove(level.dimension());
        if (previous == null) {
            return false;
        }

        CircuitAccess circuits = Electromagnetics.api().circuits();
        for (ResourceLocation elementId : previous.elementIds()) {
            circuits.unregisterElement(level, elementId);
        }
        for (CircuitPort port : previous.ports()) {
            circuits.unregisterPort(level, port);
        }
        return true;
    }

    private boolean unregisterTestField(ServerLevel level) {
        TestField previous = testFields.remove(level.dimension());
        if (previous == null) {
            return false;
        }

        FieldAccess fields = Electromagnetics.api().fields();
        for (ResourceLocation coilId : previous.coilIds()) {
            fields.unregisterCoil(level, coilId);
        }
        for (ResourceLocation sourceId : previous.sourceIds()) {
            fields.unregisterSource(level, sourceId);
        }
        fields.unregisterRegion(level, previous.regionId());
        return true;
    }

    private static Map<CircuitPort, CircuitSample> samplesByPort(CircuitSnapshot snapshot) {
        Map<CircuitPort, CircuitSample> samples = new LinkedHashMap<>();
        for (CircuitSample sample : snapshot.samples()) {
            samples.put(sample.port(), sample);
        }
        return samples;
    }

    private static MutableComponent describePort(CircuitPort port, CircuitSample sample) {
        MutableComponent component = Component.literal(format(port.position()))
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(" " + port.side()).withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(" " + port.ownerId() + "#" + port.portId()).withStyle(ChatFormatting.WHITE));

        if (sample == null) {
            return component.append(Component.literal(" no sample").withStyle(ChatFormatting.YELLOW));
        }

        return component
                .append(Component.literal(" V=" + format(sample.voltageVolts()) + "V").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(" I=" + format(sample.currentAmps()) + "A").withStyle(ChatFormatting.GREEN))
                .append(Component.literal(" P=" + format(sample.powerWatts()) + "W").withStyle(ChatFormatting.GOLD))
                .append(sample.storedEnergyJoules() == 0.0
                        ? Component.empty()
                        : Component.literal(" E=" + format(sample.storedEnergyJoules()) + "J")
                                .withStyle(ChatFormatting.LIGHT_PURPLE));
    }

    private static MutableComponent describeDiagnostic(CircuitDiagnostic diagnostic) {
        ChatFormatting style = switch (diagnostic.severity()) {
            case ERROR -> ChatFormatting.RED;
            case WARNING -> ChatFormatting.YELLOW;
            case INFO -> ChatFormatting.GRAY;
        };

        MutableComponent component = Component.literal(diagnostic.severity() + " " + diagnostic.type())
                .withStyle(style)
                .append(Component.literal(": " + diagnostic.message()).withStyle(ChatFormatting.WHITE));
        if (!diagnostic.ports().isEmpty()) {
            component.append(Component.literal(" ports=" + diagnostic.ports().size()).withStyle(ChatFormatting.DARK_GRAY));
        }
        return component;
    }

    private static MutableComponent describeFieldDiagnostic(FieldDiagnostic diagnostic) {
        ChatFormatting style = switch (diagnostic.severity()) {
            case ERROR -> ChatFormatting.RED;
            case WARNING -> ChatFormatting.YELLOW;
            case INFO -> ChatFormatting.GRAY;
        };

        MutableComponent component = Component.literal(diagnostic.severity() + " " + diagnostic.type())
                .withStyle(style)
                .append(Component.literal(": " + diagnostic.message()).withStyle(ChatFormatting.WHITE));
        if (!diagnostic.regionIds().isEmpty()) {
            component.append(Component.literal(" regions=" + diagnostic.regionIds().size())
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
        return component;
    }

    private static String format(BlockPos position) {
        return position.getX() + " " + position.getY() + " " + position.getZ();
    }

    private static String format(double value) {
        if (!Double.isFinite(value)) {
            return String.valueOf(value);
        }
        return String.format(Locale.ROOT, "%.6g", value);
    }

    private static CircuitPort testPort(String path, BlockPos position, Direction side) {
        return new CircuitPort(TEST_OWNER, testId("port/" + path), position, side);
    }

    private static ResourceLocation testId(String path) {
        return ResourceLocation.fromNamespaceAndPath(EMcore.MODID, "debug_test/" + path);
    }

    private static ResourceLocation parseId(String rawId) {
        return ResourceLocation.parse(rawId.trim());
    }

    private record TestCircuit(List<CircuitPort> ports, List<ResourceLocation> elementIds) {
    }

    private record TestField(ResourceLocation regionId, List<ResourceLocation> sourceIds, List<ResourceLocation> coilIds) {
    }
}
