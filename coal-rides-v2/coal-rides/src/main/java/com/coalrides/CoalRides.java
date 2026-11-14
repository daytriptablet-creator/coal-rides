package com.coalrides;

import com.mojang.serialization.Codec;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MinecartItem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.phys.Vec3;

public class CoalRides implements ModInitializer {
	// ---- tuning: change these to taste (speeds are blocks per tick, 20 ticks = 1 second) ----
	/** One coal burns this long while the throttle is not 0. 900 ticks = 45 seconds. */
	public static final int TICKS_PER_COAL = 900;
	/** Most coal a vehicle can hold. */
	public static final int MAX_COAL = 64;
	/** Throttle change per tick while W is held. 0.02 = 0 to 100% in 2.5 seconds. */
	public static final float THROTTLE_UP_RATE = 0.02f;
	/** Throttle change per tick while S is held. 0.03 = 100% to 0 in about 1.7 seconds. */
	public static final float THROTTLE_DOWN_RATE = 0.03f;
	/** Boats can reverse down to this throttle (-10% of top speed). */
	public static final float BOAT_REVERSE_LIMIT = -0.10f;

	/** Boat top speed at 100% throttle (1.15 = 23 blocks/s). */
	public static final double BOAT_MAX_SPEED = 1.15;
	public static final double BOAT_ACCEL = 0.2;
	public static final double BOAT_DECEL = 0.11;

	/** Minecart top speed at 100% throttle (1.6 = 32 blocks/s). */
	public static final double CART_MAX_SPEED = 1.6;
	public static final double CART_ACCEL = 0.1;
	public static final double CART_DECEL = 0.085;
	/** Speed the cart slows to before a curve, so it stays on the rails. */
	public static final double CART_CURVE_SPEED = 0.7;
	public static final double CART_CURVE_DECEL = 0.25;
	/** Speed the cart slows to before a slope (1 block up or down). */
	public static final double CART_SLOPE_SPEED = 1.0;
	public static final double CART_SLOPE_DECEL = 0.15;

	/** Coal left, in ticks. Saved with the vehicle, so it survives logging out. */
	public static final AttachmentType<Integer> FUEL = AttachmentRegistry.create(
			Identifier.fromNamespaceAndPath("coalrides", "fuel"),
			b -> b.initializer(() -> 0).persistent(Codec.INT));

	/** Current throttle. Not saved, it always starts at 0. */
	public static final AttachmentType<Float> THROTTLE = AttachmentRegistry.createDefaulted(
			Identifier.fromNamespaceAndPath("coalrides", "throttle"), () -> 0f);

	/** Coal (in ticks) stored on a boat or minecart item, so the fuel survives picking it up. */
	public static final DataComponentType<Integer> FUEL_ITEM = Registry.register(
			BuiltInRegistries.DATA_COMPONENT_TYPE,
			Identifier.fromNamespaceAndPath("coalrides", "stored_fuel"),
			DataComponentType.<Integer>builder()
					.persistent(Codec.INT)
					.networkSynchronized(ByteBufCodecs.VAR_INT)
					.build());

	/** A vehicle that was just destroyed, waiting for its dropped item to appear (same tick). */
	private record Death(Vec3 pos, int fuel, int tick) {}

	/** A player who just used a fueled boat or minecart item, waiting for the entity to appear. */
	private record Placing(int fuel, int tick) {}

	private static final List<Death> DEATHS = new ArrayList<>();
	private static final Map<UUID, Placing> PLACING = new HashMap<>();

