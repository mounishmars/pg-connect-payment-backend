package com.pgconnect.payment.repo;
import com.pgconnect.payment.domain.PaymentTransaction;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction,UUID> {
 boolean existsByGatewayPaymentId(String id);
}
