CREATE TABLE amenities (
    code VARCHAR(30) PRIMARY KEY,
    name VARCHAR(60) NOT NULL UNIQUE
);
INSERT INTO amenities(code, name) VALUES
    ('wifi', 'Wi-Fi'), ('meals', 'Meals'), ('laundry', 'Laundry'),
    ('parking', 'Parking'), ('air_conditioning', 'Air conditioning'),
    ('housekeeping', 'Housekeeping'), ('cctv', 'CCTV'),
    ('power_backup', 'Power backup'), ('drinking_water', 'Drinking water');

CREATE TABLE property_amenities (
    property_id UUID NOT NULL REFERENCES properties(id) ON DELETE CASCADE,
    amenity_code VARCHAR(30) NOT NULL REFERENCES amenities(code),
    PRIMARY KEY(property_id, amenity_code)
);

CREATE TABLE saved_properties (
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    property_id UUID NOT NULL REFERENCES properties(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(user_id, property_id)
);
