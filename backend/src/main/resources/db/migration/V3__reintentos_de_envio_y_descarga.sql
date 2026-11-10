-- Reintentos acotados para mensajes salientes y descarga de archivos.

ALTER TABLE messages
    ADD COLUMN send_attempts   integer NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at timestamptz NOT NULL DEFAULT now();

DROP INDEX messages_outbox_idx;
CREATE INDEX messages_outbox_idx ON messages (next_attempt_at) WHERE status = 'PENDING';

ALTER TABLE message_media
    ADD COLUMN attempts        integer NOT NULL DEFAULT 0,
    ADD COLUMN next_attempt_at timestamptz NOT NULL DEFAULT now();

DROP INDEX message_media_pending_idx;
CREATE INDEX message_media_pending_idx ON message_media (next_attempt_at) WHERE download_status = 'PENDING';
