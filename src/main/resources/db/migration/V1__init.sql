-- One row per checkout a website starts. No customer data: the provider's hosted page
-- collects it, and this service keeps only what it needs to track and report the payment.
CREATE TABLE checkouts (
    id                  text PRIMARY KEY,
    site_id             text        NOT NULL,
    reference           text        NOT NULL,
    item                text        NOT NULL,
    amount_minor        bigint      NOT NULL CHECK (amount_minor > 0),
    currency            char(3)     NOT NULL,
    status              text        NOT NULL CHECK (status IN ('created', 'paid', 'expired', 'refunded', 'disputed')),
    provider            text        NOT NULL,
    provider_ref        text        NOT NULL,
    provider_payment_id text,
    checkout_url        text        NOT NULL,
    success_url         text        NOT NULL,
    cancel_url          text        NOT NULL,
    idempotency_key     text,
    request_hash        text,
    expires_at          timestamptz NOT NULL,
    created_at          timestamptz NOT NULL DEFAULT now(),
    updated_at          timestamptz NOT NULL DEFAULT now(),
    paid_at             timestamptz
);

-- A retried create with the same Idempotency-Key returns the original checkout.
CREATE UNIQUE INDEX checkouts_idempotency_uq ON checkouts (site_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE UNIQUE INDEX checkouts_provider_ref_uq ON checkouts (provider, provider_ref);
CREATE INDEX checkouts_site_reference_idx ON checkouts (site_id, reference);

-- Provider webhooks are delivered at least once; the event id makes processing exactly once.
-- Only the id and type are kept, never the payload (it carries the customer's contact details).
CREATE TABLE provider_events (
    provider    text        NOT NULL,
    event_id    text        NOT NULL,
    event_type  text        NOT NULL,
    checkout_id text,
    received_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (provider, event_id)
);

-- Transactional outbox: a status change and the webhook that announces it to the website
-- commit together, and a dispatcher delivers it with retries.
CREATE TABLE webhook_deliveries (
    id              bigserial PRIMARY KEY,
    event_id        text        NOT NULL UNIQUE,
    site_id         text        NOT NULL,
    checkout_id     text        NOT NULL REFERENCES checkouts (id),
    event_type      text        NOT NULL,
    payload         text        NOT NULL,
    status          text        NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'delivered', 'failed')),
    attempts        int         NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    last_error      text,
    created_at      timestamptz NOT NULL DEFAULT now(),
    delivered_at    timestamptz
);

CREATE INDEX webhook_deliveries_due_idx ON webhook_deliveries (next_attempt_at) WHERE status = 'pending';
