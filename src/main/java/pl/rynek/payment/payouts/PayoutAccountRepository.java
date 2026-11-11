package pl.rynek.payment.payouts;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface PayoutAccountRepository extends JpaRepository<PayoutAccount, UUID> {

    /** Blokada na czas zlecania wypłaty: dwa równoległe przebiegi nie wypłacą tych samych środków dwa razy. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from PayoutAccount a where a.sellerId = :sellerId")
    Optional<PayoutAccount> lockById(UUID sellerId);

    @Query("select a.sellerId from PayoutAccount a")
    List<UUID> findAllSellerIds();
}
