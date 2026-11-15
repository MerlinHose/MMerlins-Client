package dev.mcresearch.protocolclient.net;

import dev.mcresearch.protocolclient.protocol.PacketDirection;
import dev.mcresearch.protocolclient.protocol.PacketRegistry;
import dev.mcresearch.protocolclient.protocol.ProtocolException;
import dev.mcresearch.protocolclient.protocol.ProtocolState;
import dev.mcresearch.protocolclient.protocol.RawPacket;
import dev.mcresearch.protocolclient.protocol.codec.PacketReader;
import dev.mcresearch.protocolclient.protocol.codec.PacketWriter;
import dev.mcresearch.protocolclient.protocol.packet.ServerboundPacket;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TCP-Verbindung zum Server: Framing, optionale Kompression, ID-Validierung gegen den aktuellen Zustand, Paketlog.
 * Lesen erfolgt aus genau einem Thread; Senden ist synchronisiert und damit aus mehreren Threads erlaubt.
 */
public final class Connection implements PacketChannel {
	private final Socket socket;
	private final InputStream in;
	private final OutputStream out;
	private final CipherStreams.Decrypting decrypting;
	private final CipherStreams.Encrypting encrypting;
	private final PacketLog log;
	private final PacketRegistry registry = PacketRegistry.get();
	private final AtomicBoolean closed = new AtomicBoolean();
	private final AtomicLong bytesIn = new AtomicLong();
	private final AtomicLong bytesOut = new AtomicLong();
	private final AtomicLong compressedFramesIn = new AtomicLong();
	private final AtomicLong compressedFramesOut = new AtomicLong();
	private final AtomicLong framesIn = new AtomicLong();
	private final AtomicLong framesOut = new AtomicLong();
	private final AtomicLong packetBytesIn = new AtomicLong();
	private final AtomicLong packetBytesOut = new AtomicLong();
	private final Object sendLock = new Object();
	private volatile ProtocolState inboundState = ProtocolState.HANDSHAKE;
	private volatile ProtocolState outboundState = ProtocolState.HANDSHAKE;
	private volatile CompressionCodec compression;

	private Connection(final Socket socket, final PacketLog log) throws IOException {
		this.socket = socket;
		// M13: Verschlüsselung oberhalb der Puffer (docs/51 §4): Socket → Puffer → Chiffre → Framing
		this.decrypting = new CipherStreams.Decrypting(new BufferedInputStream(socket.getInputStream(), 1 << 16));
		this.encrypting = new CipherStreams.Encrypting(new BufferedOutputStream(socket.getOutputStream(), 1 << 13));
		this.in = this.decrypting;
		this.out = this.encrypting;
		this.log = log;
	}

	/**
	 * Öffnet die Verbindung. Nur Loopback-Adressen sind zulässig (siehe {@link LoopbackGuard}).
	 *
	 * @param readTimeoutMillis maximale Wartezeit auf Daten; Vanilla-Server senden spätestens alle 15 s Keep-Alive
	 */
	public static Connection open(final InetAddress address, final int port, final int connectTimeoutMillis, final int readTimeoutMillis, final PacketLog log) throws IOException {
		if (!address.isLoopbackAddress()) {
			throw new IllegalArgumentException("Refusing non-loopback address " + address);
		}
		if (port < 1 || port > 65535) {
			throw new IllegalArgumentException("Invalid port " + port);
		}
		Socket socket = new Socket();
		try {
			socket.setTcpNoDelay(true);
			socket.setSoTimeout(readTimeoutMillis);
			socket.connect(new InetSocketAddress(address, port), connectTimeoutMillis);
			return new Connection(socket, log);
		} catch (IOException | RuntimeException e) {
			socket.close();
			throw e;
		}
	}

	@Override
	public RawPacket receive() throws IOException {
		byte[] frame = FrameCodec.readFrame(this.in);
		this.bytesIn.addAndGet(frame.length);
		this.framesIn.incrementAndGet();
		CompressionCodec codec = this.compression;
		if (codec != null && CompressionCodec.isCompressedFrame(frame)) {
			this.compressedFramesIn.incrementAndGet();
		}
		byte[] packet = codec == null ? frame : codec.decode(frame);
		PacketReader reader = new PacketReader(packet);
		int id = reader.readVarInt();
		ProtocolState state = this.inboundState;
		String name = this.registry.nameOf(state, PacketDirection.CLIENTBOUND, id);
		if (name == null) {
			throw new ProtocolException(String.format("Unknown clientbound packet id 0x%02X in state %s", id, state.id()));
		}
		byte[] body = Arrays.copyOfRange(packet, reader.position(), packet.length);
		this.packetBytesIn.addAndGet(packet.length);
		this.log.packet(false, state.id(), "clientbound", name, "raw", packet.length);
		return new RawPacket(state, id, name, body, packet.length);
	}

