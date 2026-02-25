-- Test SQL for PostgreSQL stored procedures
-- Reuses types from init-complex-test-db.sql (must be run first)

-- 1. Simple procedure - no OUT params
CREATE OR REPLACE PROCEDURE void_proc(p_text text)
LANGUAGE plpgsql AS $$
BEGIN
    -- intentionally empty, just validates the call works
END;
$$;

-- 2. Procedure with IN + OUT
CREATE OR REPLACE PROCEDURE add_numbers(IN a int4, IN b int4, OUT result int4)
LANGUAGE plpgsql AS $$
BEGIN
    result := a + b;
END;
$$;

-- 3. Procedure with multiple OUT params
CREATE OR REPLACE PROCEDURE split_text(IN input text, OUT first_half text, OUT second_half text, OUT total_len int4)
LANGUAGE plpgsql AS $$
DECLARE
    mid int;
BEGIN
    total_len := length(input);
    mid := total_len / 2;
    first_half := left(input, mid);
    second_half := substring(input from mid + 1);
END;
$$;

-- 4. Procedure with INOUT
CREATE OR REPLACE PROCEDURE increment(INOUT counter int4, IN step int4)
LANGUAGE plpgsql AS $$
BEGIN
    counter := counter + step;
END;
$$;

-- 5. Procedure with array IN param + OUT (index tracking test)
CREATE OR REPLACE PROCEDURE sum_array(IN numbers int4[], OUT total int4)
LANGUAGE plpgsql AS $$
BEGIN
    SELECT COALESCE(sum(n), 0) INTO total FROM unnest(numbers) AS n;
END;
$$;

-- 6. Procedure with composite IN param + OUT (ROW index tracking test)
CREATE OR REPLACE PROCEDURE greet_person(IN person test_person, OUT greeting text)
LANGUAGE plpgsql AS $$
BEGIN
    greeting := 'Hello, ' || person.name || '! Age: ' || person.age;
END;
$$;

-- 7. Procedure with enum IN + OUT
CREATE OR REPLACE PROCEDURE next_status(IN current_status test_status, OUT next test_status)
LANGUAGE plpgsql AS $$
BEGIN
    next := CASE current_status
        WHEN 'pending' THEN 'active'::test_status
        WHEN 'active' THEN 'inactive'::test_status
        ELSE 'pending'::test_status
    END;
END;
$$;

-- 8. Procedure with composite IN + array IN + OUT (complex index tracking)
CREATE OR REPLACE PROCEDURE complex_proc(IN person test_person, IN tags text[], OUT summary text)
LANGUAGE plpgsql AS $$
BEGIN
    summary := person.name || ' [' || array_to_string(tags, ', ') || ']';
END;
$$;
