package com.coalrides;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.world.entity.Entity;

public class CoalRidesClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// The player who steers a boat runs its movement on their own client,
		// so the client has to know the fuel level too.
		ClientPlayNetworking.registerGlobalReceiver(FuelPayload.TYPE, (payload, context) -> {
			Entity e = context.player().level().getEntity(payload.entityId());
			if (e instanceof CoalFueled fueled) {
				fueled.coalrides$setFuel(payload.ticks());
			}
		});
	}
}
