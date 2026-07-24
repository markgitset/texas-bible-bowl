-- Per-site score release (grading backlog G2).
--
-- 2026 ran two sites that graded and awarded independently, but score_releases had one row per
-- season — a single switch that either blocked a finished small site's families or leaked a big
-- site's half-graded tally. Add a site_id to the key so each site releases on its own clock.
--
-- site_id "" is the season-wide release: it's what a site-less season uses, and it's the meaning
-- of any pre-existing (single-switch) row, which the new DEFAULT '' preserves in place. A
-- contestant's scores are visible once their own site — or the season-wide "" stamp — is released.

ALTER TABLE score_releases ADD COLUMN site_id VARCHAR(64) NOT NULL DEFAULT '';

ALTER TABLE score_releases DROP CONSTRAINT score_releases_pkey;
ALTER TABLE score_releases ADD CONSTRAINT score_releases_pkey PRIMARY KEY (season_year, site_id);
