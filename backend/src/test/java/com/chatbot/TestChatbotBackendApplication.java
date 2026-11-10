package com.chatbot;

import org.springframework.boot.SpringApplication;

public class TestChatbotBackendApplication {

	public static void main(String[] args) {
		SpringApplication.from(ChatbotBackendApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
