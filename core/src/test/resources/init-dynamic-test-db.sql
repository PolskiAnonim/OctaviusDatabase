-- =========== DYNAMIC DTO TEST SETUP ===========

-- 1. Uniwersalny typ-przenośnik - będzie obecny także na zwykłej bazie
CREATE TYPE dynamic_dto AS (
   type_name   TEXT,
   data_payload JSONB
                           );

-- 2. Funkcję pomocnicza do łatwego tworzenia typu - obecna także na zwykłej bazie
CREATE OR REPLACE FUNCTION dynamic_dto(p_type_name TEXT, p_data JSONB)
    RETURNS dynamic_dto AS $$
BEGIN
    RETURN ROW(p_type_name, p_data)::dynamic_dto;
END;
$$ LANGUAGE plpgsql;


-- 3. Tabele do testowania dynamicznych zagnieżdżeń
CREATE TABLE dynamic_users (
   user_id SERIAL PRIMARY KEY,
   username TEXT NOT NULL
);

CREATE TABLE dynamic_profiles (
   profile_id SERIAL PRIMARY KEY,
   user_id INT REFERENCES dynamic_users(user_id),
   role TEXT NOT NULL,
   permissions TEXT[]
);

-- Dane testowe
INSERT INTO dynamic_users (username) VALUES ('dynamic_user_1');
INSERT INTO dynamic_profiles (user_id, role, permissions) VALUES (1, 'administrator', ARRAY['read', 'write', 'delete']);