	@Override
	public void onInitialize() {
		PayloadTypeRegistry.clientboundPlay().register(FuelPayload.TYPE, FuelPayload.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(ThrottlePayload.TYPE, ThrottlePayload.CODEC);

		// The rider's client sends the throttle it has set.
		ServerPlayNetworking.registerGlobalReceiver(ThrottlePayload.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			Entity vehicle = player.getVehicle();
			if (vehicle instanceof CoalFueled fueled && vehicle.getId() == payload.entityId()) {
				fueled.coalrides$setThrottle(Mth.clamp(payload.throttle(), BOAT_REVERSE_LIMIT, 1f));
			}
		});

		// Right-click a boat or minecart holding coal to load it.
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
			if (!(entity instanceof CoalFueled fueled)) {
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
				sp.sendSystemMessage(fuelText(fueled.coalrides$getFuel(), fueled.coalrides$getThrottle()), true);
			}
			return InteractionResult.SUCCESS;
		});

		// ---- fuel survives picking the boat or minecart up ----

		// 1. A fueled vehicle is destroyed: remember its fuel for a moment.
		ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
			if (entity instanceof CoalFueled f && f.coalrides$getFuel() > 0
					&& entity.getRemovalReason() == Entity.RemovalReason.KILLED) {
				int tick = world.getServer().getTickCount();
				DEATHS.removeIf(d -> d.tick() < tick - 1);
				DEATHS.add(new Death(entity.position(), f.coalrides$getFuel(), tick));
			}
		});

		// 2. The dropped item appears next to it: write the fuel onto the item.
		//    Or a vehicle appears right after a player used a fueled item: give it the fuel.
		ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
			int tick = world.getServer().getTickCount();
			if (entity instanceof ItemEntity itemEntity) {
				ItemStack stack = itemEntity.getItem();
				if (stack.getItem() instanceof BoatItem || stack.getItem() instanceof MinecartItem) {
					for (Death d : DEATHS) {
						if (d.tick() >= tick - 1 && d.pos().distanceToSqr(itemEntity.position()) < 9.0) {
							stack.set(FUEL_ITEM, d.fuel());
							DEATHS.remove(d);
							break;
						}
					}
				}
			} else if (entity instanceof CoalFueled f && f.coalrides$getFuel() == 0) {
				for (ServerPlayer p : world.players()) {
					Placing placing = PLACING.get(p.getUUID());
					if (placing != null && placing.tick() == tick && p.distanceToSqr(entity) < 100.0) {
						f.coalrides$setFuel(placing.fuel());
						PLACING.remove(p.getUUID());
						break;
					}
				}
			}
		});

		// 3. A player uses a fueled item: remember it for this tick.
		UseItemCallback.EVENT.register((player, level, hand) -> {
			rememberPlacing(player, level, hand);
			return InteractionResult.PASS;
		});
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
			rememberPlacing(player, level, hand);
			return InteractionResult.PASS;
		});
	}

	private static void rememberPlacing(Player player, Level level, InteractionHand hand) {
		if (!(level instanceof ServerLevel serverLevel)) {
			return;
		}
		Integer fuel = player.getItemInHand(hand).get(FUEL_ITEM);
		if (fuel != null && fuel > 0) {
			PLACING.put(player.getUUID(), new Placing(fuel, serverLevel.getServer().getTickCount()));
		}
	}

	public static Component fuelText(int ticks, float throttle) {
		int coal = (ticks + TICKS_PER_COAL - 1) / TICKS_PER_COAL;
		int secs = ticks / 20;
		return Component.literal(String.format("Coal: %d (%d:%02d)   Throttle: %d%%",
				coal, secs / 60, secs % 60, Math.round(throttle * 100f)));
	}

	public static void sync(Entity entity, int ticks) {
		FuelPayload payload = new FuelPayload(entity.getId(), ticks);
		for (ServerPlayer p : PlayerLookup.tracking(entity)) {
			ServerPlayNetworking.send(p, payload);
		}
		if (entity.getFirstPassenger() instanceof ServerPlayer rider) {
			ServerPlayNetworking.send(rider, payload);
		}
	}

	/** Smoke trail behind a burning vehicle. heading is the direction it faces or travels. */
	public static void smoke(Entity entity, Vec3 heading) {
		if (!(entity.level() instanceof ServerLevel level) || entity.tickCount % 2 != 0) {
			return;
		}
		Vec3 p = entity.position().subtract(heading.scale(0.8)).add(0, 0.8, 0);
		level.sendParticles(ParticleTypes.LARGE_SMOKE, p.x, p.y, p.z, 2, 0.15, 0.1, 0.15, 0.01);
		if (entity.tickCount % 8 == 0) {
			level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, p.x, p.y + 0.2, p.z, 1, 0.1, 0.0, 0.1, 0.01);
		}
	}

	// ---- rail helpers for the minecart ----

	public static boolean isRail(Level level, BlockPos pos) {
		return level.getBlockState(pos).getBlock() instanceof BaseRailBlock;
	}

	/** True if there is any rail within one block in every direction. */
	public static boolean railNear(Level level, BlockPos pos) {
		for (int dx = -1; dx <= 1; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				for (int dz = -1; dz <= 1; dz++) {
					if (isRail(level, pos.offset(dx, dy, dz))) {
						return true;
					}
				}
			}
		}
		return false;
	}

	/** True if a rail continues one or two blocks ahead (one up or down counts). */
	public static boolean railAhead(Level level, Vec3 from, Vec3 dir) {
		for (int k = 1; k <= 2; k++) {
			BlockPos p = BlockPos.containing(from.x + dir.x * k, from.y, from.z + dir.z * k);
			if (isRail(level, p) || isRail(level, p.below()) || isRail(level, p.above())) {
				return true;
			}
		}
		return false;
	}

	private static RailShape shapeAt(Level level, BlockPos pos) {
		BlockState state = level.getBlockState(pos);
		if (state.getBlock() instanceof BaseRailBlock rail) {
			return state.getValue(rail.getShapeProperty());
		}
		return null;
	}

	/** Looks ahead along the direction of travel. 0 = clear, 1 = slope ahead, 2 = curve ahead. */
	public static int hazardAhead(Level level, BlockPos pos, Vec3 dir, int range) {
		int result = 0;
		for (int k = 0; k <= range; k++) {
			BlockPos p = BlockPos.containing(
					pos.getX() + 0.5 + dir.x * k, pos.getY() + 0.5, pos.getZ() + 0.5 + dir.z * k);
			for (BlockPos q : new BlockPos[] {p, p.below(), p.above()}) {
				RailShape shape = shapeAt(level, q);
				if (shape == null) {
					continue;
				}
				switch (shape) {
					case NORTH_EAST, NORTH_WEST, SOUTH_EAST, SOUTH_WEST -> {
						return 2;
					}
					case ASCENDING_EAST, ASCENDING_WEST, ASCENDING_NORTH, ASCENDING_SOUTH -> result = 1;
					default -> {
					}
				}
			}
		}
		return result;
	}
}
