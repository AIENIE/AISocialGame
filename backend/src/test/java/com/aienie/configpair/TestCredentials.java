package com.aienie.configpair;
import java.util.LinkedHashMap;
import java.util.Map;
/** In-memory synthetic credentials for authentication tests. */
public final class TestCredentials {
    private TestCredentials() { }
    public static Map<String, String> parse(String text) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String line : text.split("\n")) {
            int index = line.indexOf('=');
            if (index > 0) result.put(line.substring(0, index), line.substring(index + 1));
        }
        return result;
    }
}
