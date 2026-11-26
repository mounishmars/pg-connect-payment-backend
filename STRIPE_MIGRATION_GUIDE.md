# Stripe Migration Guide - Convert from Cashfree to Stripe

This guide explains how to convert the PG Connect Payment Starter from Cashfree to Stripe.

## ✅ Why Stripe for Testing?

- ✓ **No bank account needed** for sandbox testing
- ✓ **Free test mode** with test cards
- ✓ **Easy to set up** - just API keys
- ✓ **Great documentation**
- ✓ **WebHook testing built-in**

## 🚀 Step 1: Get Stripe Test API Keys

### 1.1 Create Stripe Account
1. Go to: https://dashboard.stripe.com/register
2. Sign up with your email
3. Verify your email

### 1.2 Get Test API Keys
1. Log in to Stripe Dashboard: https://dashboard.stripe.com
2. Click **"Developers"** (top right)
3. Go to **"API keys"**
4. You'll see:
   - **Publishable key** (starts with `pk_test_`)
   - **Secret key** (starts with `sk_test_`)
5. Copy both keys and save them

### 1.3 Get Webhook Secret
1. Still in Developers section
2. Go to **"Webhooks"**
3. Click **"Add endpoint"**
4. For testing locally, use: `http://localhost:8080/api/v1/webhooks/stripe`
5. Select events: `charge.succeeded`, `charge.failed`, `charge.refunded`
6. Click **"Create endpoint"**
7. Click **"Reveal"** to see the **Webhook Signing Secret** (starts with `whsec_`)
8. Copy this secret

---

## 🔧 Step 2: Update pom.xml Dependencies

Add Stripe dependency to your `pom.xml`:

```xml
<dependency>
    <groupId>com.stripe</groupId>
    <artifactId>stripe-java</artifactId>
    <version>26.1.0</version>
</dependency>
```

Full updated `<dependencies>` section:

```xml
<dependencies>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-jpa</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-validation</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-actuator</artifactId></dependency>
    <dependency><groupId>org.flywaydb</groupId><artifactId>flyway-core</artifactId></dependency>
    <dependency><groupId>org.flywaydb</groupId><artifactId>flyway-database-postgresql</artifactId></dependency>
    <dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId><scope>runtime</scope></dependency>
    <dependency><groupId>com.fasterxml.jackson.datatype</groupId><artifactId>jackson-datatype-jsr310</artifactId></dependency>
    <dependency><groupId>com.stripe</groupId><artifactId>stripe-java</artifactId><version>26.1.0</version></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
</dependencies>
```

---

## 📝 Step 3: Update application.yml

Add Stripe configuration to `src/main/resources/application.yml`:

```yaml
spring:
  datasource:
    url: ${DB_URL}
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
  jpa:
    hibernate:
      ddl-auto: validate
  flyway:
    enabled: true

server:
  port: 8080

stripe:
  api-key: ${STRIPE_API_KEY}
  webhook-secret: ${STRIPE_WEBHOOK_SECRET}
  success-url: http://localhost:8080/api/v1/payment/success
  cancel-url: http://localhost:8080/api/v1/payment/cancel
```

---

## 🔑 Step 4: Update Environment Variables

Replace Cashfree variables with Stripe:

```bash
# Remove these (Cashfree):
# export CASHFREE_BASE_URL='...'
# export CASHFREE_CLIENT_ID='...'
# export CASHFREE_CLIENT_SECRET='...'
# export CASHFREE_WEBHOOK_SECRET='...'

# Add these (Stripe):
export STRIPE_API_KEY='sk_test_...'  # Your secret key
export STRIPE_WEBHOOK_SECRET='whsec_...'  # Your webhook secret
```

Update `~/.zshrc_pgconnect_full`:

```bash
# Replace Cashfree section with:
export STRIPE_API_KEY='sk_test_your_secret_key'
export STRIPE_WEBHOOK_SECRET='whsec_your_webhook_secret'
```

---

## 💻 Step 5: Create Stripe Integration Files

### 5.1 Create StripeClient

Create file: `src/main/java/com/pgconnect/payment/gateway/StripeClient.java`

