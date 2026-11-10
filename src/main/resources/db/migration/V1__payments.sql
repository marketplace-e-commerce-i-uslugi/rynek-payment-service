-- Płatność kupującego za jedno lub kilka zamówień (zakup z koszyka od kilku sprzedawców to kilka zamówień
-- i jedna płatność). Kwota jest sumą zamówień z chwili rozpoczęcia płatności.
CREATE TABLE payments (
    id              uuid PRIMARY KEY,
    buyer_id        uuid         NOT NULL,
    reference_type  varchar(10)  NOT NULL CHECK (reference_type IN ('ORDER', 'BOOKING')),
    amount          bigint       NOT NULL CHECK (amount > 0),
    currency        varchar(3)   NOT NULL DEFAULT 'PLN',
    method          varchar(10)  NOT NULL CHECK (method IN ('BLIK', 'CARD', 'TRANSFER')),
    status          varchar(20)  NOT NULL
        CHECK (status IN ('PENDING', 'WAITING_FOR_BLIK', 'SUCCEEDED', 'FAILED', 'REFUNDED')),
    -- Powód niepowodzenia albo zwrotu, po polsku (pokazywany kupującemu).
    status_reason   varchar(200),
    provider        varchar(20)  NOT NULL,
    blik_attempts   int          NOT NULL DEFAULT 0,
    -- Udana płatność czeka na oznaczenie zamówień jako opłaconych (order-service mógł nie odpowiadać); ponawiane co minutę.
    orders_confirmed boolean     NOT NULL DEFAULT false,
    idempotency_key varchar(100) NOT NULL,
    created_at      timestamptz  NOT NULL,
    updated_at      timestamptz  NOT NULL,
    version         int          NOT NULL DEFAULT 0,
    UNIQUE (buyer_id, idempotency_key)
);

CREATE INDEX payments_buyer_idx ON payments (buyer_id, created_at DESC);

-- Zamówienia (albo rezerwacje) opłacane tą płatnością.
CREATE TABLE payment_references (
    payment_id   uuid NOT NULL REFERENCES payments (id) ON DELETE CASCADE,
    position     int  NOT NULL,
    reference_id uuid NOT NULL,
    PRIMARY KEY (payment_id, position)
);

CREATE INDEX payment_references_reference_idx ON payment_references (reference_id);
