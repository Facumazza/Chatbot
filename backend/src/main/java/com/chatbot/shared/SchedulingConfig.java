package com.chatbot.shared;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Activa los procesos en segundo plano. Los tests los ejecutan a mano para ser deterministas. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(name = "app.workers.enabled", havingValue = "true", matchIfMissing = true)
class SchedulingConfig {
}
