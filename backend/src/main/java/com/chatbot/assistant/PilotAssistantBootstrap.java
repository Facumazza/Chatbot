package com.chatbot.assistant;

import com.chatbot.shared.Uuids;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Crea la configuración inicial del asistente de Grenlus. Si ya existe no la toca: puede haber sido editada. */
@Component
@Order(2)
class PilotAssistantBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PilotAssistantBootstrap.class);
    private static final String PROMPT_RESOURCE = "assistant/grenlus-system-prompt-v1.md";

    private final JdbcClient jdbc;

    PilotAssistantBootstrap(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws IOException {
        Optional<UUID> tenantId = jdbc.sql("""
                SELECT t.id FROM tenants t
                WHERE t.slug = 'grenlus'
                  AND NOT EXISTS (SELECT 1 FROM chatbot_configurations c WHERE c.tenant_id = t.id)
                """)
                .query(UUID.class)
                .optional();
        if (tenantId.isEmpty()) {
            return;
        }
        UUID promptId = Uuids.v7();
        jdbc.sql("""
                INSERT INTO chatbot_prompt_versions (id, tenant_id, version_number, system_prompt, change_note)
                VALUES (:id, :tenantId, 1, :prompt, 'Versión inicial')
                """)
                .param("id", promptId)
                .param("tenantId", tenantId.get())
                .param("prompt", readPrompt())
                .update();
        jdbc.sql("""
                INSERT INTO chatbot_configurations (id, tenant_id, active_prompt_version_id, privacy_notice,
                    disabled_message, outside_hours_message, fallback_message)
                VALUES (:id, :tenantId, :promptId, :privacy, :disabled, :outsideHours, :fallback)
                """)
                .param("id", Uuids.v7())
                .param("tenantId", tenantId.get())
                .param("promptId", promptId)
                .param("privacy", "Soy el asistente virtual de Grenlus. Usamos tus datos solo para atender tu consulta.")
                .param("disabled", "¡Hola! Gracias por escribir a Grenlus. En este momento el asistente automático no está disponible: una persona del equipo te va a responder a la brevedad.")
                .param("outsideHours", "Una persona del equipo te va a responder en nuestro horario de atención.")
                .param("fallback", "Gracias por tu mensaje. Para darte una respuesta precisa te paso con una persona del equipo de Grenlus, que te va a responder a la brevedad.")
                .update();
        log.info("Configuración inicial del asistente creada");
    }

    private static String readPrompt() throws IOException {
        try (InputStream prompt = new ClassPathResource(PROMPT_RESOURCE).getInputStream()) {
            return new String(prompt.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
