ALTER TABLE users ADD COLUMN oauth_provider VARCHAR(20) NULL;
ALTER TABLE users ADD COLUMN oauth_id VARCHAR(255) NULL;
ALTER TABLE users ADD UNIQUE INDEX idx_users_oauth (oauth_provider, oauth_id);
