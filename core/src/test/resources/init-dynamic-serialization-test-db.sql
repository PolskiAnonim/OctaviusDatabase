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
-----------------

-- 1. Tabela do przechowywania naszych dynamicznych obiektów
CREATE TABLE dynamic_storage (
                                 id SERIAL PRIMARY KEY,
                                 description TEXT,
                                 dynamic_data DYNAMIC_DTO
);

-- Możemy dodać jakiś przykładowy wiersz, chociaż testy będą tworzyć własne.
INSERT INTO dynamic_storage (description, dynamic_data) VALUES
    ('Przykładowy istniejący wpis', dynamic_dto('profile_dto', '{"role": "user", "permissions": ["read"], "lastLogin": "2023-01-01T10:00:00"}'));