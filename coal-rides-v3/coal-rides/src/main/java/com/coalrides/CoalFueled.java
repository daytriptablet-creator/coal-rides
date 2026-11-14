package com.coalrides;

import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;

/**
 * Implemented (via mixin) by boats and minecarts.
 * Fuel is counted in ticks and saved with the entity.
 * Throttle goes from -0.1 (boat reverse) to 1.0 and is not saved.
 */
public interface CoalFueled {
	default int coalrides$getFuel() {
		return ((AttachmentTarget) this).getAttachedOrElse(CoalRides.FUEL, 0);
	}

	default void coalrides$setFuel(int ticks) {
		((AttachmentTarget) this).setAttached(CoalRides.FUEL, Math.max(0, ticks));
	}

	default float coalrides$getThrottle() {
		return ((AttachmentTarget) this).getAttachedOrElse(CoalRides.THROTTLE, 0f);
	}

	default void coalrides$setThrottle(float value) {
		((AttachmentTarget) this).setAttached(CoalRides.THROTTLE,
				Math.max(CoalRides.BOAT_REVERSE_LIMIT, Math.min(1f, value)));
	}
}
