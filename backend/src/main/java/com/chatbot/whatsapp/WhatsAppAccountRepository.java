package com.chatbot.whatsapp;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface WhatsAppAccountRepository extends JpaRepository<WhatsAppAccount, UUID> {

    Optional<WhatsAppAccount> findByPhoneNumberIdAndActiveTrue(String phoneNumberId);
}
