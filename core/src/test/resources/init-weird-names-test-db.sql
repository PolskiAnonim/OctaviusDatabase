-- Create a schema with dots and spaces
CREATE SCHEMA "weird schema.with dots";

-- Create an enum with spaces in its name
CREATE TYPE "weird schema.with dots"."weird enum.with space" AS ENUM ('VAL1', 'VAL2');

-- Create a composite type with quotes and dots in its name and field names
CREATE TYPE "weird schema.with dots"."weird composite.with ""quotes""" AS (
    "field.one" TEXT,
    "field two" INT
);

-- Create a table using these weird types
CREATE TABLE "weird schema.with dots"."weird_table" (
    id SERIAL PRIMARY KEY,
    enum_val "weird schema.with dots"."weird enum.with space",
    comp_val "weird schema.with dots"."weird composite.with ""quotes""",
    comp_array "weird schema.with dots"."weird composite.with ""quotes"""[]
);

-- A procedure with weird parameter names and type names
CREATE OR REPLACE PROCEDURE "weird schema.with dots"."weird_proc"(
    INOUT "p.param 1" "weird schema.with dots"."weird enum.with space",
    IN "p param 2" "weird schema.with dots"."weird composite.with ""quotes"""
)
LANGUAGE plpgsql
AS $$
BEGIN
    -- Just return the enum value as is for INOUT
    NULL;
END;
$$;
