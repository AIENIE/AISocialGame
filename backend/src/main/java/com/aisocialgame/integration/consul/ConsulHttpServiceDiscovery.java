package com.aisocialgame.integration.consul;

import com.aisocialgame.config.AppProperties;
import com.aisocialgame.exception.ApiException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ConsulHttpServiceDiscovery {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration CACHE_TTL = Duration.ofSeconds(30);

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final URI consulBaseUri;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

    public ConsulHttpServiceDiscovery(AppProperties appProperties) {
        this.consulBaseUri = normalizeBaseUri(appProperties.getConsul().getAddress());
    }

    public String resolveHttpAddress(String serviceName) {
        if (!StringUtils.hasText(serviceName)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Consul 服务名不能为空");
        }
        CacheEntry cached = cache.get(serviceName);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return cached.address();
        }

        String resolved = resolveFromConsul(serviceName.trim());
        cache.put(serviceName, new CacheEntry(resolved, Instant.now().plus(CACHE_TTL)));
        return resolved;
    }

    private String resolveFromConsul(String serviceName) {
        try {
            URI uri = consulBaseUri.resolve("/v1/health/service/" + serviceName + "?passing=true");
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Consul 查询失败: HTTP " + response.statusCode());
            }
            HealthEntry[] entries = objectMapper.readValue(response.body(), HealthEntry[].class);
            if (entries == null || entries.length == 0) {
                throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Consul 中无可用实例: " + serviceName);
            }
            for (HealthEntry entry : entries) {
                if (entry == null || entry.service == null || entry.service.port <= 0) {
                    continue;
                }
                String host = entry.service.address;
                if (!StringUtils.hasText(host) && entry.node != null) {
                    host = entry.node.address;
                }
                if (StringUtils.hasText(host)) {
                    return "http://" + host.trim() + ":" + entry.service.port;
                }
            }
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Consul 返回实例地址无效: " + serviceName);
        } catch (ApiException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Consul 服务发现失败: " + ex.getMessage());
        }
    }

    private URI normalizeBaseUri(String rawAddress) {
        String address = rawAddress;
        if (!StringUtils.hasText(address)) {
            address = "http://192.168.5.208:60001";
        }
        address = address.trim();
        if (!address.startsWith("http://") && !address.startsWith("https://")) {
            address = "http://" + address;
        }
        while (address.endsWith("/")) {
            address = address.substring(0, address.length() - 1);
        }
        return URI.create(address);
    }

    private record CacheEntry(String address, Instant expiresAt) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class HealthEntry {
        @JsonProperty("Node")
        public Node node;
        @JsonProperty("Service")
        public Service service;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class Node {
        @JsonProperty("Address")
        public String address;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class Service {
        @JsonProperty("Address")
        public String address;
        @JsonProperty("Port")
        public int port;
        @JsonProperty("Meta")
        public Map<String, String> meta;
        @JsonProperty("Tags")
        public List<String> tags;
    }
}
