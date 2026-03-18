package cc.panelsd.connect.core;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Immutable configuration for a single logical connection.
 * <p>
 * This is the Java-side representation of what will eventually be
 * serialized as JSON for the external API.
 */
public final class SystemConfig {

    private final String id;
    private final String systemType;
    private final String userName;
    private final String systemPrefix;
    private final Map<String, String> options;

    private SystemConfig(Builder builder) {
        this.id = builder.id;
        this.systemType = builder.systemType;
        this.userName = builder.userName;
        this.systemPrefix = builder.systemPrefix;
        this.options = Collections.unmodifiableMap(new HashMap<>(builder.options));
    }

    public String getId() {
        return id;
    }

    public String getSystemType() {
        return systemType;
    }

    public String getUserName() {
        return userName;
    }

    public String getSystemPrefix() {
        return systemPrefix;
    }

    /**
     * Arbitrary key/value options; interpretation is system-specific.
     * For example:
     * <ul>
     *   <li>{@code portName}: serial/USB port identifier</li>
     *   <li>{@code host}, {@code port}: network targets</li>
     * </ul>
     */
    public Map<String, String> getOptions() {
        return options;
    }

    public String getOption(String key) {
        return options.get(key);
    }

    public static Builder builder(String id, String systemType) {
        return new Builder(id, systemType);
    }

    public static final class Builder {

        private final String id;
        private final String systemType;
        private String userName;
        private String systemPrefix;
        private final Map<String, String> options = new HashMap<>();

        public Builder(String id, String systemType) {
            this.id = id;
            this.systemType = systemType;
        }

        public Builder userName(String userName) {
            this.userName = userName;
            return this;
        }

        public Builder systemPrefix(String systemPrefix) {
            this.systemPrefix = systemPrefix;
            return this;
        }

        public Builder option(String key, String value) {
            if (key != null && value != null) {
                options.put(key, value);
            }
            return this;
        }

        public SystemConfig build() {
            return new SystemConfig(this);
        }
    }
}


