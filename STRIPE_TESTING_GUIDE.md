# Stripe Integration Testing Guide

## ✅ Application Status

The PG Connect Payment Starter application is now fully integrated with Stripe and ready for testing.

**Current Status:**
- ✅ Application running on `http://localhost:8080`
- ✅ Database: PostgreSQL (pgconnect database)
- ✅ Stripe API: Connected with test credentials
- ✅ Payment endpoint: Fully functional

---

## Configuration

### Environment Variables
Stored in `.env.local` (not committed to version control):
```
STRIPE_API_KEY=sk_test_your_key_here
STRIPE_WEBHOOK_SECRET=whsec_your_secret_here
STRIPE_SUCCESS_URL=http://localhost:8080/api/v1/payment/success
STRIPE_CANCEL_URL=http://localhost:8080/api/v1/payment/cancel
```

### Application Configuration
Defined in `src/main/resources/application.yml`:
```yaml
stripe:
  api-key: ${STRIPE_API_KEY:}
  webhook-secret: ${STRIPE_WEBHOOK_SECRET:}
  success-url: ${STRIPE_SUCCESS_URL:http://localhost:8080/api/v1/payment/success}
  cancel-url: ${STRIPE_CANCEL_URL:http://localhost:8080/api/v1/payment/cancel}
```

The `.env.local` file is loaded at application startup via `EnvConfig.java`.

---

## API Endpoints

### Create Payment Order
**Endpoint:** `POST /api/v1/customer/rent/payments`

**Headers:**
- `X-Authenticated-User`: UUID of the tenant user
- `Idempotency-Key`: Unique key to prevent duplicate payments
- `Content-Type: application/json`

**Request Body:**
```json
{
  "invoiceId": "770e8400-e29b-41d4-a716-446655440001",
  "amount": 10000.00,
  "customerEmail": "tenant@example.com"
}
```

**Response:**
```json
{
  "paymentOrderId": "724dfb20-2a5e-4fa0-a122-0408d241db65",
  "orderReference": "PGCR-724DFB20-2A5",
  "amount": 10000.00,
  "status": "ACTIVE",
  "paymentSessionId": "pi_3UfqlfPXJroMHRzL3nypgbe5_secret_Rm0vYMQyQFbZTOGaNrW0dxzw7"
}
```

### Health Check
**Endpoint:** `GET /actuator/health`

**Response:**
```json
{
  "status": "UP"
}
```

---

## Testing with curl

### Example 1: Create a Payment Order
```bash
curl -X POST http://localhost:8080/api/v1/customer/rent/payments \
  -H "X-Authenticated-User: 550e8400-e29b-41d4-a716-446655440002" \
  -H "Idempotency-Key: test-key-$(date +%s)" \
  -H "Content-Type: application/json" \
  -d '{
    "invoiceId": "770e8400-e29b-41d4-a716-446655440001",
    "amount": 5000.00,
    "customerEmail": "tenant@example.com"
  }' | jq .
```

### Example 2: Reuse Idempotency Key (Returns Same Payment)
```bash
curl -X POST http://localhost:8080/api/v1/customer/rent/payments \
  -H "X-Authenticated-User: 550e8400-e29b-41d4-a716-446655440002" \
  -H "Idempotency-Key: same-key-001" \
  -H "Content-Type: application/json" \
  -d '{
    "invoiceId": "770e8400-e29b-41d4-a716-446655440001",
    "amount": 5000.00,
    "customerEmail": "tenant@example.com"
  }' | jq .
```

---

## Stripe Test Cards

For testing payment intents in Stripe dashboard or Stripe CLI:

| Card Number | Scenario | CVC | Expiry |
|---|---|---|---|
| 4242 4242 4242 4242 | Successful payment | Any 3 digits | Any future date |
| 4000 0000 0000 0002 | Card declined | Any 3 digits | Any future date |
| 4000 0025 0000 3155 | 3D Secure required | Any 3 digits | Any future date |

---

## Webhook Testing with Stripe CLI

### 1. Install Stripe CLI
```bash
brew install stripe/stripe-cli/stripe
```

### 2. Login
```bash
stripe login
```

### 3. Forward webhook events to your local app
```bash
stripe listen --forward-to localhost:8080/api/v1/webhooks/stripe --events payment_intent.succeeded,payment_intent.payment_failed,charge.refunded
```

