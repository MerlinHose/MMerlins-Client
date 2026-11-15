package dev.mcresearch.protocolclient.session;

import dev.mcresearch.protocolclient.net.PacketChannel;
import dev.mcresearch.protocolclient.protocol.PacketRegistry;
import dev.mcresearch.protocolclient.protocol.ProtocolException;
import dev.mcresearch.protocolclient.protocol.ProtocolState;
import dev.mcresearch.protocolclient.protocol.packet.Clientbound;
import dev.mcresearch.protocolclient.protocol.packet.ClientboundDecoder;
import dev.mcresearch.protocolclient.protocol.packet.Serverbound;
import java.io.EOFException;
import java.io.IOException;

/** Statusabfrage über eine eigene Verbindung (docs/12 §5.2): Handshake STATUS, status_request, ping_request. */
public final class StatusQuery {
	public record Result(Clientbound.StatusResponse status, long pingMillis, boolean protocolMatches) {
	}

	private StatusQuery() {
	}

	public static Result query(final PacketChannel channel, final String host, final int port) throws IOException {
		try (channel) {
			channel.send(new Serverbound.Intention(host, port, Serverbound.Intention.STATUS));
			channel.setOutboundState(ProtocolState.STATUS);
			channel.setInboundState(ProtocolState.STATUS);
			channel.send(new Serverbound.StatusRequest());
			if (!(ClientboundDecoder.decode(channel.receive()) instanceof Clientbound.StatusResponse status)) {
				throw new ProtocolException("Expected status_response");
			}
			long sent = System.nanoTime();
			long token = System.currentTimeMillis();
			channel.send(new Serverbound.PingRequest(token));
			if (!(ClientboundDecoder.decode(channel.receive()) instanceof Clientbound.PongResponse pong)) {
				throw new ProtocolException("Expected pong_response");
			}
			long pingMillis = (System.nanoTime() - sent) / 1_000_000;
			if (pong.time() != token) {
				throw new ProtocolException("pong_response echoed " + pong.time() + " instead of " + token);
			}
			// Vanilla-Server schließt nach dem Pong (ServerStatusPacketListenerImpl#handlePingRequest)
			try {
				channel.receive();
				throw new ProtocolException("Unexpected packet after pong_response");
			} catch (EOFException | java.net.SocketException expected) {
				// erwartet
			}
			return new Result(status, pingMillis, status.protocol() == PacketRegistry.PROTOCOL_VERSION);
		}
	}
}
