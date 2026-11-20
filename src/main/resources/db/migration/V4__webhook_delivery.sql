-- Webhook delivery ids seen within the dedup window (#1374), so a redelivery after a restart, or
-- one that lands on another instance, is still dropped. One row per X-GitHub-Delivery id; the
-- first instance to insert it handles the delivery. Rows are purged once they expire.

CREATE TABLE webhook_delivery (
    deliveryid character varying(255) NOT NULL,
    seenat timestamp(6) with time zone NOT NULL,
    expiresat timestamp(6) with time zone NOT NULL,
    CONSTRAINT webhook_delivery_pkey PRIMARY KEY (deliveryid)
);
CREATE INDEX idx_webhookdelivery_expires ON webhook_delivery (expiresat);