This will display your webhook signing secret. Store it in `.env.local` as `STRIPE_WEBHOOK_SECRET`.

### 4. Trigger test events
In another terminal, while the CLI is forwarding:
```bash
stripe trigger payment_intent.succeeded
stripe trigger payment_intent.payment_failed
stripe trigger charge.refunded
```

---

## Database Information

### Key Tables
- **rent_invoices**: Rent payment invoices
- **payment_orders**: Payment orders created for Stripe
- **payment_transactions**: Payment transaction records
- **payment_webhook_events**: Webhook events received from Stripe

### Sample Queries

**Check all payment orders:**
```sql
SELECT id, order_reference, amount, gateway_order_id, payment_session_id, status, created_at 
FROM payment_orders 
ORDER BY created_at DESC;
```

**Check webhook events:**
```sql
SELECT event_id, event_type, payload, created_at 
FROM payment_webhook_events 
ORDER BY created_at DESC 
LIMIT 10;
```

**Check invoice status:**
```sql
SELECT id, invoice_number, total_amount, amount_paid, status 
FROM rent_invoices;
```

---

## Implementation Details

### Core Files

**StripeClient.java** (`src/main/java/com/pgconnect/payment/gateway/StripeClient.java`)
- Creates Stripe PaymentIntents
- Handles currency conversion (INR, amount in cents)
- Uses `statement_descriptor_suffix` instead of `statement_descriptor` for card payments

**StripeWebhookVerifier.java** (`src/main/java/com/pgconnect/payment/gateway/StripeWebhookVerifier.java`)
- Verifies webhook signatures using Stripe's secret
- Prevents replay attacks

**StripeWebhookController.java** (`src/main/java/com/pgconnect/payment/gateway/StripeWebhookController.java`)
- Handles webhook events:
  - `payment_intent.succeeded`: Updates invoice status to PAID
  - `payment_intent.payment_failed`: Marks payment as FAILED
  - `charge.refunded`: Processes refunds

**RentPaymentService.java** (`src/main/java/com/pgconnect/payment/service/RentPaymentService.java`)
- Business logic for payment order creation
- Validates invoice ownership
- Checks invoice balance
- Creates PaymentIntent via Stripe

**RentPaymentController.java** (`src/main/java/com/pgconnect/payment/api/RentPaymentController.java`)
- REST endpoint for payment creation
- Validates request data
- Returns PaymentIntent details to frontend

**EnvConfig.java** (`src/main/java/com/pgconnect/payment/config/EnvConfig.java`)
- Loads `.env.local` file at startup
- Uses dotenv-java library

---

## Running the Application

### Start the Application
```bash
cd /Users/mkuma153/Library/CloudStorage/OneDrive-azureford/Documents/pg/pg-connect-payment-starter
mvn spring-boot:run
```

### Stop the Application
```bash
# Find the process
lsof -i :8080

# Kill the process (replace XXXX with actual PID)
kill XXXX
```

### View Logs
```bash
tail -f /tmp/app.log
```

---

## Troubleshooting

### "Port 8080 already in use"
```bash
lsof -i :8080
kill <PID>
```

### "Stripe API key not configured"
Ensure `.env.local` exists in the project root with valid credentials.

### "Database connection refused"
Ensure PostgreSQL is running:
```bash
# Start PostgreSQL (macOS with Homebrew)
brew services start postgresql@15
```

### "Invoice not found"
Check that the UUID in `invoiceId` exists in the database:
```sql
SELECT id FROM rent_invoices;
```

### "Invoice does not belong to authenticated tenant"
Ensure the `X-Authenticated-User` matches the invoice's `tenant_user_id`:
```sql
SELECT tenant_user_id FROM rent_invoices WHERE id = '<your-invoice-id>';
```

---

## Next Steps

1. **Frontend Integration**: Use the `paymentSessionId` to initialize Stripe.js payment form
2. **Webhook Testing**: Set up local webhook forwarding with Stripe CLI
3. **Production Deployment**: Configure production Stripe credentials when ready
4. **Payment Confirmation**: Implement payment success/cancel pages

---

## Support

For Stripe integration questions, refer to:
- [Stripe Payment Intents API Docs](https://stripe.com/docs/payments/payment-intents)
- [Stripe Webhooks Docs](https://stripe.com/docs/webhooks)
- [Stripe CLI Documentation](https://stripe.com/docs/stripe-cli)
