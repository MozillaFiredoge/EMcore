package com.firedoge.emcore.registry;

import com.firedoge.emcore.EMcore;
import com.firedoge.emcore.internal.block.DebugCircuitBlock;
import com.firedoge.emcore.internal.block.DebugCircuitBlockEntity;
import com.firedoge.emcore.internal.block.DebugCircuitComponent;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class EmRegistries {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(EMcore.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(EMcore.MODID);
    private static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, EMcore.MODID);
    private static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, EMcore.MODID);

    public static final DeferredBlock<DebugCircuitBlock> DEBUG_VOLTAGE_SOURCE = BLOCKS.register("debug_voltage_source",
            () -> new DebugCircuitBlock(DebugCircuitComponent.VOLTAGE_SOURCE, debugBlockProperties(MapColor.COLOR_RED)));
    public static final DeferredBlock<DebugCircuitBlock> DEBUG_RESISTOR = BLOCKS.register("debug_resistor",
            () -> new DebugCircuitBlock(DebugCircuitComponent.RESISTOR, debugBlockProperties(MapColor.COLOR_ORANGE)));
    public static final DeferredBlock<DebugCircuitBlock> DEBUG_WIRE = BLOCKS.register("debug_wire",
            () -> new DebugCircuitBlock(DebugCircuitComponent.WIRE, debugBlockProperties(MapColor.METAL)));
    public static final DeferredBlock<DebugCircuitBlock> DEBUG_JUNCTION_WIRE = BLOCKS.register("debug_junction_wire",
            () -> new DebugCircuitBlock(DebugCircuitComponent.JUNCTION, debugBlockProperties(MapColor.COLOR_LIGHT_BLUE)));

    public static final DeferredItem<BlockItem> DEBUG_VOLTAGE_SOURCE_ITEM = ITEMS.registerSimpleBlockItem(DEBUG_VOLTAGE_SOURCE);
    public static final DeferredItem<BlockItem> DEBUG_RESISTOR_ITEM = ITEMS.registerSimpleBlockItem(DEBUG_RESISTOR);
    public static final DeferredItem<BlockItem> DEBUG_WIRE_ITEM = ITEMS.registerSimpleBlockItem(DEBUG_WIRE);
    public static final DeferredItem<BlockItem> DEBUG_JUNCTION_WIRE_ITEM = ITEMS.registerSimpleBlockItem(DEBUG_JUNCTION_WIRE);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<DebugCircuitBlockEntity>> DEBUG_CIRCUIT_BLOCK_ENTITY =
            BLOCK_ENTITY_TYPES.register("debug_circuit", () -> BlockEntityType.Builder.of(
                    DebugCircuitBlockEntity::new,
                    DEBUG_VOLTAGE_SOURCE.get(),
                    DEBUG_RESISTOR.get(),
                    DEBUG_WIRE.get(),
                    DEBUG_JUNCTION_WIRE.get()
            ).build(null));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> EMCORE_TAB = CREATIVE_MODE_TABS.register("emcore", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.emcore"))
            .icon(() -> DEBUG_VOLTAGE_SOURCE_ITEM.get().getDefaultInstance())
            .displayItems((parameters, output) -> {
                output.accept(DEBUG_VOLTAGE_SOURCE_ITEM.get());
                output.accept(DEBUG_RESISTOR_ITEM.get());
                output.accept(DEBUG_WIRE_ITEM.get());
                output.accept(DEBUG_JUNCTION_WIRE_ITEM.get());
            })
            .build());

    private EmRegistries() {
    }

    public static void register(IEventBus eventBus) {
        BLOCKS.register(eventBus);
        ITEMS.register(eventBus);
        BLOCK_ENTITY_TYPES.register(eventBus);
        CREATIVE_MODE_TABS.register(eventBus);
    }

    private static BlockBehaviour.Properties debugBlockProperties(MapColor mapColor) {
        return BlockBehaviour.Properties.of()
                .mapColor(mapColor)
                .strength(1.5F, 6.0F);
    }
}
