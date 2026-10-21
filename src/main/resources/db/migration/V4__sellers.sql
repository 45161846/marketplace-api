ALTER TABLE products ADD COLUMN seller_id uuid NOT NULL REFERENCES users(id);

CREATE INDEX products_seller_idx ON products (seller_id);
