SELECT id, email, role FROM users ORDER BY created_at;

SELECT id, name, price, stock, status, seller_id FROM products ORDER BY created_at;

SELECT id, user_id, status, total_amount, discount_amount, promo_code_id
FROM orders ORDER BY created_at;

SELECT order_id, product_id, quantity, price_at_order FROM order_items ORDER BY order_id, product_id;

SELECT code, discount_type, discount_value, min_order_amount, current_uses, max_uses
FROM promo_codes ORDER BY code;

SELECT user_id, operation_type, created_at FROM user_operations ORDER BY created_at;
