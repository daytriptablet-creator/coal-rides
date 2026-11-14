package com.coalrides;

/** Implemented (via mixin) by boats and minecarts. Fuel is counted in ticks. */
public interface CoalFueled {
	int coalrides$getFuel();

	void coalrides$setFuel(int ticks);
}
