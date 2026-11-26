# PG Connect backend starter

Spring Boot/PostgreSQL backend for the PG Connect Flutter starter. This is a
development foundation, **not production-ready software**. The Flutter app
connects its core login, discovery, booking, invoice and owner workflows to
these endpoints; other screens and production integrations remain incomplete.

## What is included

- Development-only phone OTP, opaque bearer sessions, profile and logout.
- Optional Firebase phone identity verification with account isolation and
  server-side revocation checks (requires separate Firebase configuration).
- Owner application and administrator approval.
- PG listing search, property/room/bed creation, owner booking approval,
  tenant booking history, rent invoices and owner dashboard totals.
- Test-mode Stripe PaymentIntent creation, native checkout support and
  server-verified payment updates.
- Owner-managed property photos, stored in the local PostgreSQL database for
  development, with a cover photo and ordering.
- Standard amenities, property search filters and user-specific saved PGs.

Owner approval is required before property management. Booking requests are
pending until the owner approves them; approval rejects other pending requests
for the same bed. Approved bookings represent occupancy until completed.

## Local setup

Requires Java 21+, Maven, and PostgreSQL 15+. Create a dedicated database and
configure `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD`. Flyway runs the schema
migrations automatically. Stripe settings are `STRIPE_API_KEY` and
`STRIPE_WEBHOOK_SECRET`; keep keys in a secrets manager or local environment,
never in source code or a mobile app. Prefer a minimally scoped restricted key.

Run locally with `SPRING_PROFILES_ACTIVE=dev mvn spring-boot:run`. **The dev
profile returns OTP codes in API responses and must never be enabled in a
deployed or publicly accessible environment.** It does not send SMS.

## Firebase phone authentication

Use a **fresh separate database** and a separate Firebase project for each
environment. Set `PGCONNECT_FIREBASE_ENABLED=true` and
`PGCONNECT_FIREBASE_PROJECT_ID` to the exact Firebase project ID. Supply
Application Default Credentials securely using the hosting platform's
service identity, with permissions to verify and manage Firebase Auth users.
For local testing use a separately provisioned credential file referenced by
`GOOGLE_APPLICATION_CREDENTIALS`; never commit, paste or embed it in the app.
The backend fails startup if Firebase and the `dev` profile are both enabled
or if the Firebase Auth emulator is configured.

The app sends a Firebase ID token as the bearer token. The backend verifies
its signature, issuer, audience, expiration, phone provider and revocation
status and checks that its Firebase UID and verified phone match the database.
First sign-in calls `POST /api/v1/auth/firebase/register` with `fullName`;
the verified UID is registered as an ordinary user. A phone number belonging to
an existing account is **not** automatically linked; it requires manual review.
Owner/admin access still comes only from the backend database.
`POST /api/v1/auth/logout` revokes Firebase refresh tokens for **all devices**.
Firebase token checks require connectivity to Google; outages fail closed.

This integration is not yet a production security review. Configure HTTPS,
abuse/rate limits, account recovery and phone-number reassignment procedures,
admin access controls, monitoring, backups and secrets management before
deployment. Phone authentication depends on Firebase availability and SMS
pricing/quotas. Firebase phone sign-in sends phone numbers to Google for abuse
prevention; review consent and privacy requirements before launch.

## Swagger UI

When the backend is running, open
http://localhost:8080/swagger-ui/index.html (or replace `8080` with `SERVER_PORT`).
The OpenAPI JSON is at http://localhost:8080/v3/api-docs. In the dev profile,
use `/auth/dev/otp` and `/auth/dev/verify`, then click **Authorize** and enter
the returned `accessToken` without the `Bearer` prefix. Owner and admin routes
also require their corresponding server-side approval. The development OTP
routes appear in the documentation only when the dev profile is active.

Swagger and the API specification are publicly accessible to anyone who can
reach the server; restrict access at the network or deployment layer if needed.

### Try the API

All paths start with `/api/v1`. Requests and responses use JSON. Except for
property search/details, the amenities catalog, dev OTP and the Stripe webhook, requests require
`Authorization: Bearer <accessToken>`. Firebase registration also requires a
verified Firebase ID token.

1. `POST /auth/dev/otp` with `{"phone":"+919876543210"}`; use the returned
   development code in `POST /auth/dev/verify` with
   `{"phone":"+919876543210","code":"<code>","fullName":"Name"}`. Use the
   returned token for the other requests. `GET /me` returns identity, owner
   application status and owner/admin flags. `PUT /me` accepts `fullName`;
   `POST /auth/logout` revokes the current session.
2. `POST /owner-applications` with `{"displayName":"My PG"}`. An existing
   administrator can list `GET /admin/owner-applications` and approve or reject
   via `POST /admin/owner-applications/{id}/approve` or `/reject`. For local
   development only, create the first admin by setting `users.is_admin = true`
   for that user directly in the local database. There is no public admin
   bootstrap API.
3. An approved owner can `POST /owner/properties` with `name`, `description`,
   `address`, `area`, `city`, and `active`; add rooms with
   `POST /owner/properties/{id}/rooms` (`name`, `monthlyRent`) and beds with
   `POST /owner/rooms/{id}/beds` (`label`). List owner properties with
   `GET /owner/properties`, edit with `PUT /owner/properties/{id}`, and inspect
   inventory with `GET /owner/properties/{id}/rooms`. Edit rooms with
   `PUT /owner/rooms/{id}` (`name`, `monthlyRent`, `active`) and beds with
   `PUT /owner/beds/{id}` (`label`, `active`). Inactive inventory is hidden
   from discovery; existing approved occupancies remain recorded.
