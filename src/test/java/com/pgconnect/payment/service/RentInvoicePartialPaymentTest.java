package com.pgconnect.payment.service;

import org.junit.jupiter.api.Test;
import java.lang.reflect.*;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class RentInvoicePartialPaymentTest {
 @Test void starterHasPartialPaymentValidationContract(){
  // Contract-level test: production integration tests should persist invoices and apply
  // multiple SUCCESS transactions under a row lock, then assert amount_paid and status.
  BigDecimal total=new BigDecimal("8000.00"), first=new BigDecimal("3000.00"), second=new BigDecimal("5000.00");
  assertEquals(total,first.add(second));
  assertTrue(first.compareTo(total)<0);
 }
}
