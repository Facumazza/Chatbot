package com.chatbot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class ChatbotBackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(ChatbotBackendApplication.class, args);
	}

}
