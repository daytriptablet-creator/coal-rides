package com.coalrides;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Client to server: the throttle (0.0 to 1.0) the rider has set. */
public record ThrottlePayload(int entityId, float throttle) implements CustomPacketPayload {
	public static final Type<ThrottlePayload> TYPE =
			new Type<>(Identifier.fromNamespaceAndPath("coalrides", "throttle"));

	public static final StreamCodec<RegistryFriendlyByteBuf, ThrottlePayload> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, ThrottlePayload::entityId,
			ByteBufCodecs.FLOAT, ThrottlePayload::throttle,
			ThrottlePayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
