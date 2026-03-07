-- init-composite-vs-dynamic-benchmark-db.sql

-- =====================================================================
-- ==  Schemat dla Benchmarku: @PgComposite vs @DynamicallyMappable  ==
-- =====================================================================

-- Krok 1: Zdefiniuj typy dla podejścia @PgComposite
DROP TYPE IF EXISTS statistics CASCADE;
CREATE TYPE statistics AS
(
    strength     INT,
    agility      INT,
    intelligence INT
);

DROP TYPE IF EXISTS game_character CASCADE;
CREATE TYPE game_character AS
(
    id    INT,
    name  TEXT,
    stats statistics
);

-- Krok 2: Zdefiniuj typ i funkcję dla podejścia @DynamicallyMappable
DROP TYPE IF EXISTS dynamic_dto CASCADE;
CREATE TYPE dynamic_dto AS
(
    type_name    TEXT,
    data_payload JSONB
);

CREATE OR REPLACE FUNCTION dynamic_dto(p_type_name TEXT, p_data JSONB)
    RETURNS dynamic_dto AS
$$
BEGIN
    RETURN ROW (p_type_name, p_data)::dynamic_dto;
END;
$$ LANGUAGE plpgsql;


-- Krok 3: Stwórz dwie oddzielne tabele do przechowywania danych
DROP TABLE IF EXISTS performance_pg_composite;
CREATE TABLE performance_pg_composite
(
    -- Przechowuje dane jako natywny, silnie typowany typ kompozytowy
    data game_character
);

DROP TABLE IF EXISTS performance_dynamic_dto;
CREATE TABLE performance_dynamic_dto
(
    -- Przechowuje dane jako nasz polimorficzny wrapper z ładunkiem JSONB
    data dynamic_dto
);