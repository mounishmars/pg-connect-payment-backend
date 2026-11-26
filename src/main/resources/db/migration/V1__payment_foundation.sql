CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- These two parent tables are minimal integration contracts for this standalone starter.
-- In the main PG Connect schema, retain the existing tables and remove these definitions.
CREATE TABLE IF NOT EXISTS users (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  full_name VARCHAR(150) NOT NULL,
  phone_number VARCHAR(20) UNIQUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS owner_profiles (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL REFERENCES users(id),
  display_name VARCHAR(150) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE rent_invoices (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  invoice_number VARCHAR(50) NOT NULL UNIQUE,
  tenant_user_id UUID NOT NULL REFERENCES users(id),
  owner_id UUID NOT NULL REFERENCES owner_profiles(id),
  property_id UUID,
  currency VARCHAR(3) NOT NULL DEFAULT 'INR',
  total_amount NUMERIC(14,2) NOT NULL CHECK (total_amount > 0),
  amount_paid NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (amount_paid >= 0),
  due_date DATE NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'UNPAID' CHECK (status IN ('UNPAID','PARTIALLY_PAID','PAID','VOID')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK (amount_paid <= total_amount)
);
CREATE INDEX idx_rent_invoice_tenant_status ON rent_invoices(tenant_user_id,status,due_date);
CREATE INDEX idx_rent_invoice_owner_due ON rent_invoices(owner_id,due_date);

CREATE TABLE gateway_vendor_accounts (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_id UUID NOT NULL REFERENCES owner_profiles(id),
  gateway VARCHAR(30) NOT NULL DEFAULT 'CASHFREE',
  gateway_vendor_id VARCHAR(100) NOT NULL,
  onboarding_status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
  kyc_status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(owner_id,gateway), UNIQUE(gateway,gateway_vendor_id)
);

CREATE TABLE payment_orders (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  order_reference VARCHAR(50) NOT NULL UNIQUE,
  rent_invoice_id UUID NOT NULL REFERENCES rent_invoices(id),
  payer_user_id UUID NOT NULL REFERENCES users(id),
  owner_id UUID NOT NULL REFERENCES owner_profiles(id),
  gateway VARCHAR(30) NOT NULL DEFAULT 'CASHFREE',
  gateway_order_id VARCHAR(100),
  payment_session_id TEXT,
  amount NUMERIC(14,2) NOT NULL CHECK(amount > 0),
  currency VARCHAR(3) NOT NULL DEFAULT 'INR',
  status VARCHAR(24) NOT NULL DEFAULT 'CREATED' CHECK(status IN ('CREATED','ACTIVE','PAID','FAILED','EXPIRED','CANCELLED')),
  idempotency_key VARCHAR(100) NOT NULL UNIQUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(gateway,gateway_order_id)
);
CREATE INDEX idx_payment_orders_invoice ON payment_orders(rent_invoice_id,status);

CREATE TABLE payment_transactions (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  payment_order_id UUID NOT NULL REFERENCES payment_orders(id),
  gateway_payment_id VARCHAR(100) NOT NULL UNIQUE,
  payment_method VARCHAR(30),
  amount NUMERIC(14,2) NOT NULL CHECK(amount > 0),
  currency VARCHAR(3) NOT NULL DEFAULT 'INR',
  status VARCHAR(20) NOT NULL CHECK(status IN ('SUCCESS','FAILED','PENDING')),
  gateway_fee NUMERIC(14,2) NOT NULL DEFAULT 0,
  gateway_tax NUMERIC(14,2) NOT NULL DEFAULT 0,
  paid_at TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_payment_tx_order ON payment_transactions(payment_order_id);

CREATE TABLE payment_allocations (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  payment_transaction_id UUID NOT NULL REFERENCES payment_transactions(id),
  beneficiary_type VARCHAR(20) NOT NULL CHECK(beneficiary_type IN ('PG_OWNER','PLATFORM')),
  beneficiary_id UUID,
  gross_amount NUMERIC(14,2) NOT NULL CHECK(gross_amount >= 0),
  gateway_fee NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK(gateway_fee >= 0),
  net_amount NUMERIC(14,2) NOT NULL CHECK(net_amount >= 0),
  allocation_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE owner_settlements (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_id UUID NOT NULL REFERENCES owner_profiles(id),
  gateway VARCHAR(30) NOT NULL DEFAULT 'CASHFREE',
  gateway_settlement_id VARCHAR(120),
  settlement_amount NUMERIC(14,2) NOT NULL,
  settlement_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
  settlement_date TIMESTAMPTZ,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(gateway,gateway_settlement_id)
);
CREATE TABLE settlement_items (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  settlement_id UUID NOT NULL REFERENCES owner_settlements(id),
  payment_transaction_id UUID NOT NULL REFERENCES payment_transactions(id),
  gross_amount NUMERIC(14,2) NOT NULL,
  gateway_fee NUMERIC(14,2) NOT NULL DEFAULT 0,
  net_amount NUMERIC(14,2) NOT NULL,
  UNIQUE(settlement_id,payment_transaction_id)
);

CREATE TABLE refunds (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  payment_transaction_id UUID NOT NULL REFERENCES payment_transactions(id),
  refund_reference VARCHAR(50) NOT NULL UNIQUE,
  gateway_refund_id VARCHAR(100) UNIQUE,
  refund_amount NUMERIC(14,2) NOT NULL CHECK(refund_amount > 0),
  refund_reason TEXT,
  refund_status VARCHAR(20) NOT NULL DEFAULT 'INITIATED',
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  completed_at TIMESTAMPTZ
);

CREATE TABLE payment_webhook_events (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  gateway VARCHAR(30) NOT NULL,
  gateway_event_id VARCHAR(200) NOT NULL,
  event_type VARCHAR(100) NOT NULL,
  payload JSONB NOT NULL,
  signature_verified BOOLEAN NOT NULL,
  processing_status VARCHAR(20) NOT NULL DEFAULT 'RECEIVED',
  retry_count INTEGER NOT NULL DEFAULT 0,
  received_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  processed_at TIMESTAMPTZ,
  error_message TEXT,
  UNIQUE(gateway,gateway_event_id)
);
CREATE INDEX idx_webhook_status_received ON payment_webhook_events(processing_status,received_at);
