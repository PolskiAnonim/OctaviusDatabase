CREATE TABLE IF NOT EXISTS json_test (
    id SERIAL PRIMARY KEY,
    data JSONB
);

INSERT INTO json_test (data) VALUES ('{"a": 1, "b": 2}');
INSERT INTO json_test (data) VALUES ('{"b": 2, "c": 3}');
INSERT INTO json_test (data) VALUES ('{"d": 4}');
