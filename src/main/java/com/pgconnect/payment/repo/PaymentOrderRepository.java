package com.pgconnect.payment.repo;
import com.pgconnect.payment.domain.PaymentOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface PaymentOrderRepository extends JpaRepository<PaymentOrder,UUID> {
 Optional<PaymentOrder> findByIdempotencyKey(String key);
 Optional<PaymentOrder> findByGatewayOrderId(String id);
 Optional<PaymentOrder> findByRentInvoiceIdAndGatewayAndStatusIn(UUID invoiceId, String gateway, Collection<String> statuses);
}
