package com.github.juggernaut.macchar.property;

import com.github.juggernaut.macchar.ByteBufferUtil;
import com.github.juggernaut.macchar.VariableByteIntegerDecoder;
import com.github.juggernaut.macchar.packet.ConnectProperties;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static com.github.juggernaut.macchar.property.PropertyIdentifiers.*;

/**
 * @author ameya
 */
public class PropertiesDecoder {

    public static List<MqttProperty> decode(final ByteBuffer buffer) {
        final int propertyLength = decodePropertyLength(buffer);
        if (propertyLength > buffer.remaining()) {
            throw new IllegalArgumentException("Invalid packet length based on property length");
        }
        final List<MqttProperty> properties = new ArrayList<>(propertyLength);
        if (propertyLength > 0) {
            // Slicing the properties portion makes it easier to not overflow bounds and read invalid data if
            // somehow the properties are faked to entice us to read past the property length.
            final var propertiesSlice = buffer.slice();
            propertiesSlice.limit(propertyLength);
            decodeProperties(propertiesSlice, properties);
            if (propertiesSlice.hasRemaining()) {
                throw new IllegalArgumentException("Properties length specified in packet is higher than the actual properties found");
            }
            buffer.position(buffer.position() + propertyLength);
        }
        return properties;
    }

    private static int decodePropertyLength(ByteBuffer buffer) {
        final var variableLengthDecoder = new VariableByteIntegerDecoder();
        boolean finished = variableLengthDecoder.decode(buffer);
        if (!finished) {
            throw new IllegalArgumentException("Invalid property length in CONNECT packet");
        }
        return variableLengthDecoder.getValue();
    }

    private static void decodeProperties(final ByteBuffer buffer, final List<MqttProperty> properties) {
        final var variableLengthDecoder = new VariableByteIntegerDecoder();
        while (buffer.hasRemaining()) {
            if (!variableLengthDecoder.decode(buffer)) {
                throw new IllegalArgumentException("Invalid property identifier length value");
            }
            final int propertyIdentifier = variableLengthDecoder.getValue();
            variableLengthDecoder.reset();
            switch(propertyIdentifier) {
                case PAYLOAD_FORMAT_INDICATOR:
                    properties.add(PayloadFormatIndicator.fromBuffer(buffer));
                    break;
                case MESSAGE_EXPIRY_INTERVAL:
                    properties.add(MessageExpiryInterval.fromBuffer(buffer));
                    break;
                case CONTENT_TYPE:
                    properties.add(ContentType.fromBuffer(buffer));
                    break;
                case RESPONSE_TOPIC:
                    properties.add(ResponseTopic.fromBuffer(buffer));
                    break;
                case CORRELATION_DATA:
                    properties.add(CorrelationData.fromBuffer(buffer));
                    break;
                case SUBSCRIPTION_IDENTIFIER:
                    properties.add(SubscriptionIdentifier.fromBuffer(buffer));
                    break;
                case TOPIC_ALIAS:
                    properties.add(TopicAlias.fromBuffer(buffer));
                    break;
                case USER_PROPERTY:
                    // 3.1.2.11.8: The User Property is allowed to appear multiple times to represent multiple name, value pairs. The same name is allowed to appear more than once
                    properties.add(UserProperty.fromBuffer(buffer));
                    break;
                default:
                    throw new IllegalArgumentException("Unknown property identifier " + propertyIdentifier);
            }

        }

    }
}
