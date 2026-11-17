-- Per-finding state across a pull request's review rounds (#1319). One row per finding, keyed by
-- the key its thread marker carries; each round updates the rows it changes. Rows of a closed or
-- merged pull request are deleted after the retention period, and an installation's rows when the
-- App is uninstalled.

CREATE SEQUENCE finding_state_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE finding_state (
    id bigint NOT NULL,
    installationid bigint NOT NULL,
    repositoryid bigint NOT NULL,
    repository character varying(255) NOT NULL,
    prnumber integer NOT NULL,
    findingkey character varying(16) NOT NULL,
    file character varying(1000),
    line integer NOT NULL,
    risk character varying(16),
    confidence character varying(16),
    title character varying(500),
    anchor text,
    anchorside character varying(8),
    anchormatched boolean,
    blobsha character varying(64),
    status character varying(16) NOT NULL,
    raisedhead character varying(64),
    statushead character varying(64),
    threadid bigint,
    threadhead character varying(64),
    prclosedat timestamp(6) with time zone,
    updatedat timestamp(6) with time zone NOT NULL,
    CONSTRAINT finding_state_pkey PRIMARY KEY (id),
    CONSTRAINT uq_findingstate_scope_pr_key
        UNIQUE (installationid, repositoryid, prnumber, findingkey)
);
CREATE INDEX idx_findingstate_closed ON finding_state (prclosedat);
CREATE INDEX idx_findingstate_installation ON finding_state (installationid);
