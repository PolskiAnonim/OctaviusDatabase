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