-- Esquema inicial del sistema. Diseño y decisiones en docs/03-base-de-datos.md.
-- Los UUID (v7) los genera la aplicación. Los estados son text con CHECK,
-- más simples de migrar que los ENUM de PostgreSQL.

CREATE EXTENSION IF NOT EXISTS btree_gist;

-- ---------------------------------------------------------------------------
-- Empresas y accesos
-- ---------------------------------------------------------------------------

CREATE TABLE tenants (
    id          uuid PRIMARY KEY,
    name        text NOT NULL,
    slug        text NOT NULL UNIQUE,
    status      text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    timezone    text NOT NULL DEFAULT 'America/Argentina/Buenos_Aires',
    locale      text NOT NULL DEFAULT 'es-AR',
    currency    text NOT NULL DEFAULT 'ARS' CHECK (char_length(currency) = 3),
    created_at  timestamptz NOT NULL DEFAULT now(),
    updated_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE whatsapp_accounts (
    id                      uuid PRIMARY KEY,
    tenant_id               uuid NOT NULL REFERENCES tenants (id),
    phone_number_id         text NOT NULL UNIQUE,
    waba_id                 text NOT NULL,
    display_phone_number    text NOT NULL,
    access_token_encrypted  text NOT NULL,
    token_key_version       integer NOT NULL DEFAULT 1,
    active                  boolean NOT NULL DEFAULT true,
    created_at              timestamptz NOT NULL DEFAULT now(),
    updated_at              timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id)
);

-- Incluye administradores y operadores, diferenciados por rol.
CREATE TABLE human_agents (
    id             uuid PRIMARY KEY,
    tenant_id      uuid NOT NULL REFERENCES tenants (id),
    email          text NOT NULL,
    password_hash  text NOT NULL,
    full_name      text NOT NULL,
    role           text NOT NULL CHECK (role IN ('ADMIN', 'AGENT')),
    active         boolean NOT NULL DEFAULT true,
    last_login_at  timestamptz,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id)
);
-- Único global: el login no pide elegir empresa.
CREATE UNIQUE INDEX human_agents_email_uq ON human_agents (lower(email));

-- ---------------------------------------------------------------------------
-- Clientes
-- ---------------------------------------------------------------------------

CREATE TABLE customers (
    id             uuid PRIMARY KEY,
    tenant_id      uuid NOT NULL REFERENCES tenants (id),
    wa_user_id     text,          -- BSUID de Meta
    phone_number   text,          -- tal como lo informa Meta; puede faltar
    wa_profile_name text,
    full_name      text,
    email          text,
    locality       text,
    first_seen_at  timestamptz NOT NULL DEFAULT now(),
    last_seen_at   timestamptz NOT NULL DEFAULT now(),
    opted_out_at   timestamptz,
    anonymized_at  timestamptz,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    version        integer NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    CONSTRAINT customers_identifier_ck
        CHECK (anonymized_at IS NOT NULL OR wa_user_id IS NOT NULL OR phone_number IS NOT NULL)
);
CREATE UNIQUE INDEX customers_wa_user_uq ON customers (tenant_id, wa_user_id) WHERE wa_user_id IS NOT NULL;
CREATE UNIQUE INDEX customers_phone_uq   ON customers (tenant_id, phone_number) WHERE phone_number IS NOT NULL;
CREATE INDEX customers_last_seen_idx     ON customers (tenant_id, last_seen_at DESC);

-- ---------------------------------------------------------------------------
-- Configuración del asistente
-- ---------------------------------------------------------------------------

CREATE TABLE chatbot_prompt_versions (
    id              uuid PRIMARY KEY,
    tenant_id       uuid NOT NULL REFERENCES tenants (id),
    version_number  integer NOT NULL,
    system_prompt   text NOT NULL,
    change_note     text,
    created_by      uuid,
    created_at      timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, version_number),
    FOREIGN KEY (tenant_id, created_by) REFERENCES human_agents (tenant_id, id)
);

