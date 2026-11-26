package com.pgconnect.payment.repo;
import com.pgconnect.payment.domain.RentInvoice;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.*;
import java.util.UUID;
public interface RentInvoiceRepository extends JpaRepository<RentInvoice,UUID> {
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select i from RentInvoice i where i.id=:id") Optional<RentInvoice> findByIdForUpdate(@Param("id") UUID id);
}
