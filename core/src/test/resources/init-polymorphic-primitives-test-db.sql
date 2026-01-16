DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'dynamic_dto') THEN
            CREATE TYPE dynamic_dto AS
            (
                type_name    TEXT, -- Klucz identyfikujący typ
                data_payload JSONB -- Zserializowana wartość (może być skalarem!)
            );
        END IF;
    END
$$;


-- Krok 2: Tworzymy tabelę do przechowywania danych testowych.
-- Kolumna `payload` jest typu `dynamic_dto[]`, czyli tablicą
-- naszych polimorficznych obiektów.
CREATE TABLE primitive_payload_storage
(
    id          SERIAL PRIMARY KEY,
    description TEXT,
    payload     dynamic_dto[]
);