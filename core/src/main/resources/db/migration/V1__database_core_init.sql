-- Jest to plik w którym jest przede wszystkim wymagy przez DAL typ dynamic_dto

-- Typ dynamic_dto: Umożliwia obsługę polimorficznych danych w PostgreSQL.
--
-- CEL: Służy jako uniwersalny kontener do przesyłania dynamicznych struktur danych,
-- których typ jest określany w czasie wykonania zapytania, a nie w schemacie bazy.
--
-- STRUKTURA:
--   - type_name: Klucz (np. "profile_dto"), który framework po stronie aplikacji
--                mapuje na konkretną klasę (np. DynamicProfile).
--   - data_payload: Właściwe dane w formacie JSONB.
--
-- UŻYCIE:
-- Idealny do agregacji różnych typów danych w jednej kolumnie, np. w JOIN LATERAL
-- lub w tablicach przechowujących heterogeniczne obiekty.
-- Dla statycznych, dobrze zdefiniowanych struktur danych, preferowane jest użycie
-- dedykowanego typu kompozytowego (CREATE TYPE my_static_type AS (...)).
CREATE TYPE dynamic_dto AS
(
    type_name    text,
    data_payload jsonb
);

CREATE OR REPLACE FUNCTION dynamic_dto(p_type_name TEXT, p_data JSONB)
    RETURNS dynamic_dto AS
$$
BEGIN
    RETURN ROW (p_type_name, p_data)::dynamic_dto;
END;
$$ LANGUAGE plpgsql IMMUTABLE
PARALLEL SAFE;

-- Ta nazwa jest zawsze prawdziwa. Tworzy DTO z dowolnej wartości, którą da się rzutować na JSONB.
CREATE OR REPLACE FUNCTION to_dynamic_dto(p_type_name TEXT, p_value ANYELEMENT)
    RETURNS dynamic_dto AS
$$
BEGIN
    RETURN ROW (p_type_name, to_jsonb(p_value))::dynamic_dto;
END;
$$ LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE;

-- Przeciążenie dla TEXT
CREATE OR REPLACE FUNCTION to_dynamic_dto(p_type_name TEXT, p_value TEXT)
    RETURNS dynamic_dto AS
$$
BEGIN
    RETURN ROW (p_type_name, to_jsonb(p_value))::dynamic_dto;
END;
$$ LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE;

-- Funkcja do odpakowywania
CREATE OR REPLACE FUNCTION unwrap_dto_payload(p_dto dynamic_dto)
    RETURNS JSONB AS
$$
BEGIN
    RETURN p_dto.data_payload;
END;
$$ LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE;
