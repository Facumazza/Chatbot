package com.chatbot.shared.web;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

    @Bean
    OpenAPI chatbotOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Chatbot WhatsApp API")
                .description("API del panel administrativo")
                .version("v1"));
    }
}
