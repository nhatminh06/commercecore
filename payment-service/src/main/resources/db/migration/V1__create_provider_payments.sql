CREATE TABLE provider_payments (
    provider_request_id UUID PRIMARY KEY,
    amount NUMERIC(19, 2) NOT NULL CHECK (amount >= 0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('AUTHORIZED', 'DECLINED')),
    provider_reference VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT provider_reference_matches_status CHECK (
        (status = 'AUTHORIZED' AND provider_reference IS NOT NULL)
        OR (status = 'DECLINED' AND provider_reference IS NULL)
    )
);
