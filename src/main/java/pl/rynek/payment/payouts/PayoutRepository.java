package pl.rynek.payment.payouts;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PayoutRepository extends JpaRepository<Payout, UUID> {

    @Query(value = "select * from payouts where seller_id = :sellerId order by created_at desc, id limit :limit offset :offset", nativeQuery = true)
    List<Payout> findOfSeller(UUID sellerId, int offset, int limit);

    /** Wszystkie wypłaty, także w realizacji: te środki już zeszły z salda. */
    @Query("select coalesce(sum(p.amount), 0) from Payout p where p.sellerId = :sellerId")
    long total(UUID sellerId);

    @Query("select p from Payout p where p.status = pl.rynek.payment.payouts.PayoutStatus.IN_PROGRESS order by p.createdAt")
    List<Payout> findInProgress();

    @Modifying
    @Query("update Payout p set p.status = pl.rynek.payment.payouts.PayoutStatus.PAID, p.paidAt = :now"
            + " where p.id = :id and p.status = pl.rynek.payment.payouts.PayoutStatus.IN_PROGRESS")
    int markPaid(UUID id, Instant now);
}
