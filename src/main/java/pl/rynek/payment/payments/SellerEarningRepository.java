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

    @Query(value = "select * from seller_earnings where seller_id = :sellerId order by created_at desc, order_id limit :limit offset :offset", nativeQuery = true)
    List<SellerEarning> findOfSeller(UUID sellerId, int offset, int limit);

    /**
     * Zamówienia (rezerwacje), których środki czekają na realizację (wykonanie), bez w pełni zwróconych: tam nie ma już
     * czego zwalniać.
     */
    @Query("select e.orderId from SellerEarning e where e.sellerId = :sellerId and e.referenceType = :type and e.releasedAt is null"
            + " and e.refunded < e.amount")
    List<UUID> findUnreleasedIds(UUID sellerId, ReferenceType type);

    @Modifying
    @Query("update SellerEarning e set e.releasedAt = :now where e.orderId in :orderIds and e.releasedAt is null")
    int release(Collection<UUID> orderIds, Instant now);

    @Query("select coalesce(sum((e.amount - e.refunded) - (e.commission * (e.amount - e.refunded)) / e.amount), 0) from SellerEarning e"
            + " where e.sellerId = :sellerId and e.releasedAt is null")
    long pending(UUID sellerId);

    @Query("select coalesce(sum((e.amount - e.refunded) - (e.commission * (e.amount - e.refunded)) / e.amount), 0) from SellerEarning e"
            + " where e.sellerId = :sellerId and e.releasedAt is not null")
    long released(UUID sellerId);
}
