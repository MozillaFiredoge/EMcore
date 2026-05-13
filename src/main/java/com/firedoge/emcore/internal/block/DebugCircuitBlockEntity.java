package com.firedoge.emcore.internal.block;

import java.util.Collection;
import java.util.ArrayList;
import java.util.List;

import com.firedoge.emcore.EMcore;
import com.firedoge.emcore.api.circuit.CircuitElement;
import com.firedoge.emcore.api.circuit.CircuitElementProvider;
import com.firedoge.emcore.api.circuit.CircuitPort;
import com.firedoge.emcore.api.circuit.CircuitTerminal;
import com.firedoge.emcore.api.circuit.ResistorElement;
import com.firedoge.emcore.api.circuit.VoltageSourceElement;
import com.firedoge.emcore.api.circuit.WireElement;
import com.firedoge.emcore.registry.EmRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class DebugCircuitBlockEntity extends BlockEntity implements CircuitElementProvider {
    private static final double DEBUG_VOLTAGE_VOLTS = 12.0;
    private static final double DEBUG_RESISTANCE_OHMS = 6.0;

    private boolean registered;

    public DebugCircuitBlockEntity(BlockPos pos, BlockState blockState) {
        super(EmRegistries.DEBUG_CIRCUIT_BLOCK_ENTITY.get(), pos, blockState);
    }

    @Override
    public void onLoad() {
        registerWithCircuit();
    }

    @Override
    public void onChunkUnloaded() {
        unregisterFromCircuit();
    }

    @Override
    public void setRemoved() {
        unregisterFromCircuit();
        super.setRemoved();
    }

    @Override
    public Collection<CircuitPort> circuitPorts() {
        return List.of();
    }

    @Override
    public Collection<CircuitTerminal> circuitTerminals() {
        Direction facing = facing();
        BlockPos positiveNode = worldPosition.relative(facing);
        BlockPos negativeNode = worldPosition.relative(facing.getOpposite());

        return switch (component()) {
            case VOLTAGE_SOURCE -> List.of(
                    circuitTerminal("positive", positiveNode),
                    circuitTerminal("negative", worldPosition)
            );
            case RESISTOR -> List.of(
                    circuitTerminal("positive", worldPosition),
                    circuitTerminal("negative", negativeNode)
            );
            case WIRE -> List.of(
                    circuitTerminal("back", negativeNode),
                    circuitTerminal("front", positiveNode)
            );
            case JUNCTION -> junctionTerminals();
        };
    }

    @Override
    public Collection<CircuitElement> circuitElements() {
        Direction facing = facing();
        BlockPos positiveNode = worldPosition.relative(facing);
        BlockPos negativeNode = worldPosition.relative(facing.getOpposite());

        return switch (component()) {
            case VOLTAGE_SOURCE -> List.of(
                    new VoltageSourceElement(
                            elementId("voltage_source"),
                            terminalPort("positive", positiveNode),
                            terminalPort("negative", worldPosition),
                            DEBUG_VOLTAGE_VOLTS
                    )
            );
            case RESISTOR -> List.of(
                    new ResistorElement(
                            elementId("resistor"),
                            terminalPort("positive", worldPosition),
                            terminalPort("negative", negativeNode),
                            DEBUG_RESISTANCE_OHMS
                    )
            );
            case WIRE -> List.of(new WireElement(
                    elementId("wire"),
                    terminalPort("back", negativeNode),
                    terminalPort("front", positiveNode)
            ));
            case JUNCTION -> junctionElements();
        };
    }

    private Collection<CircuitTerminal> junctionTerminals() {
        List<CircuitTerminal> terminals = new ArrayList<>();
        terminals.add(circuitTerminal("center", worldPosition));

        for (Direction direction : Direction.values()) {
            terminals.add(circuitTerminal(direction.getName(), worldPosition.relative(direction)));
        }

        return terminals;
    }

    private Collection<CircuitElement> junctionElements() {
        List<CircuitElement> elements = new ArrayList<>();
        CircuitPort center = terminalPort("center", worldPosition);

        for (Direction direction : Direction.values()) {
            elements.add(new WireElement(
                    elementId("junction/" + direction.getName()),
                    center,
                    terminalPort(direction.getName(), worldPosition.relative(direction))
            ));
        }

        return elements;
    }

    private void registerWithCircuit() {
        Level level = this.level;
        if (registered || !(level instanceof ServerLevel serverLevel)) {
            return;
        }

        registerCircuitElements(serverLevel);
        registered = true;
    }

    private void unregisterFromCircuit() {
        Level level = this.level;
        if (!registered || !(level instanceof ServerLevel serverLevel)) {
            return;
        }

        unregisterCircuitElements(serverLevel);
        registered = false;
    }

    private DebugCircuitComponent component() {
        if (getBlockState().getBlock() instanceof DebugCircuitBlock block) {
            return block.component();
        }

        throw new IllegalStateException("Debug circuit block entity is attached to a non-debug block");
    }

    private Direction facing() {
        BlockState state = getBlockState();
        if (state.hasProperty(DebugCircuitBlock.FACING)) {
            return state.getValue(DebugCircuitBlock.FACING);
        }

        return Direction.EAST;
    }

    private ResourceLocation elementId(String type) {
        BlockPos pos = getBlockPos();
        return ResourceLocation.fromNamespaceAndPath(EMcore.MODID, "debug_block/" + type + "/" + pos.getX() + "/" + pos.getY() + "/" + pos.getZ());
    }

    private CircuitPort terminalPort(String terminal, BlockPos node) {
        BlockPos pos = getBlockPos();
        return new CircuitPort(
                ResourceLocation.fromNamespaceAndPath(EMcore.MODID, "debug_block/" + pos.getX() + "/" + pos.getY() + "/" + pos.getZ()),
                terminalId(terminal),
                node,
                Direction.UP
        );
    }

    private CircuitTerminal circuitTerminal(String terminal, BlockPos node) {
        BlockPos pos = getBlockPos();
        return new CircuitTerminal(
                ResourceLocation.fromNamespaceAndPath(EMcore.MODID, "debug_block/" + pos.getX() + "/" + pos.getY() + "/" + pos.getZ()),
                terminalId(terminal),
                node,
                terminalPort(terminal, node)
        );
    }

    private static ResourceLocation terminalId(String terminal) {
        return ResourceLocation.fromNamespaceAndPath(EMcore.MODID, "terminal/" + terminal);
    }
}
