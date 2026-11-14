package com.coalrides;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;

public class CoalRidesClient implements ClientModInitializer {
	private static CoalFueled lastVehicle;

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
				return;
			}

			float old = current.coalrides$getThrottle();
			float next = old;
			if (current.coalrides$getFuel() <= 0) {
				next = 0f;
			} else {
				if (client.options.keyUp.isDown()) {
					next += CoalRides.THROTTLE_RATE;
				}
				if (client.options.keyDown.isDown()) {
					next -= CoalRides.THROTTLE_RATE;
				}
				next = Math.max(0f, Math.min(1f, next));
			}

			if (next != old) {
				current.coalrides$setThrottle(next);
				ClientPlayNetworking.send(new ThrottlePayload(((Entity) current).getId(), next));
			}
		});
	}
}
