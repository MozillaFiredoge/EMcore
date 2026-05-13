package com.firedoge.emcore;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import com.firedoge.emcore.api.Electromagnetics;
import com.firedoge.emcore.internal.DefaultElectromagneticsApi;
import com.firedoge.emcore.internal.world.EmWorldEvents;
import com.firedoge.emcore.internal.world.EmWorldManager;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;

@Mod(EMcore.MODID)
public final class EMcore {
    public static final String MODID = "emcore";
    public static final Logger LOGGER = LogUtils.getLogger();

    private final EmWorldManager worldManager = new EmWorldManager();

    public EMcore(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);

        Electromagnetics.install(new DefaultElectromagneticsApi(worldManager));
        NeoForge.EVENT_BUS.register(new EmWorldEvents(worldManager));
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("Electromagnetics Core initialized");
    }
}