4. Customers can search `GET /properties?query=Chennai&offset=0` and view
   `GET /properties/{id}`. Request an available bed using
   `POST /customer/bookings` with `{"bedId":"<uuid>"}`. Owners list
   `/owner/bookings` and approve, reject or complete through
   `POST /owner/bookings/{id}/{approve|reject|complete}`. Customers list
   `/customer/bookings` and cancel pending requests at
   `POST /customer/bookings/{id}/cancel`.
5. Owners create invoices with `POST /owner/invoices`, e.g.
   `{"bookingId":"<uuid>","amount":8000.00,"dueDate":"2026-12-01"}`.
   Invoices are listed at `/owner/invoices` and `/customer/invoices`;
   `/owner/dashboard` returns totals.
6. Owners can manage photos at `/owner/properties/{id}/photos`: upload a
   multipart `file`, list, delete, set a cover at `/{photoId}/cover`, or
   reorder with `PUT /order` and `{"photoIds":["<uuid>","<uuid>"]}`.
   The first image is the cover. Public photo lists and image bytes are at
   `/properties/{id}/photos` and `/properties/{id}/photos/{photoId}` for
   active properties only. Uploads are limited to 10 JPEG/PNG images per
   property, 5 MiB and 16 megapixels per image.
7. `GET /amenities` lists the standard catalog. Approved owners can read and
   replace a property's selections via `GET` and `PUT
   /owner/properties/{id}/amenities` with `{"codes":["wifi","parking"]}`.
   Search supports `query`, `maxRent`, `availableOnly`, repeated `amenity`
   parameters and `offset`. Rent and availability must match the same room;
   all selected amenities must be present. Results are returned in pages of
   50, with an offset of at most 10000.
8. Authenticated users can list `GET /customer/saved-properties`, save an
   active property with `POST /customer/saved-properties/{id}` and remove it
   with `DELETE` on the same path. Inactive properties are hidden from the list.

Photo bytes are stored in PostgreSQL, alongside metadata, for local development.
This increases database and backup size; use durable object storage and review
access, backups and moderation before production. Uploaded image metadata is
not stripped, so owners should remove location information before uploading.

The existing payment-order endpoint, `POST /customer/rent/payments`, now uses
the bearer session rather than the old caller-controlled
`X-Authenticated-User` header. It requires an `Idempotency-Key` header and
`invoiceId`, `amount`, and `customerEmail` in the body. The response includes
the Stripe client secret as `paymentSessionId`. Do not log or expose it to
other users.

### Stripe test checkout

Checkout is disabled by default. To enable it locally, configure a Stripe
sandbox test API key, its webhook signing secret, and
`STRIPE_TEST_CHECKOUT_ENABLED=true`, then restart the backend. The backend
rejects live-mode keys when this flag is enabled. Never share keys or put them
in source code; prefer a restricted key with the required permissions.
Forward sandbox events with Stripe CLI:

```bash
stripe listen --events payment_intent.succeeded --forward-to localhost:8081/api/v1/webhooks/stripe
```

Set `STRIPE_WEBHOOK_SECRET` to the signing secret for that forwarding session
and restart the backend after setting it. Signed `payment_intent.succeeded`
events cause the server to retrieve the PaymentIntent from Stripe, validate it,
and atomically update the invoice and payment transaction. The app can also
request reconciliation; a checkout callback alone never marks an invoice paid.
Only one payment may be open for an invoice at a time; it can be resumed or
cancelled after Stripe confirms cancellation.

The Flutter app additionally needs its sandbox **publishable** key and explicit
test flag; see its README. This flow charges the platform Stripe account; it
does not pay owners. Stripe CLI forwarding and real sandbox checkout still need
to be tested with the account's own sandbox configuration.

## Important gaps

- Stripe checkout is test-only. Refund and dispute events, ledger entries,
  recovery/operations and production security are incomplete. Do not enable
  live payments.
- Stripe Connect onboarding, settlement, refunds, ledger and reconciliation
  are not implemented. Existing payment-related database tables are not proof
  that these workflows exist.
- Production authentication, authorization review, rate limiting, production media storage,
  notification delivery, subscriptions, receipts and full inventory
  management are not implemented. Existing placeholder screens need Flutter
  integration and further product requirements.
- Migration V2 creates a unique owner-per-user index; inspect and resolve any
  duplicate owner profiles before applying it to an existing database.
- Migration V3 enforces one open Stripe order per invoice; resolve duplicate
  open orders before migrating an existing database.
- Historical setup and migration guides may describe the previous Cashfree
  starter or outdated Stripe steps; this README describes the current core API.

## Tests

`mvn test` runs the unit tests. PostgreSQL integration tests are opt-in and
must use a disposable database:

```bash
PGCONNECT_INTEGRATION_TESTS=true \
DB_URL=jdbc:postgresql://localhost:5432/pgconnect_test \
DB_USERNAME=your_test_user DB_PASSWORD=your_test_password mvn test
```

The tests exercise development OTP, authorization, owner approval, bookings,
and invoice isolation, signature verification, payment validation and
idempotency. They do not exercise Stripe network calls or a real sandbox webhook.