	@Override
	public void send(final ServerboundPacket packet) throws IOException {
		synchronized (this.sendLock) {
			if (this.closed.get()) {
				throw new IOException("Connection closed");
			}
			ProtocolState state = this.outboundState;
			if (packet.state() != state) {
				throw new ProtocolException("Cannot send " + packet.name() + " (" + packet.state() + ") in state " + state);
			}
			PacketWriter writer = new PacketWriter().writeVarInt(this.registry.idOf(state, PacketDirection.SERVERBOUND, packet.name()));
			packet.write(writer);
			byte[] bytes = writer.toByteArray();
			CompressionCodec codec = this.compression;
			byte[] content = codec == null ? bytes : codec.encode(bytes);
			if (codec != null && bytes.length >= codec.threshold()) {
				this.compressedFramesOut.incrementAndGet();
			}
			FrameCodec.writeFrame(this.out, content);
			this.out.flush();
			this.bytesOut.addAndGet(content.length);
			this.framesOut.incrementAndGet();
			this.packetBytesOut.addAndGet(bytes.length);
			this.log.packet(true, state.id(), "serverbound", packet.name(), packet.getClass().getSimpleName(), bytes.length);
		}
	}

	@Override
	public void setInboundState(final ProtocolState state) {
		this.inboundState = state;
	}

	@Override
	public void setOutboundState(final ProtocolState state) {
		synchronized (this.sendLock) {
			this.outboundState = state;
		}
	}

	@Override
	public ProtocolState outboundState() {
		return this.outboundState;
	}

	@Override
	public void setCompressionThreshold(final int threshold) {
		this.compression = threshold >= 0 ? new CompressionCodec(threshold) : null;
	}

	/**
	 * M13: schaltet beide Richtungen auf AES/CFB8 um. Aufruf nach dem Senden von {@code key} (Vanilla:
	 * {@code send(key, thenRun(setEncryptionKey))}) und vor dem nächsten {@link #receive()} im Lesethread.
	 */
	@Override
	public void enableEncryption(final javax.crypto.SecretKey sharedSecret) throws IOException {
		try {
			javax.crypto.Cipher dec = LoginCrypto.streamCipher(javax.crypto.Cipher.DECRYPT_MODE, sharedSecret);
			javax.crypto.Cipher enc = LoginCrypto.streamCipher(javax.crypto.Cipher.ENCRYPT_MODE, sharedSecret);
			synchronized (this.sendLock) {
				this.out.flush();
				this.encrypting.enable(enc);
			}
			this.decrypting.enable(dec);
		} catch (java.security.GeneralSecurityException e) {
			throw new IOException("Cannot enable encryption", e);
		}
		this.log.marker("encryption enabled (AES/CFB8)");
	}

	@Override
	public boolean encrypted() {
		return this.decrypting.enabled() && this.encrypting.enabled();
	}

	@Override
	public void marker(final String text) {
		this.log.marker(text);
	}

	public long bytesIn() {
		return this.bytesIn.get();
	}

	@Override
	public TransportStats transportStats() {
		CompressionCodec codec = this.compression;
		return new TransportStats(codec == null ? -1 : codec.threshold(), this.framesIn.get(), this.compressedFramesIn.get(), this.framesOut.get(),
			this.compressedFramesOut.get(), this.bytesIn.get(), this.packetBytesIn.get(), this.bytesOut.get(), this.packetBytesOut.get());
	}

	public long compressedFramesIn() {
		return this.compressedFramesIn.get();
	}

	public long compressedFramesOut() {
		return this.compressedFramesOut.get();
	}

	public long bytesOut() {
		return this.bytesOut.get();
	}

	public boolean isClosed() {
		return this.closed.get();
	}

	/** Kontrolliertes Schließen: Ausgang leeren, TCP-FIN senden, Socket schließen. Idempotent. */
	@Override
	public void close() {
		if (!this.closed.compareAndSet(false, true)) {
			return;
		}
		synchronized (this.sendLock) {
			try {
				this.out.flush();
				this.socket.shutdownOutput();
			} catch (IOException ignored) {
				// Verbindung war bereits getrennt
			}
		}
		try {
			this.socket.close();
		} catch (IOException ignored) {
			// nichts mehr zu tun
		}
	}
}
