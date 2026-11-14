package com.coalrides;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public class CoalRides implements ModInitializer {
	// ---- tuning: change these to taste ----
	/** One coal burns this long. 20 ticks = 1 second, so 900 = 45 seconds. */
	public static final int TICKS_PER_COAL = 900;
	/** Most coal a vehicle can hold. */
	public static final int MAX_COAL = 64;
	/** Boat top speed in blocks per tick (1.0 = 20 blocks/s). Vanilla boat is about 0.4. */
	public static final double BOAT_MAX_SPEED = 1.0;
	/** Boat acceleration per tick while burning. */
	public static final double BOAT_ACCEL = 0.12;
	/** Minecart top speed in blocks per tick (1.2 = 24 blocks/s). Vanilla max is 0.4. */
	public static final double CART_MAX_SPEED = 1.2;
	/** Minecart acceleration per tick while burning. */
	public static final double CART_ACCEL = 0.08;

	@Override
	public void onInitialize() {
		PayloadTypeRegistry.clientboundPlay().register(FuelPayload.TYPE, FuelPayload.CODEC);

		// Right-click a boat or minecart holding coal to load it.
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (!(entity instanceof CoalFueled fueled)) {
				return InteractionResult.PASS;
			}
			if (!(entity instanceof AbstractBoat) && !(entity instanceof AbstractMinecart)) {
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
				sp.sendSystemMessage(fuelText(fueled.coalrides$getFuel()), true);
			}
			return InteractionResult.SUCCESS;
		});
	}

	public static Component fuelText(int ticks) {
		int coal = (ticks + TICKS_PER_COAL - 1) / TICKS_PER_COAL;
		int secs = ticks / 20;
		return Component.literal(String.format("Coal: %d   (%d:%02d left)", coal, secs / 60, secs % 60));
	}

	public static void sync(Entity entity, int ticks) {
		FuelPayload payload = new FuelPayload(entity.getId(), ticks);
		for (ServerPlayer p : PlayerLookup.tracking(entity)) {
			ServerPlayNetworking.send(p, payload);
		}
		if (entity.getControllingPassenger() instanceof ServerPlayer rider) {
			ServerPlayNetworking.send(rider, payload);
		}
	}
}
