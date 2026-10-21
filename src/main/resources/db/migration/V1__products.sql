CREATE TABLE products (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    name varchar(255) NOT NULL CHECK (char_length(name) >= 1),
    description varchar(4000),
    price numeric(12, 2) NOT NULL CHECK (price > 0),
    stock integer NOT NULL CHECK (stock >= 0),
    category varchar(100) NOT NULL CHECK (char_length(category) >= 1),
    status varchar(16) NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE', 'ARCHIVED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX products_status_idx ON products (status);

CREATE FUNCTION touch_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER products_updated_at
BEFORE UPDATE ON products
FOR EACH ROW EXECUTE FUNCTION touch_updated_at();
