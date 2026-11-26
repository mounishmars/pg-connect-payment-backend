CREATE UNIQUE INDEX idx_one_open_stripe_order_per_invoice
  ON payment_orders(rent_invoice_id)
  WHERE gateway = 'STRIPE' AND status IN ('CREATED', 'ACTIVE');
