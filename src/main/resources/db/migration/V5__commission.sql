-- Prowizja platformy z przychodu sprzedawcy: stały procent od wartości towarów (bez dostawy) albo zaliczki, naliczony przy zaksięgowaniu
-- płatności. Wcześniejsze przychody mają 0. Przy zwrocie prowizja maleje proporcjonalnie do zwróconej kwoty.
ALTER TABLE seller_earnings ADD COLUMN commission bigint NOT NULL DEFAULT 0 CHECK (commission >= 0);
