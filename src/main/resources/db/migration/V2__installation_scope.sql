-- Scope stored rows by GitHub App installation and numeric repository id (#1220).
-- The ids stay nullable: rows written before this version are filled by the startup backfill,
-- and a row it cannot match keeps a null scope and is never served.

ALTER TABLE reviewsession ADD COLUMN installationid bigint;
ALTER TABLE reviewsession ADD COLUMN repositoryid bigint;
CREATE INDEX idx_reviewsession_scope ON reviewsession (installationid, repositoryid, prnumber);

ALTER TABLE pausedpr ADD COLUMN installationid bigint;
ALTER TABLE pausedpr ADD COLUMN repositoryid bigint;
ALTER TABLE pausedpr DROP CONSTRAINT uq_pausedpr_repository_pr;
ALTER TABLE pausedpr ADD CONSTRAINT uq_pausedpr_scope_pr
    UNIQUE (installationid, repositoryid, prnumber);
CREATE INDEX idx_pausedpr_repository ON pausedpr (repository, prnumber);

ALTER TABLE finding_feedback ADD COLUMN installationid bigint;
ALTER TABLE finding_feedback ADD COLUMN repositoryid bigint;
CREATE INDEX idx_findingfeedback_scope ON finding_feedback (installationid, repositoryid);

ALTER TABLE review_learning ADD COLUMN repositoryid bigint;
DROP INDEX idx_reviewlearning_scope;
CREATE INDEX idx_reviewlearning_scope ON review_learning (installationid, repositoryid, active);
