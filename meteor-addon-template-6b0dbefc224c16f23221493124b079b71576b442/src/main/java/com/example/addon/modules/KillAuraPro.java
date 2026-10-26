package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.utils.render.color.Color;
import net.minecraft.entity.passive.TameableEntity;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EntityTypeListSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.SortPriority;
import meteordevelopment.meteorclient.utils.entity.TargetUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.Hand;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class KillAuraPro extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTargeting = settings.createGroup("Targeting");

    // General
    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range")
        .description("Maximum distance to attack.")
        .defaultValue(4.2)
        .min(1)
        .sliderMax(6)
        .build()
    );

    private final Setting<Double> wallRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("through-walls-range")
        .description("Maximum distance to attack targets you cannot see. Set to 0 to never hit through walls.")
        .defaultValue(0)
        .min(0)
        .sliderMax(6)
        .build()
    );

    private final Setting<Double> cooldown = sgGeneral.add(new DoubleSetting.Builder()
        .name("cooldown")
        .description("Attack only when the weapon charge is at least this value (1.0 = full damage).")
        .defaultValue(0.95)
        .range(0, 1)
        .sliderRange(0, 1)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Face the target before attacking.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> onlyWeapon = sgGeneral.add(new BoolSetting.Builder()
        .name("only-weapon")
        .description("Only attack while holding a sword, axe or mace.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> autoSwitch = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-switch")
        .description("Switch to a weapon in your hotbar when needed.")
        .defaultValue(true)
        .visible(onlyWeapon::get)
        .build()
    );

    private final Setting<Boolean> onlyCrits = sgGeneral.add(new BoolSetting.Builder()
        .name("only-crits")
        .description("Only attack while falling, so every hit is a critical hit.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> swing = sgGeneral.add(new BoolSetting.Builder()
        .name("swing")
        .description("Play the arm swing animation when attacking.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> pauseBelowHealth = sgGeneral.add(new DoubleSetting.Builder()
        .name("pause-below-health")
        .description("Stop attacking when your health is at or below this value (0 = never).")
        .defaultValue(0)
        .range(0, 20)
        .sliderRange(0, 20)
        .build()
    );

    private final Setting<Boolean> disableOnDeath = sgGeneral.add(new BoolSetting.Builder()
        .name("disable-on-death")
        .description("Turn the module off when you die.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> render = sgGeneral.add(new BoolSetting.Builder()
        .name("render-target")
        .description("Draw a box around the current target.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> pauseOnUse = sgGeneral.add(new BoolSetting.Builder()
        .name("pause-while-using")
        .description("Do not attack while eating, drinking or blocking.")
        .defaultValue(true)
        .build()
    );

    // Targeting
    private final Setting<Set<EntityType<?>>> entities = sgTargeting.add(new EntityTypeListSetting.Builder()
        .name("entities")
        .description("Which entities to attack.")
        .onlyAttackable()
        .defaultValue(
            EntityType.PLAYER,
            EntityType.ZOMBIE, EntityType.ZOMBIE_VILLAGER, EntityType.HUSK, EntityType.DROWNED,
            EntityType.SKELETON, EntityType.STRAY, EntityType.WITHER_SKELETON,
            EntityType.CREEPER, EntityType.SPIDER, EntityType.CAVE_SPIDER,
            EntityType.WITCH, EntityType.PHANTOM, EntityType.SLIME, EntityType.MAGMA_CUBE,
            EntityType.BLAZE, EntityType.GUARDIAN, EntityType.PILLAGER, EntityType.VINDICATOR,
            EntityType.EVOKER, EntityType.VEX, EntityType.RAVAGER, EntityType.SILVERFISH,
            EntityType.ENDERMITE, EntityType.ZOGLIN, EntityType.HOGLIN
        )
        .build()
    );

    private final Setting<Boolean> ignoreTamed = sgTargeting.add(new BoolSetting.Builder()
        .name("ignore-tamed")
        .description("Never attack tamed pets (dogs, cats, parrots...).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> ignoreBabies = sgTargeting.add(new BoolSetting.Builder()
        .name("ignore-babies")
        .description("Do not attack baby mobs.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> ignoreNamed = sgTargeting.add(new BoolSetting.Builder()
        .name("ignore-named")
        .description("Do not attack mobs that have a name tag.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SortPriority> priority = sgTargeting.add(new EnumSetting.Builder<SortPriority>()
        .name("priority")
        .description("How to choose between several targets.")
        .defaultValue(SortPriority.LowestDistance)
        .build()
    );

    private final Setting<Boolean> multiTarget = sgTargeting.add(new BoolSetting.Builder()
        .name("multi-target")
        .description("Hit several targets in range at the same time instead of one by one.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> maxTargets = sgTargeting.add(new IntSetting.Builder()
        .name("max-targets")
        .description("Maximum number of targets hit per attack.")
        .defaultValue(5)
        .range(2, 20)
        .sliderRange(2, 20)
        .visible(multiTarget::get)
        .build()
    );

    private final Setting<Boolean> ignoreFriends = sgTargeting.add(new BoolSetting.Builder()
        .name("ignore-friends")
        .description("Never attack players on your Meteor friends list.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> ignoreCreative = sgTargeting.add(new BoolSetting.Builder()
        .name("ignore-creative")
        .description("Do not attack players in creative or spectator mode.")
        .defaultValue(true)
        .build()
    );

    private Entity target;

    public KillAuraPro() {
        super(AddonTemplate.CATEGORY, "kill-aura-pro", "Automatically attacks nearby entities.");
    }

    @Override
    public void onDeactivate() {
        target = null;
    }

    @Override
    public String getInfoString() {
        return target != null ? target.getName().getString() : null;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        target = null;
        if (mc.player == null || mc.world == null || mc.interactionManager == null) return;
        if (!mc.player.isAlive()) {
            if (disableOnDeath.get()) toggle();
            return;
        }
        if (mc.currentScreen != null) return;
        if (pauseOnUse.get() && mc.player.isUsingItem()) return;
        if (pauseBelowHealth.get() > 0 && mc.player.getHealth() <= pauseBelowHealth.get()) return;

        target = TargetUtils.get(this::isValidTarget, priority.get());
        if (target == null) return;

        if (onlyWeapon.get() && !isWeapon(mc.player.getMainHandStack())) {
            if (!autoSwitch.get()) return;
            FindItemResult weapon = InvUtils.findInHotbar(this::isWeapon);
            if (!weapon.found()) return;
            InvUtils.swap(weapon.slot(), false);
        }

        if (mc.player.getAttackCooldownProgress(0.5f) < cooldown.get()) return;

        if (onlyCrits.get()) {
            boolean falling = mc.player.fallDistance > 0 && !mc.player.isOnGround()
                && !mc.player.isClimbing() && !mc.player.isTouchingWater();
            if (!falling) return;
        }

        List<Entity> targets = new ArrayList<>();
        if (multiTarget.get()) {
            TargetUtils.getList(targets, this::isValidTarget, priority.get(), maxTargets.get());
        }
        if (targets.isEmpty()) targets.add(target);

        if (rotate.get()) {
            Entity first = targets.get(0);
            Rotations.rotate(Rotations.getYaw(first), Rotations.getPitch(first), () -> {
                for (Entity e : targets) attack(e);
            });
        } else {
            for (Entity e : targets) attack(e);
        }
    }

    private boolean isValidTarget(Entity entity) {
        if (entity == mc.player || !(entity instanceof LivingEntity living)) return false;
        if (!living.isAlive() || living.isInvulnerable()) return false;
        if (!entities.get().contains(entity.getType())) return false;

        double distance = mc.player.distanceTo(entity);
        if (distance > range.get()) return false;
        if (!PlayerUtils.canSeeEntity(entity) && distance > wallRange.get()) return false;

        if (!(entity instanceof PlayerEntity)) {
            if (ignoreTamed.get() && entity instanceof TameableEntity pet && pet.isTamed()) return false;
            if (ignoreBabies.get() && living.isBaby()) return false;
            if (ignoreNamed.get() && entity.hasCustomName()) return false;
        }

        if (entity instanceof PlayerEntity player) {
            if (ignoreFriends.get() && Friends.get().isFriend(player)) return false;
            if (ignoreCreative.get() && (player.isCreative() || player.isSpectator())) return false;
        }
        return true;
    }

    private boolean isWeapon(ItemStack stack) {
        return stack.isIn(ItemTags.SWORDS) || stack.isIn(ItemTags.AXES) || stack.isOf(Items.MACE);
    }

    private void attack(Entity entity) {
        if (mc.player == null || mc.interactionManager == null || entity == null) return;
        mc.interactionManager.attackEntity(mc.player, entity);
        if (swing.get()) mc.player.swingHand(Hand.MAIN_HAND);
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get() || target == null) return;
        event.renderer.box(target.getBoundingBox(), new Color(255, 40, 40, 40), new Color(255, 40, 40, 255), ShapeMode.Both, 0);
    }
}
