CREATE TABLE users (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    email varchar(255) NOT NULL UNIQUE,
    password_hash varchar(255) NOT NULL,
    role varchar(8) NOT NULL DEFAULT 'USER' CHECK (role IN ('USER', 'SELLER', 'ADMIN')),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE TRIGGER users_updated_at
BEFORE UPDATE ON users
FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

CREATE TABLE promo_codes (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    code varchar(20) NOT NULL UNIQUE CHECK (code ~ '^[A-Z0-9_]{4,20}$'),
    discount_type varchar(16) NOT NULL CHECK (discount_type IN ('PERCENTAGE', 'FIXED_AMOUNT')),
    discount_value numeric(12, 2) NOT NULL CHECK (discount_value > 0),
    min_order_amount numeric(12, 2) NOT NULL CHECK (min_order_amount >= 0),
    max_uses integer NOT NULL CHECK (max_uses >= 1),
    current_uses integer NOT NULL DEFAULT 0 CHECK (current_uses >= 0 AND current_uses <= max_uses),
    valid_from timestamptz NOT NULL,
    valid_until timestamptz NOT NULL,
    active boolean NOT NULL DEFAULT true,
    CHECK (discount_type <> 'PERCENTAGE' OR discount_value <= 100),
    CHECK (valid_until >= valid_from)
);

CREATE TABLE orders (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users (id),
    status varchar(16) NOT NULL CHECK (status IN ('CREATED', 'PAYMENT_PENDING', 'PAID', 'SHIPPED', 'COMPLETED', 'CANCELED')),
    promo_code_id uuid REFERENCES promo_codes (id),
    total_amount numeric(12, 2) NOT NULL CHECK (total_amount >= 0),
    discount_amount numeric(12, 2) NOT NULL DEFAULT 0 CHECK (discount_amount >= 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX orders_one_active ON orders (user_id)
WHERE status IN ('CREATED', 'PAYMENT_PENDING');

CREATE TRIGGER orders_updated_at
BEFORE UPDATE ON orders
FOR EACH ROW EXECUTE FUNCTION touch_updated_at();

CREATE TABLE order_items (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    order_id uuid NOT NULL REFERENCES orders (id),
    product_id uuid NOT NULL REFERENCES products (id),
    quantity integer NOT NULL CHECK (quantity BETWEEN 1 AND 999),
    price_at_order numeric(12, 2) NOT NULL CHECK (price_at_order > 0),
    UNIQUE (order_id, product_id)
);

CREATE TABLE user_operations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES users (id),
    operation_type varchar(16) NOT NULL CHECK (operation_type IN ('CREATE_ORDER', 'UPDATE_ORDER')),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX user_operations_last ON user_operations (user_id, operation_type, created_at DESC);