CREATE TABLE chatbot_configurations (
    id                        uuid PRIMARY KEY,
    tenant_id                 uuid NOT NULL UNIQUE REFERENCES tenants (id),
    active_prompt_version_id  uuid NOT NULL,
    assistant_enabled         boolean NOT NULL DEFAULT true,
    llm_model                 text,          -- null: usa el modelo por defecto del entorno
    history_message_limit     integer NOT NULL DEFAULT 20 CHECK (history_message_limit BETWEEN 1 AND 100),
    max_tool_iterations       integer NOT NULL DEFAULT 4  CHECK (max_tool_iterations BETWEEN 1 AND 10),
    debounce_seconds          integer NOT NULL DEFAULT 4  CHECK (debounce_seconds BETWEEN 0 AND 60),
    debounce_max_seconds      integer NOT NULL DEFAULT 20 CHECK (debounce_max_seconds BETWEEN 0 AND 300),
    inactivity_close_hours    integer NOT NULL DEFAULT 24 CHECK (inactivity_close_hours > 0),
    business_hours            jsonb NOT NULL DEFAULT '{}',
    handoff_rules             jsonb NOT NULL DEFAULT '{}',
    privacy_notice            text NOT NULL,
    disabled_message          text NOT NULL,
    outside_hours_message     text NOT NULL,
    fallback_message          text NOT NULL,
    message_retention_days    integer CHECK (message_retention_days > 0),
    updated_by                uuid,
    created_at                timestamptz NOT NULL DEFAULT now(),
    updated_at                timestamptz NOT NULL DEFAULT now(),
    version                   integer NOT NULL DEFAULT 0,
    FOREIGN KEY (tenant_id, active_prompt_version_id) REFERENCES chatbot_prompt_versions (tenant_id, id),
    FOREIGN KEY (tenant_id, updated_by) REFERENCES human_agents (tenant_id, id)
);

CREATE TABLE frequently_asked_questions (
    id             uuid PRIMARY KEY,
    tenant_id      uuid NOT NULL REFERENCES tenants (id),
    category       text,
    question       text NOT NULL,
    answer         text NOT NULL,
    active         boolean NOT NULL DEFAULT true,
    sort_order     integer NOT NULL DEFAULT 0,
    search_vector  tsvector GENERATED ALWAYS AS
                   (to_tsvector('spanish', question || ' ' || answer)) STORED,
    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    version        integer NOT NULL DEFAULT 0
);
CREATE INDEX faq_listing_idx ON frequently_asked_questions (tenant_id, active, sort_order);
CREATE INDEX faq_search_idx  ON frequently_asked_questions USING gin (search_vector);

-- ---------------------------------------------------------------------------
-- Catálogo
-- ---------------------------------------------------------------------------

CREATE TABLE products (
    id                  uuid PRIMARY KEY,
    tenant_id           uuid NOT NULL REFERENCES tenants (id),
    kind                text NOT NULL DEFAULT 'PRODUCT' CHECK (kind IN ('PRODUCT', 'SERVICE')),
    name                text NOT NULL,
    description         text,
    min_order_quantity  integer NOT NULL DEFAULT 1 CHECK (min_order_quantity >= 1),
    lead_time_days      integer CHECK (lead_time_days >= 0),
    attributes          jsonb NOT NULL DEFAULT '{}',
    active              boolean NOT NULL DEFAULT true,
    external_id         text,          -- id en el sistema de origen del catálogo
    public_url          text,          -- enlace que el bot envía al cliente
    synced_at           timestamptz,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    version             integer NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id)
);
CREATE UNIQUE INDEX products_external_uq ON products (tenant_id, external_id) WHERE external_id IS NOT NULL;
CREATE INDEX products_listing_idx ON products (tenant_id, active);

-- Opciones genéricas por rubro. Piloto: SIZE, COLOR, PLACEMENT, DESIGN_SIZE.
-- attributes guarda datos propios de cada tipo, como las medidas máximas de una estampa.
CREATE TABLE product_options (
    id           uuid PRIMARY KEY,
    tenant_id    uuid NOT NULL REFERENCES tenants (id),
    product_id   uuid NOT NULL,
    option_type  text NOT NULL,
    value        text NOT NULL,
    attributes   jsonb NOT NULL DEFAULT '{}',
    active       boolean NOT NULL DEFAULT true,
    sort_order   integer NOT NULL DEFAULT 0,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, product_id, id),
    UNIQUE (tenant_id, product_id, option_type, value),
    FOREIGN KEY (tenant_id, product_id) REFERENCES products (tenant_id, id) ON DELETE CASCADE
);

