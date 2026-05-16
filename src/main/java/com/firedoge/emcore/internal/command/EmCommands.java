package com.firedoge.emcore.internal.command;

import com.firedoge.emcore.EMcore;
import com.firedoge.emcore.api.Electromagnetics;
import com.firedoge.emcore.api.circuit.CircuitAccess;
import com.firedoge.emcore.api.circuit.CircuitDiagnostic;
import com.firedoge.emcore.api.circuit.CircuitPort;
import com.firedoge.emcore.api.circuit.CircuitSample;
import com.firedoge.emcore.api.circuit.CircuitSnapshot;
import com.firedoge.emcore.api.circuit.ResistorElement;
import com.firedoge.emcore.api.circuit.VoltageSourceElement;
import com.firedoge.emcore.api.circuit.WireElement;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
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
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public final class EmCommands {
    private static final double TEST_VOLTAGE_VOLTS = 12.0;
    private static final double CONFLICT_TEST_VOLTAGE_VOLTS = 5.0;
    private static final double TEST_RESISTANCE_OHMS = 6.0;
    private static final int MAX_TRANSIENT_DEBUG_STEPS = 10_000;
    private static final ResourceLocation TEST_OWNER = ResourceLocation.fromNamespaceAndPath(EMcore.MODID, "debug_test");

    private final Map<ResourceKey<Level>, TestCircuit> testCircuits = new HashMap<>();

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
                                .then(Commands.literal("clear")
                                        .executes(this::clearTestCircuit)))));
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

    private record TestCircuit(List<CircuitPort> ports, List<ResourceLocation> elementIds) {
    }
}
