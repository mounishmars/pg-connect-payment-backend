package com.pgconnect.payment.service;

import com.pgconnect.payment.domain.PaymentOrder;
import com.pgconnect.payment.gateway.StripeClient;
import com.pgconnect.payment.repo.PaymentOrderRepository;
import com.pgconnect.payment.repo.RentInvoiceRepository;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RentPaymentIdempotencyTest {
    @Test
    void idempotencyKeyCannotExposeAnotherUsersPaymentSession() throws Exception {
        var invoices=mock(RentInvoiceRepository.class);
        var orders=mock(PaymentOrderRepository.class);
        var reservation=new PaymentOrderReservation(invoices,orders);
        UUID user=UUID.randomUUID(), invoice=UUID.randomUUID();
        PaymentOrder order=new PaymentOrder(UUID.randomUUID(),"ref",invoice,user,UUID.randomUUID(),new BigDecimal("10.00"),"key");
        order.activate("pi_test","secret");
        when(orders.findByIdempotencyKey("key")).thenReturn(Optional.of(order));

        assertSame(order,reservation.reserve(invoice,user,new BigDecimal("10.0"),"key"));
        assertThrows(IllegalArgumentException.class,()->reservation.reserve(invoice,UUID.randomUUID(),new BigDecimal("10.00"),"key"));
        assertThrows(IllegalArgumentException.class,()->reservation.reserve(UUID.randomUUID(),user,new BigDecimal("10.00"),"key"));
        assertThrows(IllegalArgumentException.class,()->reservation.reserve(invoice,user,new BigDecimal("11.00"),"key"));
        verifyNoInteractions(invoices);
    }
}
