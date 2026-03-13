package io.github.nickm980.smallville.api.v1.dto;

import java.util.HashMap;
import java.util.Map;

public class ObjectTypeDefinitionRequest {
    private Map<String, Object> properties = new HashMap<>();

    public Map<String, Object> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, Object> properties) {
        this.properties = properties;
    }
}
