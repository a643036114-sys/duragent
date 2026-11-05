package dev.duragent.core.json;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * JSON boundary between the engine and the database.
 *
 * <p>Map entries are sorted on write so that two runs of the same step serialize
 * identically. That is a precondition for the replay/diff feature: a diff that
 * reports noise from key ordering is useless.
 */
public final class JsonCodec {

    private final ObjectMapper mapper;

    public JsonCodec() {
        this(defaultMapper());
    }

    public JsonCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public static ObjectMapper defaultMapper() {
        ObjectMapper mapper = new ObjectMapper();
        // Picks up jackson-datatype-jsr310 & friends when they are on the classpath.
        mapper.findAndRegisterModules();
        mapper.configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }

    public String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot serialize step output of type "
                    + (value == null ? "null" : value.getClass().getName()), e);
        }
    }

    public <T> T fromJson(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot deserialize step output into " + type.getName(), e);
        }
    }

    public <T> T fromJson(String json, TypeReference<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot deserialize step output into " + type, e);
        }
    }

    public ObjectMapper mapper() {
        return mapper;
    }
}
