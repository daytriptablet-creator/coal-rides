package com.coalrides.mixin;

import com.coalrides.CoalFueled;
import com.coalrides.CoalRides;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(AbstractBoat.class)
public abstract class BoatMixin implements CoalFueled {
	@Unique
	private int coalrides$fuel;

	@Override
	public int coalrides$getFuel() {
		return coalrides$fuel;
	}

	@Override
	public void coalrides$setFuel(int ticks) {
		coalrides$fuel = Math.max(0, ticks);
	}

	/** Burns fuel on the server and updates the action bar. */
	@Inject(method = "tick", at = @At("HEAD"))
	private void coalrides$burn(CallbackInfo ci) {
		AbstractBoat self = (AbstractBoat) (Object) this;
		if (self.level().isClientSide() || coalrides$fuel <= 0) {
			return;
		}
		if (!(self.getControllingPassenger() instanceof ServerPlayer rider)) {
			return;
		}
		coalrides$fuel--;
		if (coalrides$fuel % 10 == 0 || coalrides$fuel == 0) {
			CoalRides.sync(self, coalrides$fuel);
			rider.sendSystemMessage(CoalRides.fuelText(coalrides$fuel), true);
		}
	}

	/** Runs on the steering player's client and on the server: pushes the boat forward. */
	@Inject(method = "controlBoat", at = @At("TAIL"))
	private void coalrides$push(CallbackInfo ci) {
		AbstractBoat self = (AbstractBoat) (Object) this;
		if (coalrides$fuel <= 0 || !(self.getControllingPassenger() instanceof Player)) {
			return;
		}
		double yaw = Math.toRadians(self.getYRot());
		Vec3 v = self.getDeltaMovement();
		double x = v.x + -Math.sin(yaw) * CoalRides.BOAT_ACCEL;
		double z = v.z + Math.cos(yaw) * CoalRides.BOAT_ACCEL;
		double speed = Math.sqrt(x * x + z * z);
		if (speed > CoalRides.BOAT_MAX_SPEED) {
			double k = CoalRides.BOAT_MAX_SPEED / speed;
			x *= k;
			z *= k;
		}
		self.setDeltaMovement(x, v.y, z);
	}
}
