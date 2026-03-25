CREATE TABLE array_test_table (
    id SERIAL PRIMARY KEY,
    int_array INT[],
    index INT
);

INSERT INTO array_test_table (int_array, index) VALUES (ARRAY[10, 20, 30, 40, 50], 3);
