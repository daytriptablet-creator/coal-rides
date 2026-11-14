package com.coalrides;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;

public class CoalRidesClient implements ClientModInitializer {
	private static CoalFueled lastVehicle;
	/** After S brings a boat to exactly 0%, S has to be released before it starts reversing. */
	private static boolean holdBlock;

	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(FuelPayload.TYPE, (payload, context) -> {
			Entity e = context.player().level().getEntity(payload.entityId());
			if (e instanceof CoalFueled fueled) {
				fueled.coalrides$setFuel(payload.ticks());
			}
		});

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			LocalPlayer player = client.player;
			CoalFueled current = null;
			if (player != null && player.getVehicle() instanceof CoalFueled f
					&& player.getVehicle().getFirstPassenger() == player) {
				current = f;
			}

			if (lastVehicle != null && lastVehicle != current) {
				lastVehicle.coalrides$setThrottle(0f);
			}
			lastVehicle = current;
			if (current == null) {
				holdBlock = false;
				return;
			}

			boolean isBoat = (Entity) current instanceof AbstractBoat;
			float min = isBoat ? CoalRides.BOAT_REVERSE_LIMIT : 0f;
			boolean down = client.options.keyDown.isDown();
			if (!down) {
				holdBlock = false;
			}

			float old = current.coalrides$getThrottle();
			float next = old;
			if (current.coalrides$getFuel() <= 0) {
				next = 0f;
			} else {
				if (client.options.keyUp.isDown()) {
					next += CoalRides.THROTTLE_UP_RATE;
				}
				if (down && !holdBlock) {
					next -= CoalRides.THROTTLE_DOWN_RATE;
				}
				if (old > 0f && next < 0f) {
					// Braking to a stop: stay at 0% until S is released and pressed again.
					next = 0f;
					holdBlock = true;
				}
				next = Math.max(min, Math.min(1f, next));
			}

			if (next != old) {
				current.coalrides$setThrottle(next);
				ClientPlayNetworking.send(new ThrottlePayload(((Entity) current).getId(), next));
			}
		});
	}
}