-- Componentes del precio unitario, por cantidad y vigencia.
-- Sin option_id: precio base del producto (la prenda).
-- Con option_id: importe que se suma por esa opción (piloto: cada estampa según su tamaño
-- y el adicional de talle especial). El descuento mayorista es otra escala del precio base.
CREATE TABLE price_tiers (
    id            uuid PRIMARY KEY,
    tenant_id     uuid NOT NULL REFERENCES tenants (id),
    product_id    uuid NOT NULL,
    option_id     uuid,
    min_quantity  integer NOT NULL CHECK (min_quantity >= 1),
    max_quantity  integer,
    unit_price    numeric(12, 2) NOT NULL CHECK (unit_price >= 0),
    valid_from    timestamptz NOT NULL DEFAULT now(),
    valid_to      timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CHECK (max_quantity IS NULL OR max_quantity >= min_quantity),
    CHECK (valid_to IS NULL OR valid_to > valid_from),
    FOREIGN KEY (tenant_id, product_id) REFERENCES products (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, product_id, option_id)
        REFERENCES product_options (tenant_id, product_id, id) ON DELETE CASCADE,
    -- Dos filas nunca pueden dar precios distintos para el mismo pedido.
    CONSTRAINT price_tiers_no_overlap EXCLUDE USING gist (
        tenant_id WITH =,
        product_id WITH =,
        (COALESCE(option_id, '00000000-0000-0000-0000-000000000000'::uuid)) WITH =,
        int4range(min_quantity, max_quantity, '[]') WITH &&,
        tstzrange(valid_from, valid_to, '[)') WITH &&
    )
);

-- ---------------------------------------------------------------------------
-- Conversaciones y mensajes
-- ---------------------------------------------------------------------------

CREATE TABLE conversations (
    id                   uuid PRIMARY KEY,
    tenant_id            uuid NOT NULL REFERENCES tenants (id),
    customer_id          uuid NOT NULL,
    whatsapp_account_id  uuid NOT NULL,
    status               text NOT NULL DEFAULT 'ACTIVE'
                         CHECK (status IN ('ACTIVE', 'WAITING_FOR_AGENT', 'WITH_AGENT', 'CLOSED')),
    assigned_agent_id    uuid,
    last_inbound_at      timestamptz,    -- base de la ventana de 24 h
    last_message_at      timestamptz NOT NULL DEFAULT now(),
    opened_at            timestamptz NOT NULL DEFAULT now(),
    closed_at            timestamptz,
    close_reason         text,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    version              integer NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, customer_id) REFERENCES customers (tenant_id, id),
    FOREIGN KEY (tenant_id, whatsapp_account_id) REFERENCES whatsapp_accounts (tenant_id, id),
    FOREIGN KEY (tenant_id, assigned_agent_id) REFERENCES human_agents (tenant_id, id),
    CONSTRAINT conversations_agent_ck CHECK (status <> 'WITH_AGENT' OR assigned_agent_id IS NOT NULL),
    CONSTRAINT conversations_closed_ck CHECK ((status = 'CLOSED') = (closed_at IS NOT NULL))
);
CREATE UNIQUE INDEX conversations_one_open_uq
    ON conversations (tenant_id, whatsapp_account_id, customer_id) WHERE status <> 'CLOSED';
CREATE INDEX conversations_inbox_idx    ON conversations (tenant_id, status, last_message_at DESC);
CREATE INDEX conversations_customer_idx ON conversations (tenant_id, customer_id, opened_at DESC);
CREATE INDEX conversations_inactive_idx ON conversations (last_message_at) WHERE status <> 'CLOSED';

