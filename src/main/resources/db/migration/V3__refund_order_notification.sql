-- Zwrot zlecony przez sprzedawcę albo admina zgłaszamy order-service: zgłoszony przez kupującego zwrot staje się przyjęty.
-- false = order-service jeszcze nie przyjął zgłoszenia (ponawiane co minutę). Zwroty po anulowaniu niczego nie zgłaszają.
ALTER TABLE refunds ADD COLUMN order_notified boolean NOT NULL DEFAULT true;

CREATE INDEX refunds_order_unnotified_idx ON refunds (created_at) WHERE NOT order_notified;
