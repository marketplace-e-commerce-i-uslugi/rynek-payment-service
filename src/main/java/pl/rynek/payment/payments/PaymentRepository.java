package pl.rynek.payment.payments;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByBuyerIdAndIdempotencyKey(UUID buyerId, String idempotencyKey);

    /** Płatności w toku obejmujące któreś z zamówień (nowa płatność je zastępuje). */
    @Query("select distinct p from Payment p join p.references r where r in :references"
            + " and p.status in (pl.rynek.payment.payments.PaymentStatus.PENDING, pl.rynek.payment.payments.PaymentStatus.WAITING_FOR_BLIK)")
    List<Payment> findOpenFor(Collection<UUID> references);

    @Query("select p.id from Payment p where p.status = pl.rynek.payment.payments.PaymentStatus.SUCCEEDED and p.ordersConfirmed = false")
    List<UUID> findUnconfirmed();
}