CREATE TABLE messages (
    id               uuid PRIMARY KEY,
    tenant_id        uuid NOT NULL REFERENCES tenants (id),
    conversation_id  uuid NOT NULL,
    direction        text NOT NULL CHECK (direction IN ('INBOUND', 'OUTBOUND')),
    sender_type      text NOT NULL CHECK (sender_type IN ('CUSTOMER', 'ASSISTANT', 'AGENT', 'SYSTEM')),
    agent_id         uuid,
    wamid            text,           -- null hasta que Meta acepta un saliente
    type             text NOT NULL DEFAULT 'TEXT',
    body             text,
    template_name    text,
    status           text NOT NULL CHECK (status IN
                     ('RECEIVED', 'PENDING', 'SENDING', 'SENT', 'DELIVERED', 'READ', 'FAILED', 'UNKNOWN')),
    error_code       text,
    error_detail     text,
    provider_at      timestamptz,    -- timestamp informado por Meta
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, wamid),       -- idempotencia: NULL no colisiona
    FOREIGN KEY (tenant_id, conversation_id) REFERENCES conversations (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, agent_id) REFERENCES human_agents (tenant_id, id),
    CONSTRAINT messages_sender_ck CHECK ((direction = 'INBOUND') = (sender_type = 'CUSTOMER')),
    CONSTRAINT messages_agent_ck  CHECK ((sender_type = 'AGENT') = (agent_id IS NOT NULL)),
    CONSTRAINT messages_status_ck CHECK ((direction = 'INBOUND') = (status = 'RECEIVED'))
);
CREATE INDEX messages_history_idx ON messages (tenant_id, conversation_id, created_at DESC, id DESC);
CREATE INDEX messages_stats_idx   ON messages (tenant_id, created_at);
CREATE INDEX messages_outbox_idx  ON messages (created_at) WHERE status = 'PENDING';
CREATE INDEX messages_sending_idx ON messages (updated_at) WHERE status = 'SENDING';

-- WhatsApp manda un adjunto por mensaje.
CREATE TABLE message_media (
    id                 uuid PRIMARY KEY,
    tenant_id          uuid NOT NULL REFERENCES tenants (id),
    message_id         uuid NOT NULL,
    wa_media_id        text,
    mime_type          text NOT NULL,
    size_bytes         bigint CHECK (size_bytes >= 0),
    sha256             text,
    storage_key        text,
    original_filename  text,
    download_status    text NOT NULL DEFAULT 'PENDING'
                       CHECK (download_status IN ('PENDING', 'STORED', 'FAILED', 'REJECTED')),
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, message_id),
    FOREIGN KEY (tenant_id, message_id) REFERENCES messages (tenant_id, id) ON DELETE CASCADE
);
CREATE INDEX message_media_pending_idx ON message_media (created_at) WHERE download_status = 'PENDING';

-- Auditoría de estados: derivaciones, tomas y retorno a la IA.
CREATE TABLE conversation_transitions (
    id               uuid PRIMARY KEY,
    tenant_id        uuid NOT NULL REFERENCES tenants (id),
    conversation_id  uuid NOT NULL,
    from_status      text,
    to_status        text NOT NULL,
    actor_type       text NOT NULL CHECK (actor_type IN ('SYSTEM', 'ASSISTANT', 'AGENT', 'CUSTOMER')),
    agent_id         uuid,
    reason           text,
    created_at       timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, conversation_id) REFERENCES conversations (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, agent_id) REFERENCES human_agents (tenant_id, id),
    CONSTRAINT transitions_agent_ck CHECK (actor_type <> 'AGENT' OR agent_id IS NOT NULL)
);
CREATE INDEX transitions_conversation_idx ON conversation_transitions (tenant_id, conversation_id, created_at);
CREATE INDEX transitions_stats_idx        ON conversation_transitions (tenant_id, to_status, created_at);

-- ---------------------------------------------------------------------------
-- Colas de procesamiento
-- ---------------------------------------------------------------------------

