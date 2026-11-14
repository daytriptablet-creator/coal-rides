package com.coalrides;

import com.mojang.serialization.Codec;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.phys.Vec3;

public class CoalRides implements ModInitializer {
	// ---- tuning: change these to taste (speeds are blocks per tick, 20 ticks = 1 second) ----
	/** One coal burns this long while the throttle is above 0. 900 ticks = 45 seconds. */
	public static final int TICKS_PER_COAL = 900;
	/** Most coal a vehicle can hold. */
	public static final int MAX_COAL = 64;
	/** How fast the throttle moves per tick while W or S is held. 0.02 = 0 to 100% in 2.5 seconds. */
	public static final float THROTTLE_RATE = 0.02f;

	/** Boat top speed at 100% throttle (1.15 = 23 blocks/s). */
	public static final double BOAT_MAX_SPEED = 1.15;
	public static final double BOAT_ACCEL = 0.2;
	public static final double BOAT_DECEL = 0.08;

	/** Minecart top speed at 100% throttle (1.6 = 32 blocks/s). */
	public static final double CART_MAX_SPEED = 1.6;
	public static final double CART_ACCEL = 0.1;
	public static final double CART_DECEL = 0.06;
	/** Speed the cart slows to before a curve, so it stays on the rails. */
	public static final double CART_CURVE_SPEED = 0.7;
	public static final double CART_CURVE_DECEL = 0.25;

	/** Coal left, in ticks. Saved with the vehicle, so it survives logging out. */
	public static final AttachmentType<Integer> FUEL = AttachmentRegistry.create(
			Identifier.fromNamespaceAndPath("coalrides", "fuel"),
			b -> b.initializer(() -> 0).persistent(Codec.INT));

	/** Current throttle, 0.0 to 1.0. Not saved, it always starts at 0. */
	public static final AttachmentType<Float> THROTTLE = AttachmentRegistry.createDefaulted(
			Identifier.fromNamespaceAndPath("coalrides", "throttle"), () -> 0f);

	@Override
	public void onInitialize() {
		PayloadTypeRegistry.clientboundPlay().register(FuelPayload.TYPE, FuelPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ThrottlePayload.TYPE, ThrottlePayload.CODEC);

		// The rider's client sends the throttle it has set.
		ServerPlayNetworking.registerGlobalReceiver(ThrottlePayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			Entity vehicle = player.getVehicle();
			if (vehicle instanceof CoalFueled fueled && vehicle.getId() == payload.entityId()) {
				fueled.coalrides$setThrottle(Mth.clamp(payload.throttle(), 0f, 1f));
			}
		});

		// Right-click a boat or minecart holding coal to load it.
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (!(entity instanceof CoalFueled fueled)) {
				return InteractionResult.PASS;
			}
			ItemStack stack = player.getItemInHand(hand);
			if (!stack.is(Items.COAL)) {
				return InteractionResult.PASS;
			}
			if (level.isClientSide()) {
				return InteractionResult.SUCCESS;
			}
			int max = MAX_COAL * TICKS_PER_COAL;
			if (fueled.coalrides$getFuel() + TICKS_PER_COAL > max) {
				if (player instanceof ServerPlayer sp) {
					sp.sendSystemMessage(Component.literal("Tank is full (" + MAX_COAL + " coal)"), true);
				}
				return InteractionResult.SUCCESS;
			}
			stack.consume(1, player);
			fueled.coalrides$setFuel(fueled.coalrides$getFuel() + TICKS_PER_COAL);
			sync(entity, fueled.coalrides$getFuel());
			if (player instanceof ServerPlayer sp) {
				sp.sendSystemMessage(fuelText(fueled.coalrides$getFuel(), fueled.coalrides$getThrottle()), true);
			}
			return InteractionResult.SUCCESS;
		});
	}

	public static Component fuelText(int ticks, float throttle) {
		int coal = (ticks + TICKS_PER_COAL - 1) / TICKS_PER_COAL;
		int secs = ticks / 20;
		return Component.literal(String.format("Coal: %d (%d:%02d)   Throttle: %d%%",
				coal, secs / 60, secs % 60, Math.round(throttle * 100f)));
	}

	public static void sync(Entity entity, int ticks) {
		FuelPayload payload = new FuelPayload(entity.getId(), ticks);
		for (ServerPlayer p : PlayerLookup.tracking(entity)) {
			ServerPlayNetworking.send(p, payload);
		}
		if (entity.getFirstPassenger() instanceof ServerPlayer rider) {
			ServerPlayNetworking.send(rider, payload);
		}
	}

	// ---- rail helpers for the minecart ----

	public static boolean isRail(Level level, BlockPos pos) {
		return level.getBlockState(pos).getBlock() instanceof BaseRailBlock;
	}

	private static boolean isCurve(Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (state.getBlock() instanceof BaseRailBlock rail) {
			RailShape shape = state.getValue(rail.getShapeProperty());
			return shape == RailShape.NORTH_EAST || shape == RailShape.NORTH_WEST
					|| shape == RailShape.SOUTH_EAST || shape == RailShape.SOUTH_WEST;
		}
		return false;
	}

	/** True if there is a curved rail within range blocks in the direction of travel. */
	public static boolean curveAhead(Level level, BlockPos pos, Vec3 dir, int range) {
		for (int k = 0; k <= range; k++) {
			BlockPos p = BlockPos.containing(
					pos.getX() + 0.5 + dir.x * k, pos.getY() + 0.5, pos.getZ() + 0.5 + dir.z * k);
			if (isCurve(level, p) || isCurve(level, p.below())) {
				return true;
			}
		}
		return false;
	}
}
