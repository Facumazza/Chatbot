package com.chatbot.catalog;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Solo lectura de la API pública de la web. Nunca escribe en ella. */
@Component
class GrenlusCatalogClient {

    private final RestClient rest;
    private final JsonMapper jsonMapper;

    GrenlusCatalogClient(CatalogProperties properties, JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(20));
        this.rest = RestClient.builder()
                .baseUrl(properties.syncEnabled() ? properties.apiUrl() : "http://localhost")
                .requestFactory(factory)
                .build();
    }

    JsonNode products() {
        return get("/indumentarias");
    }

    JsonNode colors(String productId) {
        return get("/indumentarias/" + productId + "/colores");
    }

    private JsonNode get(String path) {
        return jsonMapper.readTree(rest.get().uri(path).retrieve().body(String.class));
    }
}
