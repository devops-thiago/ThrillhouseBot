-- Schema as of v0.8.0, as Hibernate created it before migrations were adopted (#22).
-- A database that already has these tables is baselined at version 1 and skips this script.

CREATE SEQUENCE reviewsession_seq START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE pausedpr_seq START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE finding_feedback_seq START WITH 1 INCREMENT BY 50;
CREATE SEQUENCE review_learning_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE reviewsession (
    id bigint NOT NULL,
    commitsha character varying(255),
    cost double precision NOT NULL,
    criticalfindings integer NOT NULL,
    durationms bigint NOT NULL,
    errormessage character varying(255),
    highfindings integer NOT NULL,
    inputtokens integer NOT NULL,
    lowfindings integer NOT NULL,
    mediumfindings integer NOT NULL,
    model character varying(255),
    outputtokens integer NOT NULL,
    prnumber integer NOT NULL,
    prtitle character varying(255),
    repository character varying(255),
    status character varying(255),
    "timestamp" timestamp(6) with time zone,
    airesponsejson text,
    public_id character varying(36),
    pricingmissing boolean DEFAULT false NOT NULL,
    CONSTRAINT reviewsession_pkey PRIMARY KEY (id),
    CONSTRAINT uk91r5wwqc2j9xxyhkxfkhb3lb2 UNIQUE (public_id)
);
CREATE INDEX idx_reviewsession_repository ON reviewsession (repository);
CREATE INDEX idx_reviewsession_status ON reviewsession (status);
CREATE INDEX idx_reviewsession_timestamp ON reviewsession ("timestamp");

CREATE TABLE pausedpr (
    id bigint NOT NULL,
    prnumber integer NOT NULL,
    repository character varying(255) NOT NULL,
    CONSTRAINT pausedpr_pkey PRIMARY KEY (id),
    CONSTRAINT uq_pausedpr_repository_pr UNIQUE (repository, prnumber)
);

CREATE TABLE finding_feedback (
    id bigint NOT NULL,
    createdat timestamp(6) with time zone NOT NULL,
    findingindex integer,
    githubcommentid bigint NOT NULL,
    githubreactionid bigint,
    prnumber integer NOT NULL,
    reactorlogin character varying(255) NOT NULL,
    repository character varying(255) NOT NULL,
    signal character varying(32) NOT NULL,
    source character varying(32) NOT NULL,
    CONSTRAINT finding_feedback_pkey PRIMARY KEY (id),
    CONSTRAINT uq_findingfeedback_reaction_id UNIQUE (githubreactionid),
    CONSTRAINT uq_findingfeedback_comment_reactor_signal_source
        UNIQUE (githubcommentid, reactorlogin, signal, source)
);
CREATE INDEX idx_findingfeedback_comment ON finding_feedback (githubcommentid);
CREATE INDEX idx_findingfeedback_created ON finding_feedback (createdat);
CREATE INDEX idx_findingfeedback_repository ON finding_feedback (repository);

CREATE TABLE review_learning (
    id bigint NOT NULL,
    active boolean NOT NULL,
    author character varying(255) NOT NULL,
    createdat timestamp(6) with time zone NOT NULL,
    dedupkey character varying(64) NOT NULL,
    findingrisk character varying(16),
    findingtitle character varying(300),
    installationid bigint NOT NULL,
    kind character varying(32) NOT NULL,
    path character varying(500),
    repository character varying(255) NOT NULL,
    retractedat timestamp(6) with time zone,
    retractedby character varying(255),
    sourceprnumber integer NOT NULL,
    sourceurl character varying(500) NOT NULL,
    text character varying(1000) NOT NULL,
    CONSTRAINT review_learning_pkey PRIMARY KEY (id),
    CONSTRAINT uq_reviewlearning_dedup_key UNIQUE (dedupkey)
);
CREATE INDEX idx_reviewlearning_scope ON review_learning (installationid, repository, active);