-- Sin tenant_id obligatorio: la empresa se conoce recién al parsear el evento.
CREATE TABLE webhook_events (
    id               uuid PRIMARY KEY,
    tenant_id        uuid REFERENCES tenants (id),
    payload          jsonb NOT NULL,
    status           text NOT NULL DEFAULT 'PENDING'
                     CHECK (status IN ('PENDING', 'PROCESSING', 'DONE', 'DEAD')),
    attempts         integer NOT NULL DEFAULT 0,
    next_attempt_at  timestamptz NOT NULL DEFAULT now(),
    locked_until     timestamptz,
    last_error       text,
    received_at      timestamptz NOT NULL DEFAULT now(),
    processed_at     timestamptz
);
CREATE INDEX webhook_events_claim_idx ON webhook_events (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX webhook_events_lease_idx ON webhook_events (locked_until) WHERE status = 'PROCESSING';
CREATE INDEX webhook_events_purge_idx ON webhook_events (received_at);

CREATE TABLE conversation_turns (
    id               uuid PRIMARY KEY,
    tenant_id        uuid NOT NULL REFERENCES tenants (id),
    conversation_id  uuid NOT NULL,
    status           text NOT NULL DEFAULT 'PENDING'
                     CHECK (status IN ('PENDING', 'PROCESSING', 'DONE', 'DISCARDED', 'FAILED')),
    first_queued_at  timestamptz NOT NULL DEFAULT now(),
    run_after        timestamptz NOT NULL,
    locked_until     timestamptz,
    locked_by        text,
    attempts         integer NOT NULL DEFAULT 0,
    last_error       text,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, conversation_id) REFERENCES conversations (tenant_id, id) ON DELETE CASCADE
);
-- Un turno en espera y uno en curso, como máximo, por conversación.
CREATE UNIQUE INDEX turns_one_pending_uq    ON conversation_turns (conversation_id) WHERE status = 'PENDING';
CREATE UNIQUE INDEX turns_one_processing_uq ON conversation_turns (conversation_id) WHERE status = 'PROCESSING';
CREATE INDEX turns_claim_idx ON conversation_turns (run_after) WHERE status = 'PENDING';
CREATE INDEX turns_lease_idx ON conversation_turns (locked_until) WHERE status = 'PROCESSING';
CREATE INDEX turns_purge_idx ON conversation_turns (updated_at) WHERE status IN ('DONE', 'DISCARDED', 'FAILED');

-- Sobrevive al borrado de la conversación: costos sin datos personales.
CREATE TABLE assistant_runs (
    id                 uuid PRIMARY KEY,
    tenant_id          uuid NOT NULL REFERENCES tenants (id),
    conversation_id    uuid,
    turn_id            uuid,
    prompt_version_id  uuid NOT NULL,
    model              text NOT NULL,
    input_tokens       integer NOT NULL DEFAULT 0,
    cached_tokens      integer NOT NULL DEFAULT 0,
    output_tokens      integer NOT NULL DEFAULT 0,
    tool_names         text[] NOT NULL DEFAULT '{}',
    iterations         integer NOT NULL DEFAULT 0,
    latency_ms         integer,
    estimated_cost_usd numeric(12, 6),
    outcome            text NOT NULL CHECK (outcome IN ('REPLIED', 'HANDED_OFF', 'DISCARDED', 'FAILED')),
    created_at         timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, conversation_id) REFERENCES conversations (tenant_id, id)
        ON DELETE SET NULL (conversation_id),
    FOREIGN KEY (tenant_id, turn_id) REFERENCES conversation_turns (tenant_id, id)
        ON DELETE SET NULL (turn_id),
    FOREIGN KEY (tenant_id, prompt_version_id) REFERENCES chatbot_prompt_versions (tenant_id, id)
);
CREATE INDEX assistant_runs_stats_idx ON assistant_runs (tenant_id, created_at);
CREATE INDEX assistant_runs_conversation_idx ON assistant_runs (tenant_id, conversation_id);

-- ---------------------------------------------------------------------------
-- Consentimiento, intereses y oportunidades
-- ---------------------------------------------------------------------------

-- Solo se agregan filas: es evidencia del consentimiento.
CREATE TABLE customer_consents (
    id                 uuid PRIMARY KEY,
    tenant_id          uuid NOT NULL REFERENCES tenants (id),
    customer_id        uuid NOT NULL,
    consent_type       text NOT NULL CHECK (consent_type IN ('DATA_PROCESSING', 'MARKETING')),
    status             text NOT NULL CHECK (status IN ('GRANTED', 'REVOKED')),
    policy_version     text NOT NULL,
    source             text NOT NULL,
    source_message_id  uuid,
    occurred_at        timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, customer_id) REFERENCES customers (tenant_id, id),
    FOREIGN KEY (tenant_id, source_message_id) REFERENCES messages (tenant_id, id)
        ON DELETE SET NULL (source_message_id)
);
CREATE INDEX consents_customer_idx ON customer_consents (tenant_id, customer_id, consent_type, occurred_at DESC);

