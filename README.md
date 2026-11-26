# PG Connect – Payment Starter (Spring Boot)

Starter scaffold for the first manual rent-payment release using Cashfree PG + Easy Split.

## Important integration boundaries

This is a **starter scaffold, not production-ready payment software**. It intentionally does not pretend that a verified webhook alone is enough to post a rent payment. Before production, implement the durable webhook worker that fetches the authoritative Cashfree payment/order state, validates amount/currency/order/vendor, and atomically writes payment transaction + invoice allocation + balanced ledger entries.

Cashfree's current documentation describes Easy Split's Split After Payment endpoint as `POST /pg/easy-split/orders/{order_id}/split`, and says to wait 2 minutes after successful payment confirmation before invoking it. The settlement split worker is therefore a separate asynchronous job, not part of the synchronous webhook request. The actual split amount/vendor-fee handling must be confirmed with Cashfree for the PG Connect account. See https://www.cashfree.com/docs/api-reference/payments/latest/easy-split/split-after-payment.

Cashfree webhook signature verification uses `x-webhook-signature`, `x-webhook-timestamp`, and HMAC-SHA256 over `timestamp + rawBody`, Base64 encoded. Keep the raw request body intact. See https://github.com/cashfree/cashfree-pg/blob/main/docs/Webhook.md.

## Prerequisites

- Java 21
- Maven 3.9+
- PostgreSQL 15+
- Cashfree sandbox credentials and Easy Split enabled for the merchant account

## Run

```bash
createdb pgconnect
export DB_URL='jdbc:postgresql://localhost:5432/pgconnect'
export DB_USERNAME='your_db_user'
export DB_PASSWORD='your_db_password'
export CASHFREE_BASE_URL='https://sandbox.cashfree.com/pg'
export CASHFREE_CLIENT_ID='your_sandbox_app_id'
export CASHFREE_CLIENT_SECRET='your_sandbox_secret'
export CASHFREE_WEBHOOK_SECRET='your_webhook_secret'
mvn spring-boot:run
```

Flyway runs `V1__payment_foundation.sql` automatically. The migration includes minimal `users` and `owner_profiles` parent tables so the starter runs independently. When merging into the main PG Connect database, remove those two parent-table definitions and point the foreign keys at the existing identity/owner tables.

## API

`POST /api/v1/customer/rent/payments`

Headers:

- `X-Authenticated-User: <tenant UUID>` **DEMO ONLY**; replace with the authenticated Spring Security principal before any deployment.
- `Idempotency-Key: <unique client-generated key>`

Body:

```json
{
  "invoiceId": "<invoice UUID>",
  "amount": 2500.00,
  "customerPhone": "9876543210"
}
```

The amount is validated against the locked invoice balance. Partial payments are allowed. Never accept identity from a caller-controlled header in production.

Webhook: `POST /api/v1/webhooks/cashfree`.

## Deliberately not implemented yet

- Production Spring Security/JWT principal mapping and owner/tenant authorization.
- Cashfree Easy Split vendor onboarding API and vendor KYC status sync.
- Durable async webhook worker + Cashfree Get Payments verification.
- Invoice allocation across multiple successful partial payment transactions.
- Delayed Easy Split split-after-payment job (minimum 2-minute wait per current docs).
- Gateway fee attribution to owner and actual split/net settlement calculations.
- Refund API and vendor balance adjustment workflow.
- Double-entry ledger and daily settlement reconciliation.
- Subscription billing (separate PG Connect merchant flow).
- UPI AutoPay (phase 2).
- Integration tests against PostgreSQL/Testcontainers and Cashfree sandbox.

## Tests

```bash
mvn test
```

The included test is a small contract smoke test, not end-to-end payment coverage. Do not enable live payments until the missing production controls above are implemented and reviewed.
