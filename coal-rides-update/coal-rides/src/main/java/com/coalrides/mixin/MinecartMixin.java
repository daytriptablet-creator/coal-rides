package com.coalrides.mixin;

import com.coalrides.CoalFueled;
import com.coalrides.CoalRides;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.level.Level;
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
	private Vec3 coalrides$lastRail;
	@Unique
	private int coalrides$cooldown;

	/** Server: burns fuel, follows the throttle, slows for curves, and puts the cart back if it derails. */
	@Inject(method = "tick", at = @At("HEAD"))
	private void coalrides$drive(CallbackInfo ci) {
		AbstractMinecart self = (AbstractMinecart) (Object) this;
		Level level = self.level();
		if (level.isClientSide()) {
			return;
		}
		if (!(self.getFirstPassenger() instanceof ServerPlayer rider) || coalrides$getFuel() <= 0) {
			if (coalrides$getThrottle() != 0f) {
				coalrides$setThrottle(0f);
			}
			coalrides$lastRail = null;
			coalrides$cooldown = 0;
			return;
		}

		float throttle = coalrides$getThrottle();
		int fuel = coalrides$getFuel();
		if (throttle > 0f) {
			fuel--;
			coalrides$setFuel(fuel);
		}
		if (self.tickCount % 10 == 0 || fuel == 0) {
			CoalRides.sync(self, fuel);
		}
		if (self.tickCount % 4 == 0) {
			rider.sendSystemMessage(CoalRides.fuelText(fuel, throttle), true);
		}

		// Safety net: if the cart left the rails, put it back where it last was on them.
		BlockPos pos = self.blockPosition();
		boolean onRail = CoalRides.isRail(level, pos) || CoalRides.isRail(level, pos.below());
		if (onRail) {
			coalrides$lastRail = self.position();
		} else if (coalrides$lastRail != null) {
			self.setPos(coalrides$lastRail.x, coalrides$lastRail.y, coalrides$lastRail.z);
			self.setDeltaMovement(Vec3.ZERO);
			coalrides$cooldown = 20;
			return;
		}
		if (coalrides$cooldown > 0) {
			coalrides$cooldown--;
			return;
		}

		Vec3 v = self.getDeltaMovement();
		Vec3 flat = new Vec3(v.x, 0, v.z);
		double speed = flat.length();
		Vec3 dir;
		if (speed > 1.0E-3) {
			dir = flat.scale(1.0 / speed);
		} else {
			// Standing still: nudge toward where the rider looks, the rail snaps it to its axis.
			if (throttle <= 0f) {
				return;
			}
			Vec3 look = rider.getLookAngle();
			dir = new Vec3(look.x, 0, look.z);
			if (dir.lengthSqr() < 1.0E-4) {
				return;
			}
			dir = dir.normalize();
		}

		double target = throttle * CoalRides.CART_MAX_SPEED;
		double decel = CoalRides.CART_DECEL;
		if (target > CoalRides.CART_CURVE_SPEED && CoalRides.curveAhead(level, pos, dir, 5)) {
			target = CoalRides.CART_CURVE_SPEED;
			decel = CoalRides.CART_CURVE_DECEL;
		}
		double next = speed + Mth.clamp(target - speed, -decel, CoalRides.CART_ACCEL);
		if (next < 0.005) {
			self.setDeltaMovement(0, 0, 0);
		} else if (speed > 1.0E-3) {
			self.setDeltaMovement(v.scale(next / speed));
		} else {
			self.setDeltaMovement(dir.scale(next));
		}
	}

	/** Raises the speed cap while the cart is burning. */
	@Inject(method = "getMaxSpeed", at = @At("HEAD"), cancellable = true)
	private void coalrides$maxSpeed(ServerLevel level, CallbackInfoReturnable<Double> cir) {
		if (coalrides$getFuel() > 0) {
			cir.setReturnValue(CoalRides.CART_MAX_SPEED);
		}
	}
}
