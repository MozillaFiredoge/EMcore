package com.firedoge.emcore.internal.block;

import com.mojang.serialization.MapCodec;
import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;

public final class DebugCircuitBlock extends Block implements EntityBlock {
    public static final MapCodec<DebugCircuitBlock> CODEC = simpleCodec(properties -> new DebugCircuitBlock(DebugCircuitComponent.WIRE, properties));
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    private final DebugCircuitComponent component;

    public DebugCircuitBlock(DebugCircuitComponent component, BlockBehaviour.Properties properties) {
        super(properties);
        this.component = component;
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH));
    }

    public DebugCircuitComponent component() {
        return component;
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new DebugCircuitBlockEntity(pos, state);
    }
}
