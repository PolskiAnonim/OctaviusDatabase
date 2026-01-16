-- Usuwamy wszystko, żeby zapewnić czysty start
DROP TABLE IF EXISTS logs CASCADE;
DROP TABLE IF EXISTS profiles CASCADE;
DROP TABLE IF EXISTS users CASCADE;

-- Uniwersalny typ-przenośnik - będzie obecny także na zwykłej bazie. Wymagany przez rejestr
DROP TYPE IF EXISTS dynamic_dto CASCADE;
CREATE TYPE dynamic_dto AS
(
    type_name    TEXT,
    data_payload JSONB
);

-- Tabela użytkowników z ograniczeniem unikalności na nazwę
CREATE TABLE users
(
    id         SERIAL PRIMARY KEY,
    name       TEXT NOT NULL UNIQUE,
    created_at TIMESTAMPTZ DEFAULT NOW()
);

-- Tabela profili z kluczem obcym do użytkowników
CREATE TABLE profiles
(
    id         SERIAL PRIMARY KEY,
    user_id    INT NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    bio        TEXT,
    updated_at TIMESTAMPTZ DEFAULT NOW()
);

-- Tabela logów
CREATE TABLE logs
(
    id         SERIAL PRIMARY KEY,
    message    TEXT,
    created_at TIMESTAMPTZ DEFAULT NOW()
);