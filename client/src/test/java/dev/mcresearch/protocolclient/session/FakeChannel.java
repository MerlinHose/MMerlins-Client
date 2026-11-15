package dev.mcresearch.protocolclient.session;

import dev.mcresearch.protocolclient.net.PacketChannel;
import dev.mcresearch.protocolclient.protocol.PacketDirection;
import dev.mcresearch.protocolclient.protocol.PacketRegistry;
import dev.mcresearch.protocolclient.protocol.ProtocolException;
import dev.mcresearch.protocolclient.protocol.ProtocolState;
import dev.mcresearch.protocolclient.protocol.RawPacket;
import dev.mcresearch.protocolclient.protocol.codec.PacketWriter;
import dev.mcresearch.protocolclient.protocol.packet.ServerboundPacket;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Testkanal: liefert vorbereitete Serverpakete und zeichnet gesendete Pakete samt Zustand auf. */
final class FakeChannel implements PacketChannel {
	record Sent(ProtocolState state, ServerboundPacket packet) {
	}

	final Deque<RawPacket> incoming = new ArrayDeque<>();
	final List<Sent> sent = new ArrayList<>();
	final List<String> markers = new ArrayList<>();
	ProtocolState inbound = ProtocolState.HANDSHAKE;
	ProtocolState outbound = ProtocolState.HANDSHAKE;
	int compressionThreshold = -1;
	boolean closed;

	/** Fügt ein Serverpaket für den angegebenen Zustand hinzu. */
	FakeChannel add(final ProtocolState state, final String name, final PacketWriter body) {
		byte[] bytes = body.toByteArray();
		int id = PacketRegistry.get().idOf(state, PacketDirection.CLIENTBOUND, name);
		this.incoming.add(new RawPacket(state, id, name, bytes, bytes.length + 1));
		return this;
	}

	FakeChannel add(final ProtocolState state, final String name) {
		return add(state, name, new PacketWriter());
	}

	@Override
	public synchronized RawPacket receive() throws IOException {
		if (this.closed) {
			throw new java.net.SocketException("closed");
		}
		RawPacket p = this.incoming.poll();
		if (p == null) {
			throw new EOFException("no more test packets");
		}
		if (p.state() != this.inbound) {
			throw new ProtocolException("Test packet " + p.name() + " for " + p.state() + " but inbound state is " + this.inbound);
		}
		return p;
	}

	@Override
	public synchronized void send(final ServerboundPacket packet) throws IOException {
		if (this.closed) {
			throw new IOException("closed");
		}
		if (packet.state() != this.outbound) {
			throw new ProtocolException("Cannot send " + packet.name() + " in state " + this.outbound);
		}
		this.sent.add(new Sent(this.outbound, packet));
	}

	@Override
	public synchronized void setInboundState(final ProtocolState state) {
		this.inbound = state;
	}

	@Override
	public synchronized void setOutboundState(final ProtocolState state) {
		this.outbound = state;
	}

	@Override
	public synchronized ProtocolState outboundState() {
		return this.outbound;
	}

	@Override
	public synchronized void setCompressionThreshold(final int threshold) {
		this.compressionThreshold = threshold;
	}

	@Override
	public synchronized void marker(final String text) {
		this.markers.add(text);
	}

	@Override
	public synchronized void close() {
		this.closed = true;
	}

	synchronized List<String> sentNames() {
		return this.sent.stream().map(s -> s.state().id() + "/" + s.packet().name()).toList();
	}

	synchronized <T extends ServerboundPacket> T last(final Class<T> type) {
		for (int i = this.sent.size() - 1; i >= 0; i--) {
			if (type.isInstance(this.sent.get(i).packet())) {
				return type.cast(this.sent.get(i).packet());
			}
		}
		throw new AssertionError("no " + type.getSimpleName() + " sent");
	}
}
