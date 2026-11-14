package com.coalrides.mixin;

import com.coalrides.CoalFueled;
import com.coalrides.CoalRides;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractBoat.class)
public abstract class BoatMixin implements CoalFueled {
	/** Server: burns fuel while the throttle is not 0, keeps the rider's display up to date. */
	@Inject(method = "tick", at = @At("HEAD"))
	private void coalrides$burn(CallbackInfo ci) {
		AbstractBoat self = (AbstractBoat) (Object) this;
		if (self.level().isClientSide()) {
			return;
		}
		float throttle = coalrides$getThrottle();
		if (!(self.getControllingPassenger() instanceof ServerPlayer rider)) {
			if (throttle != 0f) {
				coalrides$setThrottle(0f);
			}
			return;
		}
		int fuel = coalrides$getFuel();
		if (fuel <= 0) {
			if (throttle != 0f) {
				coalrides$setThrottle(0f);
			}
			if (self.tickCount % 20 == 0) {
				rider.sendSystemMessage(CoalRides.outOfCoal(), true);
			}
			return;
		}
		if (throttle != 0f) {
			fuel--;
			coalrides$setFuel(fuel);
			double yaw = Math.toRadians(self.getYRot());
			CoalRides.smoke(self, new Vec3(-Math.sin(yaw), 0, Math.cos(yaw)));
		}
		if (self.tickCount % 10 == 0 || fuel == 0) {
			CoalRides.sync(self, fuel);
		}
		if (fuel == 0) {
			rider.sendSystemMessage(CoalRides.outOfCoal(), true);
		} else if (self.tickCount % 4 == 0) {
			rider.sendSystemMessage(CoalRides.fuelText(fuel, throttle), true);
		}
	}

	/** Steering player's client: moves the boat toward the speed the throttle asks for. */
	@Inject(method = "controlBoat", at = @At("TAIL"))
	private void coalrides$drive(CallbackInfo ci) {
		AbstractBoat self = (AbstractBoat) (Object) this;
		if (coalrides$getFuel() <= 0 || !(self.getControllingPassenger() instanceof Player)) {
			return;
		}
		double yaw = Math.toRadians(self.getYRot());
		double hx = -Math.sin(yaw);
		double hz = Math.cos(yaw);
		Vec3 v = self.getDeltaMovement();
		double speed = v.x * hx + v.z * hz;
		double target = coalrides$getThrottle() * CoalRides.BOAT_MAX_SPEED;
		double next = speed + Mth.clamp(target - speed, -CoalRides.BOAT_DECEL, CoalRides.BOAT_ACCEL);
		self.setDeltaMovement(hx * next, v.y, hz * next);
	}
}
