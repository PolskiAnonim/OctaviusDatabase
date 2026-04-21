-- SQL for JSON and Composite round-trip tests

CREATE TABLE products (
    id SERIAL PRIMARY KEY,
    name TEXT NOT NULL,
    price NUMERIC(10, 2) NOT NULL,
    tags TEXT[],
    release_date DATE,
    created_at TIMESTAMPTZ,
    delivery_time TIME
);

INSERT INTO products (name, price, tags, release_date, created_at, delivery_time) VALUES 
('Laptop', 999.99, ARRAY['electronics', 'work'], '2024-01-01', '2024-01-01 12:00:00+00', '10:00:00'),
('Coffee Mug', 12.50, ARRAY['kitchen', 'home'], '2023-05-15', '2023-05-15 08:30:00+00', '14:30:00');
