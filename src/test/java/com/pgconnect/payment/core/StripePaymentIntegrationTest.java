package com.pgconnect.payment.core;

import com.pgconnect.payment.service.StripePaymentProcessor;
import com.pgconnect.payment.service.RentPaymentService;
import com.pgconnect.payment.gateway.StripeClient;
import com.stripe.model.PaymentIntent;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named="PGCONNECT_INTEGRATION_TESTS",matches="true")
class StripePaymentIntegrationTest {
  @Autowired JdbcTemplate jdbc;
  @Autowired StripePaymentProcessor processor;
  @Autowired EntityManager entityManager;
  @Autowired RentPaymentService paymentService;
  @MockitoBean StripeClient stripe;

  @Test
  void confirmsPartialPaymentOnceAndRejectsMismatchedAmount() {
    UUID tenant=UUID.randomUUID(), ownerUser=UUID.randomUUID(), owner=UUID.randomUUID();
    UUID invoice=UUID.randomUUID(), order=UUID.randomUUID();
    jdbc.update("INSERT INTO users(id,full_name) VALUES (?,?),(?,?)",tenant,"Tenant",ownerUser,"Owner");
    jdbc.update("INSERT INTO owner_profiles(id,user_id,display_name) VALUES (?,?,?)",owner,ownerUser,"Owner");
    jdbc.update("""
      INSERT INTO rent_invoices(id,invoice_number,tenant_user_id,owner_id,total_amount,due_date)
      VALUES (?,?,?,?,?,current_date)
      """,invoice,"INV-TEST",tenant,owner,new BigDecimal("8000.00"));
    jdbc.update("""
      INSERT INTO payment_orders(id,order_reference,rent_invoice_id,payer_user_id,owner_id,gateway,
        gateway_order_id,amount,status,idempotency_key)
      VALUES (?,?,?,?,?,'STRIPE','pi_test',?,'ACTIVE','test-key')
      """,order,"PGCR-TEST",invoice,tenant,owner,new BigDecimal("3000.00"));
    PaymentIntent intent=new PaymentIntent();
    intent.setId("pi_test"); intent.setLivemode(false); intent.setStatus("succeeded");
    intent.setCurrency("inr"); intent.setAmountReceived(299900L);
    intent.setMetadata(Map.of("order_id","PGCR-TEST","customer_id",tenant.toString()));
    assertThrows(IllegalStateException.class, () -> processor.applySucceeded(intent));
    intent.setAmountReceived(300000L);
    processor.applySucceeded(intent);
    entityManager.flush();
    processor.applySucceeded(intent);
    entityManager.flush();
    assertEquals(new BigDecimal("3000.00"),jdbc.queryForObject("SELECT amount_paid FROM rent_invoices WHERE id=?",BigDecimal.class,invoice));
    assertEquals("PARTIALLY_PAID",jdbc.queryForObject("SELECT status FROM rent_invoices WHERE id=?",String.class,invoice));
    assertEquals("PAID",jdbc.queryForObject("SELECT status FROM payment_orders WHERE id=?",String.class,order));
    assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM payment_transactions WHERE payment_order_id=?",Integer.class,order));
  }

  @Test
  void preventsConcurrentOpenPaymentsAndReusesSessionForSameAmount() throws Exception {
    UUID tenant=UUID.randomUUID(), ownerUser=UUID.randomUUID(), owner=UUID.randomUUID(), invoice=UUID.randomUUID();
    jdbc.update("INSERT INTO users(id,full_name) VALUES (?,?),(?,?)",tenant,"Tenant",ownerUser,"Owner");
    jdbc.update("INSERT INTO owner_profiles(id,user_id,display_name) VALUES (?,?,?)",owner,ownerUser,"Owner");
    jdbc.update("""
      INSERT INTO rent_invoices(id,invoice_number,tenant_user_id,owner_id,total_amount,due_date)
      VALUES (?,?,?,?,?,current_date)
      """,invoice,"INV-OPEN",tenant,owner,new BigDecimal("8000.00"));
    when(stripe.createPaymentIntent(anyString(),any(),anyString(),anyString(),anyString()))
        .thenReturn(new StripeClient.StripePaymentIntent("pi_open","test_client_secret"));
    var first=paymentService.createOrder(invoice,tenant,new BigDecimal("3000.00"),"key-one","test@example.com");
    var retried=paymentService.createOrder(invoice,tenant,new BigDecimal("3000.00"),"key-two","test@example.com");
    assertEquals(first.getId(),retried.getId());
    assertThrows(IllegalStateException.class, () -> paymentService.createOrder(
        invoice,tenant,new BigDecimal("4000.00"),"key-three","test@example.com"));
    verify(stripe,times(1)).createPaymentIntent(anyString(),any(),anyString(),anyString(),anyString());
  }

  @Test
  @Transactional(propagation=Propagation.NOT_SUPPORTED)
  void preservesReservationWhenStripeRequestFails() throws Exception {
    UUID tenant=UUID.randomUUID(), ownerUser=UUID.randomUUID(), owner=UUID.randomUUID(), invoice=UUID.randomUUID();
    jdbc.update("INSERT INTO users(id,full_name) VALUES (?,?),(?,?)",tenant,"Tenant",ownerUser,"Owner");
    jdbc.update("INSERT INTO owner_profiles(id,user_id,display_name) VALUES (?,?,?)",owner,ownerUser,"Owner");
    jdbc.update("""
      INSERT INTO rent_invoices(id,invoice_number,tenant_user_id,owner_id,total_amount,due_date)
      VALUES (?,?,?,?,?,current_date)
      """,invoice,"INV-"+invoice,tenant,owner,new BigDecimal("8000.00"));
    try {
      when(stripe.createPaymentIntent(anyString(),any(),anyString(),anyString(),anyString()))
          .thenThrow(new com.stripe.exception.ApiConnectionException("network error"))
          .thenReturn(new StripeClient.StripePaymentIntent("pi_retry","test_client_secret"));
      assertThrows(com.stripe.exception.ApiConnectionException.class, () -> paymentService.createOrder(
          invoice,tenant,new BigDecimal("3000.00"),"retry-key","test@example.com"));
      assertEquals("CREATED",jdbc.queryForObject("SELECT status FROM payment_orders WHERE rent_invoice_id=?",String.class,invoice));
      var retried=paymentService.createOrder(invoice,tenant,new BigDecimal("3000.00"),"retry-key","test@example.com");
      assertEquals("ACTIVE",retried.getStatus());
      assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM payment_orders WHERE rent_invoice_id=?",Integer.class,invoice));
      verify(stripe,times(2)).createPaymentIntent(anyString(),any(),anyString(),anyString(),eq("pgconnect-"+retried.getId()));
    } finally {
      jdbc.update("DELETE FROM payment_orders WHERE rent_invoice_id=?",invoice);
      jdbc.update("DELETE FROM rent_invoices WHERE id=?",invoice);
      jdbc.update("DELETE FROM owner_profiles WHERE id=?",owner);
      jdbc.update("DELETE FROM users WHERE id IN (?,?)",tenant,ownerUser);
    }
  }
}
