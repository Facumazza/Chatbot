-- Pedidos que el bot cargó en el sistema de la web. El pedido vive en la web: acá queda solo
-- la referencia y el último estado visto, para avisarle al cliente cuando cambia.

CREATE TABLE orders (
    id                   uuid PRIMARY KEY,
    tenant_id            uuid NOT NULL REFERENCES tenants (id),
    conversation_id      uuid NOT NULL,
    customer_id          uuid NOT NULL,
    external_order_id    text NOT NULL,
    status               text NOT NULL,      -- estado según la web
    payment_status       text NOT NULL,      -- estado del pago según la web
    total                numeric(14, 2),
    delivery_method      text NOT NULL,
    request              jsonb NOT NULL,     -- lo que se envió a la web
    receipt_sent_at      timestamptz,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, external_order_id),
    FOREIGN KEY (tenant_id, conversation_id) REFERENCES conversations (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, customer_id) REFERENCES customers (tenant_id, id)
);
CREATE INDEX orders_open_idx ON orders (tenant_id, status) WHERE status NOT IN ('ENTREGADO', 'CANCELADO');
CREATE INDEX orders_conversation_idx ON orders (tenant_id, conversation_id, created_at DESC);
