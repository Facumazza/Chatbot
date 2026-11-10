package com.chatbot.whatsapp;

import com.chatbot.shared.Uuids;
import com.chatbot.tenant.CredentialCipher;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Registra la empresa piloto y su número desde el entorno. El alta real de empresas llega después del MVP. */
@Component
@Order(1)
class PilotAccountBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PilotAccountBootstrap.class);
    private static final String PILOT_SLUG = "grenlus";

    private final JdbcClient jdbc;
    private final CredentialCipher cipher;
    private final String phoneNumberId;
    private final String businessAccountId;
    private final String displayPhoneNumber;
    private final String accessToken;

    PilotAccountBootstrap(
            JdbcClient jdbc,
            CredentialCipher cipher,
            @Value("${whatsapp.pilot.phone-number-id:}") String phoneNumberId,
            @Value("${whatsapp.pilot.business-account-id:}") String businessAccountId,
            @Value("${whatsapp.pilot.display-phone-number:}") String displayPhoneNumber,
            @Value("${whatsapp.pilot.access-token:}") String accessToken) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.phoneNumberId = phoneNumberId;
        this.businessAccountId = businessAccountId;
        this.displayPhoneNumber = displayPhoneNumber;
        this.accessToken = accessToken;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!StringUtils.hasText(phoneNumberId) || !StringUtils.hasText(accessToken)) {
            log.info("Sin credenciales de WhatsApp en el entorno: no se registra la cuenta piloto");
            return;
        }
        UUID tenantId = ensureTenant();
        jdbc.sql("""
                INSERT INTO whatsapp_accounts
                    (id, tenant_id, phone_number_id, waba_id, display_phone_number, access_token_encrypted)
                VALUES (:id, :tenantId, :phoneNumberId, :wabaId, :display, :token)
                ON CONFLICT (phone_number_id) DO UPDATE SET
                    waba_id = EXCLUDED.waba_id,
                    display_phone_number = EXCLUDED.display_phone_number,
                    access_token_encrypted = EXCLUDED.access_token_encrypted,
                    updated_at = now()
                """)
                .param("id", Uuids.v7())
                .param("tenantId", tenantId)
                .param("phoneNumberId", phoneNumberId)
                .param("wabaId", businessAccountId)
                .param("display", displayPhoneNumber)
                .param("token", cipher.encrypt(accessToken))
                .update();
        log.info("Cuenta de WhatsApp de la empresa piloto registrada");
    }

    private UUID ensureTenant() {
        jdbc.sql("INSERT INTO tenants (id, name, slug) VALUES (:id, 'Grenlus', :slug) ON CONFLICT (slug) DO NOTHING")
                .param("id", Uuids.v7())
                .param("slug", PILOT_SLUG)
                .update();
        return jdbc.sql("SELECT id FROM tenants WHERE slug = :slug")
                .param("slug", PILOT_SLUG)
                .query(UUID.class)
                .single();
    }
}
