package pl.rynek.payment.payments;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface RefundRepository extends JpaRepository<Refund, UUID> {

    Optional<Refund> findByIdempotencyKey(String idempotencyKey);

    @Query("select r.id from Refund r where r.orderNotified = false order by r.createdAt")
    List<UUID> findOrderUnnotified();
}
