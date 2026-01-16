-- ============================================================================
-- ==     THE GRAND UNIFICATION TEST - POLYMORPHIC ARRAY (Structure Only)    ==
-- ============================================================================
-- Ten skrypt przygotowuje STRUKTURĘ dla ostatecznego testu frameworka.
-- Dane będą wstawiane dynamicznie przez kod testowy, aby zweryfikować
-- pełen cykl ZAPIS -> ODCZYT dla polimorficznych tablic.

-- Krok 1: Upewniamy się, że typ dynamic_dto i funkcja istnieją
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

-- Krok 2: Tworzymy tabelę przechowującą TABLICĘ dynamicznych DTO.
-- Tabela będzie pusta po inicjalizacji.
CREATE TABLE polymorphic_storage
(
    id          SERIAL PRIMARY KEY,
    description TEXT,
    payload     dynamic_dto[] -- TABLICA OBIEKTÓW POLIMORFICZNYCH
);