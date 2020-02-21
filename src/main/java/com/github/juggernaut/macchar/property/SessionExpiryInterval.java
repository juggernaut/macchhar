package com.github.juggernaut.macchar.property;

import com.github.juggernaut.macchar.property.types.FourByteIntegerProperty;

import java.nio.ByteBuffer;

/**
 * @author ameya
 */
public class SessionExpiryInterval extends FourByteIntegerProperty {

    public SessionExpiryInterval(long value) {
        super(PropertyIdentifiers.SESSION_EXPIRY_INTERVAL, value);
    }

    public static SessionExpiryInterval fromBuffer(ByteBuffer buffer) {
        return new SessionExpiryInterval(FourByteIntegerProperty.decodeValue(buffer));
    }
}
