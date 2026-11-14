package com.coalrides;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Server to client: how much fuel (in ticks) an entity has left. */
public record FuelPayload(int entityId, int ticks) implements CustomPacketPayload {
	public static final Type<FuelPayload> TYPE =
			new Type<>(Identifier.fromNamespaceAndPath("coalrides", "fuel"));

	public static final StreamCodec<RegistryFriendlyByteBuf, FuelPayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, FuelPayload::entityId,
			ByteBufCodecs.VAR_INT, FuelPayload::ticks,
			FuelPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
