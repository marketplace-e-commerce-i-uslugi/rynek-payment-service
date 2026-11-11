package pl.rynek.payment.payments;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface SellerEarningRepository extends JpaRepository<SellerEarning, UUID> {

    List<SellerEarning> findByPaymentId(UUID paymentId);

    /** Zamówienia, których środki czekają na realizację (bez w pełni zwróconych: tam nie ma już czego zwalniać). */
    @Query("select e.orderId from SellerEarning e where e.sellerId = :sellerId and e.releasedAt is null and e.refunded < e.amount")
    List<UUID> findUnreleasedOrderIds(UUID sellerId);

    @Modifying
    @Query("update SellerEarning e set e.releasedAt = :now where e.orderId in :orderIds and e.releasedAt is null")
    int release(Collection<UUID> orderIds, Instant now);

    @Query("select coalesce(sum(e.amount - e.refunded), 0) from SellerEarning e where e.sellerId = :sellerId and e.releasedAt is null")
    long pending(UUID sellerId);

    @Query("select coalesce(sum(e.amount - e.refunded), 0) from SellerEarning e where e.sellerId = :sellerId and e.releasedAt is not null")
    long released(UUID sellerId);
}
