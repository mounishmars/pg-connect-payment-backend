CREATE TABLE property_photos (
    id UUID PRIMARY KEY,
    property_id UUID NOT NULL REFERENCES properties(id) ON DELETE CASCADE,
    position INTEGER NOT NULL CHECK (position >= 0),
    content_type VARCHAR(20) NOT NULL CHECK (content_type IN ('image/jpeg', 'image/png')),
    content BYTEA NOT NULL,
    UNIQUE (property_id, position) DEFERRABLE INITIALLY DEFERRED
);
