-- Krok 1: Zdefiniuj kompozytowy typ `dynamic_dto`, jeśli jeszcze nie istnieje.
-- Będzie on służył jako opakowanie dla naszych dynamicznie mapowanych obiektów.
DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'dynamic_dto') THEN
            CREATE TYPE dynamic_dto AS
            (
                type_name    TEXT, -- Nazwa typu (np. 'feature_flag')
                data_payload JSONB -- Zserializowany obiekt (np. '"dark_theme"')
            );
        END IF;
    END
$$;


-- Krok 2: Stwórz tabelę do przechowywania danych testowych.
-- Kolumna `flags` jest typu `dynamic_dto[]`, czyli tablicą naszych dynamicznych obiektów.
CREATE TABLE soft_enum_storage
(
    id          SERIAL PRIMARY KEY,
    description TEXT,
    flags       dynamic_dto[]
);