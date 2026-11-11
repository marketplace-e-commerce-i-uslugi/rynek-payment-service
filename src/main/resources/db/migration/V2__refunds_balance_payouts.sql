-- Zwrócona część płatności. Płatność za kilka zamówień może być zwrócona częściowo (jedno zamówienie anulowane).
ALTER TABLE payments ADD COLUMN refunded_amount bigint NOT NULL DEFAULT 0 CHECK (refunded_amount >= 0);

-- Przychód sprzedawcy z opłaconego zamówienia (zapisywany przy zaksięgowaniu płatności). Środki są oczekujące,
-- dopóki zamówienie nie jest zrealizowane (released_at), potem dostępne do wypłaty. refunded: suma zwrotów.
CREATE TABLE seller_earnings (
    order_id    uuid PRIMARY KEY,
    payment_id  uuid        NOT NULL REFERENCES payments (id),
    seller_id   uuid        NOT NULL,
    amount      bigint      NOT NULL CHECK (amount > 0),
    refunded    bigint      NOT NULL DEFAULT 0 CHECK (refunded >= 0 AND refunded <= amount),
    released_at timestamptz,
    created_at  timestamptz NOT NULL
);

CREATE INDEX seller_earnings_seller_idx ON seller_earnings (seller_id);
CREATE INDEX seller_earnings_payment_idx ON seller_earnings (payment_id);

-- Zwroty pieniędzy kupującemu. requested_by: sprzedawca albo admin; NULL = automatycznie po anulowaniu zamówienia.
-- idempotency_key: "cancel:<orderId>" dla zwrotu po anulowaniu, "<userId>:<klucz>" dla Idempotency-Key z żądania.
CREATE TABLE refunds (
    id              uuid PRIMARY KEY,
    payment_id      uuid         NOT NULL REFERENCES payments (id),
    order_id        uuid         NOT NULL REFERENCES seller_earnings (order_id),
    seller_id       uuid         NOT NULL,
    amount          bigint       NOT NULL CHECK (amount > 0),
    reason          varchar(200),
    requested_by    uuid,
    idempotency_key varchar(150) UNIQUE,
    created_at      timestamptz  NOT NULL
);

CREATE INDEX refunds_order_idx ON refunds (order_id);

-- Rachunek do wypłat sprzedawcy (jeden na sprzedawcę). IBAN bez spacji.
CREATE TABLE payout_accounts (
    seller_id  uuid PRIMARY KEY,
    holder     varchar(100) NOT NULL,
    iban       varchar(34)  NOT NULL,
    updated_at timestamptz  NOT NULL
);

-- Wypłaty dostępnych środków na rachunek z chwili zlecenia.
CREATE TABLE payouts (
    id         uuid PRIMARY KEY,
    seller_id  uuid        NOT NULL,
    amount     bigint      NOT NULL CHECK (amount > 0),
    iban       varchar(34) NOT NULL,
    status     varchar(20) NOT NULL CHECK (status IN ('IN_PROGRESS', 'PAID')),
    created_at timestamptz NOT NULL,
    paid_at    timestamptz
);

CREATE INDEX payouts_seller_idx ON payouts (seller_id, created_at DESC);
CREATE INDEX payouts_in_progress_idx ON payouts (created_at) WHERE status = 'IN_PROGRESS';