```java
package com.pgconnect.payment.gateway;

import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.param.PaymentIntentCreateParams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;

@Component
public class StripeClient {
    
    @Value("${stripe.api-key}")
    private String apiKey;
    
    public StripeClient(@Value("${stripe.api-key}") String apiKey) {
        Stripe.apiKey = apiKey;
    }
    
    public PaymentIntent createPaymentIntent(String description, long amountInCents, String customerId) 
            throws StripeException {
        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(amountInCents)
                .setCurrency("inr")
                .setDescription(description)
                .setCustomer(customerId)
                .build();
        
        return PaymentIntent.create(params);
    }
    
    public PaymentIntent retrievePaymentIntent(String paymentIntentId) throws StripeException {
        return PaymentIntent.retrieve(paymentIntentId);
    }
}
```

### 5.2 Create StripeWebhookVerifier

Create file: `src/main/java/com/pgconnect/payment/webhook/StripeWebhookVerifier.java`

```java
package com.pgconnect.payment.webhook;

import com.stripe.model.Event;
import com.stripe.net.Webhook;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class StripeWebhookVerifier {
    
    @Value("${stripe.webhook-secret}")
    private String webhookSecret;
    
    public Event verifyWebhook(String payload, String sigHeader) {
        try {
            return Webhook.constructEvent(payload, sigHeader, webhookSecret);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid webhook signature: " + e.getMessage());
        }
    }
}
```

### 5.3 Create StripeWebhookController

Create file: `src/main/java/com/pgconnect/payment/webhook/StripeWebhookController.java`

```java
package com.pgconnect.payment.webhook;

import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.pgconnect.payment.service.RentPaymentService;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.math.BigDecimal;

@RestController
@RequestMapping("/api/v1/webhooks/stripe")
public class StripeWebhookController {
    
    private static final Logger log = LoggerFactory.getLogger(StripeWebhookController.class);
    private final StripeWebhookVerifier verifier;
    private final RentPaymentService paymentService;
    
    public StripeWebhookController(StripeWebhookVerifier verifier, RentPaymentService paymentService) {
        this.verifier = verifier;
        this.paymentService = paymentService;
    }
    
    @PostMapping
    public void handleWebhook(@RequestBody String payload, 
                             @RequestHeader("Stripe-Signature") String sigHeader) {
        try {
            Event event = verifier.verifyWebhook(payload, sigHeader);
            
            switch (event.getType()) {
                case "charge.succeeded":
                    handleChargeSucceeded(event);
                    break;
                case "charge.failed":
                    handleChargeFailed(event);
                    break;
                default:
                    log.info("Unhandled event type: {}", event.getType());
            }
        } catch (Exception e) {
            log.error("Webhook error: {}", e.getMessage());
            throw new RuntimeException(e);
        }
    }
    
    private void handleChargeSucceeded(Event event) {
        PaymentIntent intent = (PaymentIntent) event.getDataObjectDeserializer()
                .getObject().orElse(null);
        if (intent != null) {
            log.info("Payment succeeded: {}", intent.getId());
            // Update your database with payment success
        }
    }
    
    private void handleChargeFailed(Event event) {
        PaymentIntent intent = (PaymentIntent) event.getDataObjectDeserializer()
                .getObject().orElse(null);
        if (intent != null) {
            log.info("Payment failed: {}", intent.getId());
            // Update your database with payment failure
        }
    }
}
```

---

## 🔄 Step 6: Update RentPaymentController

Modify: `src/main/java/com/pgconnect/payment/api/RentPaymentController.java`

```java
package com.pgconnect.payment.api;

import com.pgconnect.payment.domain.PaymentOrder;
import com.pgconnect.payment.service.RentPaymentService;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/customer/rent/payments")
public class RentPaymentController {
    
    private final RentPaymentService service;
    
    public RentPaymentController(RentPaymentService service) {
        this.service = service;
    }
    
    @PostMapping
    public CreatePaymentResponse create(
            @RequestHeader("X-Authenticated-User") UUID authenticatedUser,
            @RequestHeader("Idempotency-Key") @NotBlank String idempotencyKey,
            @Valid @RequestBody CreatePaymentRequest request) throws StripeException {
        
        PaymentOrder order = service.createOrder(
                request.invoiceId(), 
                authenticatedUser, 
                request.amount(), 
                idempotencyKey, 
                request.customerPhone()
        );
        
        // Create Stripe PaymentIntent
        PaymentIntent intent = service.createStripePaymentIntent(
                order.getId(),
                request.amount(),
                request.customerPhone()
        );
        
        return new CreatePaymentResponse(
                order.getId(),
                order.getOrderReference(),
                request.amount(),
                order.getStatus(),
                intent.getClientSecret()  // This is used by frontend
        );
    }
    
    public record CreatePaymentRequest(
            @NotNull UUID invoiceId,
            @NotNull @DecimalMin("0.01") BigDecimal amount,
            @NotBlank String customerPhone
    ) {}
    
    public record CreatePaymentResponse(
            UUID paymentOrderId,
            String orderReference,
            BigDecimal amount,
            String status,
            String clientSecret  // Changed from paymentSessionId
    ) {}
}
```

