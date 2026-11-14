-- Baseline: shared trigger functions used by the tables that later migrations create.
-- No business tables yet; those arrive with the feature that owns them.

-- Keeps updated_at truthful on editable tables regardless of which code path writes the row.
CREATE FUNCTION set_updated_at() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;

-- Attached as BEFORE UPDATE OR DELETE on append-only tables (operational history, stored
-- simulation inputs). History is corrected by adding a correcting row, never by rewriting one.
CREATE FUNCTION reject_modification() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION '% is append-only: % is not allowed', TG_TABLE_NAME, TG_OP
        USING ERRCODE = 'restrict_violation';
END;
$$;
