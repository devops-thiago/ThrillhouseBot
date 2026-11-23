-- Count the model calls of a review that failed (#1417). A failed call that is retried no longer
-- marks the session failed or leaves its message on the row; the count records that it happened.
-- The default fills the rows written before this version.

ALTER TABLE reviewsession ADD COLUMN failedmodelcalls integer NOT NULL DEFAULT 0;
