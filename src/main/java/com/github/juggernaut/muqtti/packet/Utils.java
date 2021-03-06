package com.github.juggernaut.muqtti.packet;

import com.github.juggernaut.muqtti.exception.DecodingException;
import com.github.juggernaut.muqtti.property.MqttProperty;
import com.github.juggernaut.muqtti.property.UserProperty;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author ameya
 */
public class Utils {

    private static void commonValidateTopicNameAndFilter(final String s) {
        assert s != null;
        //  All Topic Names and Topic Filters MUST be at least one character long [MQTT-4.7.3-1]
        if (s.isEmpty()) {
            throw new IllegalArgumentException("Topic/filter must be at least one character long");
        }
        // Topic Names and Topic Filters MUST NOT include the null character (Unicode U+0000) [MQTT-4.7.3-2]
        if (s.codePoints().anyMatch(i -> i == 0)) {
            throw new IllegalArgumentException("Topic/filter must not include the null character");
        }
    }

    public static void validateTopicName(final String s) {
        commonValidateTopicNameAndFilter(s);
        // The Topic Name in the PUBLISH packet MUST NOT contain wildcard characters [MQTT-3.3.2-2]
        if (s.contains("#") || s.contains("+")) {
            throw new IllegalArgumentException("Topic Name in PUBLISH packet MUST NOT contain wildcard characters");
        }
    }

    public static void validateTopicFilter(final String s) {
        commonValidateTopicNameAndFilter(s);
        validateMultiLevelWildcard(s);
        validateSingleLevelWildcard(s);
    }

    private static void validateSingleLevelWildcard(String s) {
        String withoutWildcards = s;
        if (s.startsWith("+")) {
            withoutWildcards = s.substring(1, s.length());
        }
        withoutWildcards = withoutWildcards.replaceAll("/\\+/", "");
        if (withoutWildcards.contains("+")) {
            throw new IllegalArgumentException("Single-level wildcard (+) found at invalid position in filter " + s);
        }
    }

    private static void validateMultiLevelWildcard(final String s) {
        // The multi-level wildcard character MUST be specified either on its own or following a topic level separator. In either case it MUST be the last character specified in the Topic Filter [MQTT-4.7.1-1]
        if ("#".equals(s)) {
            return;
        }
        String withoutTrailingWildcard = s;
        if (s.endsWith("/#")) {
            withoutTrailingWildcard = s.substring(0, s.length() - 2);
        }
        if (withoutTrailingWildcard.contains("#")) {
            throw new IllegalArgumentException("Multi-level wildcard (#) found at invalid position in filter " + s);
        }
    }

    public static boolean doesSubscriptionMatchTopic(final String filter, final String topicName) {
        assert filter != null;
        assert topicName != null;
        final String[] topicSegments = topicName.split("/");
        final String[] filterSegments = filter.split("/");

        if (filterSegments.length > topicSegments.length) {
            // no way this topic can match because filter has more segments
            return false;
        }

        int i;
        for (i = 0; i < filterSegments.length; i++) {
            if ("#".equals(filterSegments[i])) {
                return true;
            }
            if ("+".equals(filterSegments[i])) {
                continue;
            }
            if (!filterSegments[i].equals(topicSegments[i])) {
                return false;
            }
        }
        return i == topicSegments.length;
    }

    public static List<UserProperty> extractUserProperties(final List<MqttProperty> rawProperties) {
        return rawProperties.stream()
                .filter(prop -> prop instanceof UserProperty)
                .map(prop -> (UserProperty) prop)
                .collect(Collectors.toList());
    }

    public static <P extends MqttProperty> Optional<P> extractProperty(final Class<P> klass, List<MqttProperty> rawProperties) {
        return rawProperties.stream()
                .filter(prop -> prop.getClass().equals(klass))
                .map(klass::cast)
                .findFirst();
    }

    public static void validatePropertyTypes(final List<MqttProperty> mqttProperties,
                                             final Set<Class<? extends MqttProperty>> expectedPropertyTypes,
                                             final MqttPacket.PacketType packetType) {
        mqttProperties.forEach(prop -> {
            if (!expectedPropertyTypes.contains(prop.getClass())) {
                // TODO: better exception type
                throw new DecodingException("Property type " + prop.getClass().getSimpleName() + " not allowed in packet " + packetType);
            }
        });
    }

    public static SocketAddress getRemoteAddressUnchecked(final SocketChannel socketChannel) {
        try {
            return socketChannel.getRemoteAddress();
        } catch (IOException e) {
            return null;
        }
    }

}
