-- Backs BookingIdGenerator with a real Postgres sequence instead of a per-pod in-memory
-- counter, so two app instances can never generate the same booking id (see README known
-- gaps / assumptions). CYCLE + MAXVALUE 9999999 matches the existing "BKG" + 7-digit
-- format - the format itself is what limits this to 9,999,999 values, not this sequence.
CREATE SEQUENCE booking_id_seq
    START WITH 1
    MINVALUE 1
    MAXVALUE 9999999
    CYCLE;

-- Continues from wherever the old in-memory counter (seeded from a row count at startup)
-- would have been, so this migration is safe to run against a database that already has
-- bookings in it, not just a fresh one. is_called=false on an empty table makes the very
-- first nextval() return 1 itself, instead of 2 (setval's own value counts as "already
-- used" whenever is_called=true, and the sequence's MINVALUE 1 rejects a bare 0).
WITH existing AS (SELECT COUNT(*) AS n FROM bookings)
SELECT setval('booking_id_seq', GREATEST(n, 1), n > 0) FROM existing;
