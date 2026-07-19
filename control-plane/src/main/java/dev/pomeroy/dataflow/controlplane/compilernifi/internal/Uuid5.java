package dev.pomeroy.dataflow.controlplane.compilernifi.internal;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/**
 * RFC 4122 name-based UUIDs, version 5 (SHA-1) — the JDK only ships version 3
 * (MD5, {@link UUID#nameUUIDFromBytes}). Every identifier in a compiled flow
 * definition is minted here from the component's logical path, which is the whole
 * determinism story: same plan, same paths, same ids, same bytes.
 */
final class Uuid5 {

	/** The RFC 4122 URL namespace; names are {@code dataflow:{slug}/{path}}. */
	private static final UUID NAMESPACE = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8");

	private Uuid5() {
	}

	static String mint(String name) {
		MessageDigest sha1;
		try {
			sha1 = MessageDigest.getInstance("SHA-1");
		}
		catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("every JVM ships SHA-1", e);
		}
		sha1.update(ByteBuffer.allocate(16)
				.putLong(NAMESPACE.getMostSignificantBits())
				.putLong(NAMESPACE.getLeastSignificantBits()).array());
		sha1.update(("dataflow:" + name).getBytes(StandardCharsets.UTF_8));
		byte[] hash = sha1.digest();
		hash[6] = (byte) ((hash[6] & 0x0f) | 0x50);
		hash[8] = (byte) ((hash[8] & 0x3f) | 0x80);
		ByteBuffer bytes = ByteBuffer.wrap(hash);
		return new UUID(bytes.getLong(), bytes.getLong()).toString();
	}
}
