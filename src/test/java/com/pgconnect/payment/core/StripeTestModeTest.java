package com.pgconnect.payment.core;

import com.pgconnect.payment.gateway.StripeClient;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import static org.junit.jupiter.api.Assertions.*;

class StripeTestModeTest {
  @Test
  void rejectsLiveKeysAndDisabledCheckout() {
    assertThrows(IllegalStateException.class, () -> new StripeClient("sk_live_dummy",true));
    StripeClient disabled=new StripeClient("",false);
    assertThrows(IllegalStateException.class,
        () -> disabled.createPaymentIntent("ref",new BigDecimal("1.00"),"user","a@example.com","key"));
  }
}
