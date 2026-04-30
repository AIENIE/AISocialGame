package com.aisocialgame.integration.consul;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.Attributes;
import io.grpc.EquivalentAddressGroup;
import io.grpc.NameResolver;
import io.grpc.NameResolverProvider;
import io.grpc.Status;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

public final class ConsulNameResolverProvider extends NameResolverProvider {
    @Override
    protected boolean isAvailable() {
        return true;
    }

    @Override
    protected int priority() {
        return 5;
    }

    @Override
    public String getDefaultScheme() {
        return "consul";
    }

    @Override
    public NameResolver newNameResolver(URI targetUri, NameResolver.Args args) {
        if (targetUri == null || !"consul".equals(targetUri.getScheme())) {
            return null;
        }
        String serviceName = extractServiceName(targetUri);
        if (serviceName == null || serviceName.isBlank()) {
            return null;
        }
        return new ConsulNameResolver(serviceName);
    }

    private static String extractServiceName(URI targetUri) {
        String path = targetUri.getPath();
        if (path != null && path.startsWith("/") && path.length() > 1) {
            return path.substring(1);
        }
        String authority = targetUri.getAuthority();
        if (authority != null && !authority.isBlank()) {
            return authority;
        }
        String schemeSpecificPart = targetUri.getSchemeSpecificPart();
        if (schemeSpecificPart != null && !schemeSpecificPart.isBlank()) {
            return schemeSpecificPart.replaceFirst("^/*", "");
        }
        return null;
    }

    private static final class ConsulNameResolver extends NameResolver {
        private static final ObjectMapper MAPPER = new ObjectMapper();
        private static final HttpClient HTTP = HttpClient.newHttpClient();

        private final String serviceName;
        private Listener2 listener;

        private ConsulNameResolver(String serviceName) {
            this.serviceName = serviceName;
        }

        @Override
        public String getServiceAuthority() {
            return serviceName;
        }

        @Override
        public void start(Listener2 listener) {
            this.listener = listener;
            resolve();
        }

        @Override
        public void refresh() {
            resolve();
        }

        @Override
        public void shutdown() {
        }

        private void resolve() {
            if (listener == null) {
                return;
            }
            try {
                URI base = consulHttpBase();
                URI uri = base.resolve("/v1/health/service/" + serviceName + "?passing=true");
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(uri)
                        .timeout(Duration.ofSeconds(3))
                        .GET()
                        .build();
                HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    listener.onError(Status.UNAVAILABLE.withDescription("Consul HTTP " + response.statusCode()));
                    return;
                }
                HealthEntry[] entries = MAPPER.readValue(response.body(), HealthEntry[].class);
                if (entries.length == 0 || entries[0].service == null) {
                    listener.onError(Status.UNAVAILABLE.withDescription("No passing instances for " + serviceName));
                    return;
                }
                String host = entries[0].service.address;
                if (host == null || host.isBlank()) {
                    host = entries[0].node != null ? entries[0].node.address : null;
                }
                int port = entries[0].service.port;
                if (host == null || host.isBlank() || port <= 0) {
                    listener.onError(Status.UNAVAILABLE.withDescription("Invalid address for " + serviceName));
                    return;
                }

                EquivalentAddressGroup eag = new EquivalentAddressGroup(new InetSocketAddress(host, port));
                listener.onResult(ResolutionResult.newBuilder()
                        .setAddresses(List.of(eag))
                        .setAttributes(Attributes.EMPTY)
                        .build());
            } catch (Exception e) {
                listener.onError(Status.UNAVAILABLE.withCause(e).withDescription("Consul resolve error"));
            }
        }

        private static URI consulHttpBase() {
            String raw = System.getProperty("CONSUL_HTTP_ADDR");
            if (raw == null || raw.isBlank()) {
                raw = System.getenv("CONSUL_HTTP_ADDR");
            }
            if (raw == null || raw.isBlank()) {
                raw = "http://192.168.5.208:60000";
            }
            raw = raw.trim();
            if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
                raw = "http://" + raw;
            }
            while (raw.endsWith("/")) {
                raw = raw.substring(0, raw.length() - 1);
            }
            return URI.create(raw);
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        private static final class HealthEntry {
            @JsonProperty("Node")
            public Node node;

            @JsonProperty("Service")
            public Service service;
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        private static final class Node {
            @JsonProperty("Address")
            public String address;
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        private static final class Service {
            @JsonProperty("Address")
            public String address;

            @JsonProperty("Port")
            public int port;
        }
    }
}
