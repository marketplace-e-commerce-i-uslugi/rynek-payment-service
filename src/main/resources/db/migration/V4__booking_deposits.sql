-- Zaliczki za rezerwacje: przychód usługodawcy jest dostępny po wykonaniu usługi (booking-service), a przychód sprzedawcy
-- po realizacji zamówienia (order-service). Kolumna order_id przechowuje id zamówienia albo rezerwacji.
ALTER TABLE seller_earnings ADD COLUMN reference_type varchar(10) NOT NULL DEFAULT 'ORDER'
    CHECK (reference_type IN ('ORDER', 'BOOKING'));
