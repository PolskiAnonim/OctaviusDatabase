CREATE TYPE mapped_address AS (street TEXT, city TEXT);
CREATE TYPE class_mapped_address AS (street TEXT, city TEXT);
CREATE TABLE mapper_test (id SERIAL PRIMARY KEY, addr mapped_address, class_addr class_mapped_address);