package com.github.juggernaut.macchar.property;

import com.github.juggernaut.macchar.property.types.ByteProperty;

import java.nio.ByteBuffer;

/**
 * @author ameya
 */
public class RequestResponseInformation extends ByteProperty {

    public RequestResponseInformation(byte value) {
        super(PropertyIdentifiers.REQUEST_RESPONSE_INFORMATION, value);
    }

    public static RequestResponseInformation fromBuffer(ByteBuffer buffer) {
        return new RequestResponseInformation(ByteProperty.decodeValue(buffer));
    }
}
