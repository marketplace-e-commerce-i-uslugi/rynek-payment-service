package pl.rynek.payment.payments;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByBuyerIdAndIdempotencyKey(UUID buyerId, String idempotencyKey);

    /** Płatności w toku obejmujące któreś z zamówień (nowa płatność je zastępuje). */
    @Query("select distinct p from Payment p join p.references r where r in :references"
            + " and p.status in (pl.rynek.payment.payments.PaymentStatus.PENDING, pl.rynek.payment.payments.PaymentStatus.WAITING_FOR_BLIK)")
    List<Payment> findOpenFor(Collection<UUID> references);

    @Query("select p.id from Payment p where p.status = pl.rynek.payment.payments.PaymentStatus.SUCCEEDED and p.ordersConfirmed = false")
    List<UUID> findUnconfirmed();

    /** Zaksięgowana płatność za zamówienie (także częściowo albo w całości zwrócona). Zamówienie opłaca się raz. */
    @Query("select p from Payment p join p.references r where r = :orderId"
            + " and p.status in (pl.rynek.payment.payments.PaymentStatus.SUCCEEDED, pl.rynek.payment.payments.PaymentStatus.REFUNDED)")
    List<Payment> findSettledFor(UUID orderId);

    /** Zaksięgowane płatności, w których któreś zamówienie nie ma przychodu sprzedawcy (sprzed wprowadzenia salda). */
    @Query("select distinct p.id from Payment p join p.references r"
            + " where p.status in (pl.rynek.payment.payments.PaymentStatus.SUCCEEDED, pl.rynek.payment.payments.PaymentStatus.REFUNDED)"
            + " and p.referenceType = pl.rynek.payment.payments.ReferenceType.ORDER"
            + " and not exists (select e.orderId from SellerEarning e where e.orderId = r)")
    List<UUID> findWithoutEarnings();

    /** Blokada wiersza na czas zwrotu: równoległe zwroty z tej samej płatności nie przekroczą jej kwoty. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> lockById(UUID id);
}
