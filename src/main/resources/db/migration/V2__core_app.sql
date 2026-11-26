ALTER TABLE users ADD COLUMN is_admin BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE auth_sessions (
  token_hash VARCHAR(64) PRIMARY KEY,
  user_id UUID NOT NULL REFERENCES users(id),
  expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_auth_sessions_user ON auth_sessions(user_id);

CREATE TABLE dev_otp_challenges (
  phone_number VARCHAR(20) PRIMARY KEY,
  code_hash VARCHAR(100) NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0,
  requested_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE owner_applications (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id UUID NOT NULL UNIQUE REFERENCES users(id),
  display_name VARCHAR(150) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','REJECTED')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX idx_owner_profiles_user ON owner_profiles(user_id);

CREATE TABLE properties (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  owner_id UUID NOT NULL REFERENCES owner_profiles(id),
  name VARCHAR(150) NOT NULL,
  description TEXT NOT NULL DEFAULT '',
  address TEXT NOT NULL,
  area VARCHAR(150) NOT NULL,
  city VARCHAR(150) NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_properties_location ON properties(city, area);
CREATE TABLE rooms (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  property_id UUID NOT NULL REFERENCES properties(id),
  name VARCHAR(100) NOT NULL,
  monthly_rent NUMERIC(14,2) NOT NULL CHECK (monthly_rent > 0),
  active BOOLEAN NOT NULL DEFAULT TRUE,
  UNIQUE(property_id, name)
);
CREATE TABLE beds (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  room_id UUID NOT NULL REFERENCES rooms(id),
  label VARCHAR(100) NOT NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  UNIQUE(room_id, label)
);
CREATE TABLE bookings (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_user_id UUID NOT NULL REFERENCES users(id),
  bed_id UUID NOT NULL REFERENCES beds(id),
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','REJECTED','CANCELLED','COMPLETED')),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX idx_bookings_occupied_bed ON bookings(bed_id) WHERE status = 'APPROVED';
CREATE UNIQUE INDEX idx_bookings_pending_request ON bookings(tenant_user_id, bed_id) WHERE status = 'PENDING';
CREATE INDEX idx_bookings_tenant ON bookings(tenant_user_id, created_at);