---

## 🧪 Step 7: Test with Stripe Test Cards

After setup, use these test card numbers in Stripe dashboard or your app:

**Successful Payment:**
- Card: `4242 4242 4242 4242`
- Expiry: `12/25` (or any future date)
- CVC: `123`
- ZIP: `12345`

**Failed Payment:**
- Card: `4000 0000 0000 0002`
- Expiry: `12/25`
- CVC: `123`

---

## 🚀 Step 8: Run the Application

```bash
# Update environment
source ~/.zshrc_pgconnect_full

# Or manually:
export STRIPE_API_KEY='sk_test_...'
export STRIPE_WEBHOOK_SECRET='whsec_...'

# Navigate to project
cd ~/Documents/pg/pg-connect-payment-starter

# Install dependencies
mvn clean install

# Run application
mvn spring-boot:run
```

---

## 🌐 API Usage

### Create Payment (Stripe)

```bash
curl -X POST http://localhost:8080/api/v1/customer/rent/payments \
  -H "Content-Type: application/json" \
  -H "X-Authenticated-User: 550e8400-e29b-41d4-a716-446655440000" \
  -H "Idempotency-Key: unique-key-123" \
  -d '{
    "invoiceId": "550e8400-e29b-41d4-a716-446655440001",
    "amount": 2500.00,
    "customerPhone": "9876543210"
  }'
```

**Response:**
```json
{
  "paymentOrderId": "550e8400-e29b-41d4-a716-446655440002",
  "orderReference": "ORDER-123456",
  "amount": 2500.00,
  "status": "pending",
  "clientSecret": "pi_test_..._secret_..."
}
```

Use the `clientSecret` on your frontend with Stripe.js to complete the payment.

---

## 🔗 Testing Webhooks Locally

For local testing, use **Stripe CLI**:

```bash
# Install Stripe CLI
brew install stripe/stripe-cli/stripe

# Login
stripe login

# Forward webhooks to your local app
stripe listen --forward-to localhost:8080/api/v1/webhooks/stripe

# This will show you the webhook signing secret
```

---

## ✅ Checklist

- [ ] Created Stripe account and got API keys
- [ ] Updated `pom.xml` with Stripe dependency
- [ ] Updated `application.yml` with Stripe config
- [ ] Set environment variables (STRIPE_API_KEY, STRIPE_WEBHOOK_SECRET)
- [ ] Created `StripeClient.java`
- [ ] Created `StripeWebhookVerifier.java`
- [ ] Created `StripeWebhookController.java`
- [ ] Updated `RentPaymentController.java`
- [ ] Run `mvn clean install`
- [ ] Start application with `mvn spring-boot:run`
- [ ] Test with Stripe test cards

---

## 📚 Resources

- **Stripe Dashboard**: https://dashboard.stripe.com
- **Stripe API Docs**: https://stripe.com/docs/api
- **Stripe Java Library**: https://github.com/stripe/stripe-java
- **Stripe Testing Guide**: https://stripe.com/docs/testing

---

## 🆘 Troubleshooting

**"Invalid API key provided"**
- Check your STRIPE_API_KEY environment variable
- Make sure it starts with `sk_test_` for testing

**"Webhook signature verification failed"**
- Verify your STRIPE_WEBHOOK_SECRET is correct
- Use Stripe CLI to test webhooks locally

**"Could not initialize Stripe"**
- Make sure Stripe dependency is in pom.xml
- Run `mvn clean install`

---

**You're all set! Start testing Stripe payments without needing a bank account.** 🎉
