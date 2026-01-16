-- Usuń starą tabelę, jeśli istnieje, żeby zacząć na czysto
DROP TABLE IF EXISTS simple_type_benchmark;

-- Uniwersalny typ-przenośnik - będzie obecny także na zwykłej bazie. Wymagany przez rejestr
DROP TYPE IF EXISTS dynamic_dto CASCADE;
CREATE TYPE dynamic_dto AS
(
    type_name    TEXT,
    data_payload JSONB
);

-- Stwórz tabelę, której potrzebujemy do testów
CREATE TABLE simple_type_benchmark
(
    id          SERIAL PRIMARY KEY,
    int_val     INT,
    long_val    BIGINT,
    text_val    TEXT,
    ts_val      TIMESTAMP,
    bool_val    BOOLEAN,
    numeric_val NUMERIC(12, 4) -- Precyzja: 12 cyfr, z czego 4 po przecinku
);

CREATE OR REPLACE FUNCTION generate_benchmark_data(num_rows INT)
    RETURNS VOID AS $$ -- Funkcja nic nie zwraca (VOID), po prostu wykonuje operację
BEGIN
    FOR i IN 1..num_rows LOOP
            INSERT INTO simple_type_benchmark (
                int_val,
                long_val,
                text_val,
                ts_val,
                bool_val,
                numeric_val
            ) VALUES (
                         -- int_val: losowa liczba całkowita od 0 do 999999
                         floor(random() * 1000000)::INT,

                         -- long_val: losowa duża liczba całkowita
                         floor(random() * 10000000000)::BIGINT,

                         -- text_val: unikalny tekst z numerem wiersza i losowym hashem
                         'Testowy wiersz nr ' || i || ' z losowym ciągiem: ' || md5(random()::text),

                         -- ts_val: losowa data i czas z ostatniego roku
                         NOW() - (random() * interval '365 days'),

                         -- bool_val: losowa wartość true/false
                         (random() > 0.5),

                         -- numeric_val: losowa liczba z 4 miejscami po przecinku
                         (random() * 100000)::NUMERIC(12, 4)
                     );
        END LOOP;
END;
$$ LANGUAGE plpgsql;

SELECT generate_benchmark_data(10000);