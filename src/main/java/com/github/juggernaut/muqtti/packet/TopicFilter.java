package com.github.juggernaut.muqtti.packet;

import com.github.juggernaut.muqtti.exception.DecodingException;

import java.util.Objects;
import java.util.Optional;

/**
 * @author ameya
 */
public class TopicFilter {

    private String filterString;
    private String shareName;

    private TopicFilter() {}

    public static TopicFilter fromString(final String input) {
        try {
            Utils.validateTopicFilter(input);
            TopicFilter topicFilter = new TopicFilter();
            topicFilter.filterString = input;
            topicFilter.shareName = null;
            checkForSharedFilter(topicFilter);
            return topicFilter;
        } catch (final IllegalArgumentException e) {
            throw new DecodingException(e.getMessage());
        }
    }

    private static void checkForSharedFilter(final TopicFilter topicFilter) {
        final String[] segs = topicFilter.filterString.split("/", 3);
        if (segs.length < 3) {
            return;
        }
        // A Shared Subscription's Topic Filter MUST start with $share/ and MUST contain a ShareName that is at least one character long [MQTT-4.8.2-1]
        // The ShareName MUST NOT contain the characters "/", "+" or "#", but MUST be followed by a "/" character. This "/" character MUST be followed by a Topic Filter [MQTT-4.8.2-2]
        if ("$share".equals(segs[0]) && !segs[1].isEmpty() && !(segs[1].contains("#") || segs[1].contains("+"))) {
            topicFilter.shareName = segs[1];
            topicFilter.filterString = segs[2];
        }
    }

    public String getFilterString() {
        return filterString;
    }

    public Optional<String> getShareName() {
        return Optional.ofNullable(shareName);
    }

    public boolean isShared() {
        return shareName != null;
    }

    public String getRawFilterString() {
        return shareName != null ? ("$share/" + shareName + "/" + filterString) : filterString;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TopicFilter that = (TopicFilter) o;
        return filterString.equals(that.filterString) &&
                Objects.equals(shareName, that.shareName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(filterString, shareName);
    }
}