CREATE TABLE customer_interests (
    id                 uuid PRIMARY KEY,
    tenant_id          uuid NOT NULL REFERENCES tenants (id),
    customer_id        uuid NOT NULL,
    category           text,
    description        text NOT NULL,
    source_message_id  uuid,
    created_at         timestamptz NOT NULL DEFAULT now(),
    FOREIGN KEY (tenant_id, customer_id) REFERENCES customers (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, source_message_id) REFERENCES messages (tenant_id, id)
        ON DELETE SET NULL (source_message_id)
);
CREATE INDEX interests_customer_idx ON customer_interests (tenant_id, customer_id, created_at DESC);

CREATE TABLE leads (
    id                 uuid PRIMARY KEY,
    tenant_id          uuid NOT NULL REFERENCES tenants (id),
    customer_id        uuid NOT NULL,
    conversation_id    uuid,
    product_id         uuid,
    assigned_agent_id  uuid,
    status             text NOT NULL DEFAULT 'NEW'
                       CHECK (status IN ('NEW', 'QUALIFIED', 'QUOTED', 'WON', 'LOST')),
    quantity           integer CHECK (quantity > 0),
    details            jsonb NOT NULL DEFAULT '{}',   -- talles, colores, diseños y su tamaño, localidad
    estimated_amount   numeric(14, 2) CHECK (estimated_amount >= 0),
    currency           text CHECK (char_length(currency) = 3),
    quote_snapshot     jsonb,                         -- precio y escala usados al cotizar
    lost_reason        text,
    closed_at          timestamptz,
    created_at         timestamptz NOT NULL DEFAULT now(),
    updated_at         timestamptz NOT NULL DEFAULT now(),
    version            integer NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, customer_id) REFERENCES customers (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, conversation_id) REFERENCES conversations (tenant_id, id)
        ON DELETE SET NULL (conversation_id),
    FOREIGN KEY (tenant_id, product_id) REFERENCES products (tenant_id, id)
        ON DELETE SET NULL (product_id),
    FOREIGN KEY (tenant_id, assigned_agent_id) REFERENCES human_agents (tenant_id, id),
    CONSTRAINT leads_closed_ck CHECK ((status IN ('WON', 'LOST')) = (closed_at IS NOT NULL))
);
CREATE UNIQUE INDEX leads_one_open_uq ON leads (tenant_id, conversation_id) WHERE status NOT IN ('WON', 'LOST');
CREATE INDEX leads_pipeline_idx ON leads (tenant_id, status, updated_at DESC);
CREATE INDEX leads_customer_idx ON leads (tenant_id, customer_id, created_at DESC);

-- Diseños enviados por el cliente y su revisión por un operador.
CREATE TABLE lead_attachments (
    id             uuid PRIMARY KEY,
    tenant_id      uuid NOT NULL REFERENCES tenants (id),
    lead_id        uuid NOT NULL,
    media_id       uuid NOT NULL,
    review_status  text NOT NULL DEFAULT 'PENDING_REVIEW'
                   CHECK (review_status IN ('PENDING_REVIEW', 'APPROVED', 'REJECTED')),
    reviewed_by    uuid,
    reviewed_at    timestamptz,
    review_note    text,
    created_at     timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, lead_id, media_id),
    FOREIGN KEY (tenant_id, lead_id) REFERENCES leads (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, media_id) REFERENCES message_media (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, reviewed_by) REFERENCES human_agents (tenant_id, id),
    CONSTRAINT attachments_review_ck
        CHECK ((review_status = 'PENDING_REVIEW') = (reviewed_at IS NULL))
);
CREATE INDEX attachments_review_idx ON lead_attachments (tenant_id, review_status, created_at);
