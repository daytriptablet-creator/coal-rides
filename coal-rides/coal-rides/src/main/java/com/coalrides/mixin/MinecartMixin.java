package com.coalrides.mixin;

import com.coalrides.CoalFueled;
import com.coalrides.CoalRides;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractMinecart.class)
public abstract class MinecartMixin implements CoalFueled {
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

	/** Burns fuel and accelerates the cart along its direction of travel (server side). */
	@Inject(method = "tick", at = @At("HEAD"))
	private void coalrides$burnAndPush(CallbackInfo ci) {
		AbstractMinecart self = (AbstractMinecart) (Object) this;
		if (self.level().isClientSide() || coalrides$fuel <= 0) {
			return;
		}
		if (!(self.getFirstPassenger() instanceof ServerPlayer rider)) {
			return;
		}
		coalrides$fuel--;
		if (coalrides$fuel % 10 == 0 || coalrides$fuel == 0) {
			CoalRides.sync(self, coalrides$fuel);
			rider.sendSystemMessage(CoalRides.fuelText(coalrides$fuel), true);
		}

		Vec3 v = self.getDeltaMovement();
		Vec3 flat = new Vec3(v.x, 0, v.z);
		Vec3 dir;
		if (flat.lengthSqr() > 1.0E-4) {
			dir = flat.normalize();
		} else {
			// Standing still: nudge toward where the rider looks, the rail snaps it to its axis.
			Vec3 look = rider.getLookAngle();
			dir = new Vec3(look.x, 0, look.z);
			if (dir.lengthSqr() < 1.0E-4) {
				return;
			}
			dir = dir.normalize();
		}
		self.setDeltaMovement(v.add(dir.scale(CoalRides.CART_ACCEL)));
	}

	/** Raises the speed cap while the cart is burning. */
	@Inject(method = "getMaxSpeed", at = @At("HEAD"), cancellable = true)
	private void coalrides$maxSpeed(ServerLevel level, CallbackInfoReturnable<Double> cir) {
		if (coalrides$fuel > 0) {
			cir.setReturnValue(CoalRides.CART_MAX_SPEED);
		}
	}
}
