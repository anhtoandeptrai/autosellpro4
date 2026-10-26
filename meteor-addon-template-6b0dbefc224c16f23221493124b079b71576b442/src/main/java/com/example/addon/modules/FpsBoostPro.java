package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import net.minecraft.client.option.CloudRenderMode;
import net.minecraft.client.option.ParticlesMode;

/**
 * Lowers expensive video settings while enabled and restores your original values when disabled.
 */
public class FpsBoostPro extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> renderDistance = sgGeneral.add(new IntSetting.Builder()
        .name("render-distance")
        .description("Render distance in chunks while the module is on.")
        .defaultValue(8)
        .range(2, 32)
        .sliderRange(2, 32)
        .build()
    );

    private final Setting<Integer> simulationDistance = sgGeneral.add(new IntSetting.Builder()
        .name("simulation-distance")
        .description("Simulation distance in chunks (mobs, crops, redstone).")
        .defaultValue(6)
        .range(5, 32)
        .sliderRange(5, 32)
        .build()
    );

    private final Setting<Integer> entityDistance = sgGeneral.add(new IntSetting.Builder()
        .name("entity-distance-percent")
        .description("How far entities are drawn, as a percentage (50 = half the normal distance).")
        .defaultValue(50)
        .range(50, 500)
        .sliderRange(50, 200)
        .build()
    );

    private final Setting<Boolean> minimalParticles = sgGeneral.add(new BoolSetting.Builder()
        .name("minimal-particles")
        .description("Reduce particles to the minimum.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> noClouds = sgGeneral.add(new BoolSetting.Builder()
        .name("no-clouds")
        .description("Turn clouds off.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> noShadows = sgGeneral.add(new BoolSetting.Builder()
        .name("no-entity-shadows")
        .description("Turn entity shadows off.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> noBlend = sgGeneral.add(new BoolSetting.Builder()
        .name("no-biome-blend")
        .description("Set biome blend to 0 (faster chunk loading).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> noBobbing = sgGeneral.add(new BoolSetting.Builder()
        .name("no-view-bobbing")
        .description("Turn view bobbing off.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> unlimitedFps = sgGeneral.add(new BoolSetting.Builder()
        .name("unlimited-fps")
        .description("Remove the FPS cap.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> noVsync = sgGeneral.add(new BoolSetting.Builder()
        .name("disable-vsync")
        .description("Turn VSync off.")
        .defaultValue(true)
        .build()
    );

    // Saved originals
    private boolean saved;
    private int oldRender;
    private int oldSimulation;
    private double oldEntityDistance;
    private ParticlesMode oldParticles;
    private CloudRenderMode oldClouds;
    private boolean oldShadows;
    private int oldBlend;
    private boolean oldBobbing;
    private int oldMaxFps;
    private boolean oldVsync;

    public FpsBoostPro() {
        super(AddonTemplate.CATEGORY, "fps-boost-pro", "Lowers video settings to raise FPS and reduce client lag.");
    }

    @Override
    public void onActivate() {
        if (mc.options == null) return;

        oldRender = mc.options.getViewDistance().getValue();
        oldSimulation = mc.options.getSimulationDistance().getValue();
        oldEntityDistance = mc.options.getEntityDistanceScaling().getValue();
        oldParticles = mc.options.getParticles().getValue();
        oldClouds = mc.options.getCloudRenderMode().getValue();
        oldShadows = mc.options.getEntityShadows().getValue();
        oldBlend = mc.options.getBiomeBlendRadius().getValue();
        oldBobbing = mc.options.getBobView().getValue();
        oldMaxFps = mc.options.getMaxFps().getValue();
        oldVsync = mc.options.getEnableVsync().getValue();
        saved = true;

        mc.options.getViewDistance().setValue(renderDistance.get());
        mc.options.getSimulationDistance().setValue(simulationDistance.get());
        mc.options.getEntityDistanceScaling().setValue(entityDistance.get() / 100.0);
        if (minimalParticles.get()) mc.options.getParticles().setValue(ParticlesMode.MINIMAL);
        if (noClouds.get()) mc.options.getCloudRenderMode().setValue(CloudRenderMode.OFF);
        if (noShadows.get()) mc.options.getEntityShadows().setValue(false);
        if (noBlend.get()) mc.options.getBiomeBlendRadius().setValue(0);
        if (noBobbing.get()) mc.options.getBobView().setValue(false);
        if (unlimitedFps.get()) mc.options.getMaxFps().setValue(260);
        if (noVsync.get()) mc.options.getEnableVsync().setValue(false);
    }

    @Override
    public void onDeactivate() {
        if (!saved || mc.options == null) return;

        mc.options.getViewDistance().setValue(oldRender);
        mc.options.getSimulationDistance().setValue(oldSimulation);
        mc.options.getEntityDistanceScaling().setValue(oldEntityDistance);
        mc.options.getParticles().setValue(oldParticles);
        mc.options.getCloudRenderMode().setValue(oldClouds);
        mc.options.getEntityShadows().setValue(oldShadows);
        mc.options.getBiomeBlendRadius().setValue(oldBlend);
        mc.options.getBobView().setValue(oldBobbing);
        mc.options.getMaxFps().setValue(oldMaxFps);
        mc.options.getEnableVsync().setValue(oldVsync);
        saved = false;
    }
}
