-- Transactional invalidation only: no scores, identities or private details are exposed.
CREATE TABLE score_live_revision (id INTEGER PRIMARY KEY CHECK (id=1), revision UUID NOT NULL);
INSERT INTO score_live_revision VALUES (1,gen_random_uuid());
CREATE FUNCTION invalidate_score_views() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  UPDATE score_live_revision SET revision=gen_random_uuid() WHERE id=1;
  RETURN NULL;
END;
$$;
DO $$
DECLARE table_name TEXT;
BEGIN
  FOREACH table_name IN ARRAY ARRAY['score_event','score_summary','performance_case','performance_appeal',
    'shift_score','d_grade_nomination','grade_snapshot'] LOOP
    EXECUTE format('CREATE TRIGGER score_view_changed AFTER INSERT OR UPDATE OR DELETE ON %I FOR EACH STATEMENT EXECUTE FUNCTION invalidate_score_views()',table_name);
  END LOOP;
END;
$$;
