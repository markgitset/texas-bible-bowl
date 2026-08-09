-- Admin-curated study materials: uploaded documents served back byte-exact (past tests and other
-- hand-made files) and links to externally hosted resources (Kahoot, Quizlet, …). Each is pinned to
-- a study set and one Study & Practice section page (shared-api StudySection slug), in an
-- admin-controlled manual order. The document bytes live right here — like generated_pdfs, Postgres
-- TOASTs a large bytea out of the row, so listings that never select `body` stay cheap.
CREATE TABLE study_materials (
    id VARCHAR(36) NOT NULL,
    study_set VARCHAR(64) NOT NULL,          -- strict StandardStudySet slug ("acts")
    section VARCHAR(32) NOT NULL,            -- StudySection slug ("practice-tests")
    type VARCHAR(12) NOT NULL,               -- DOCUMENT | LINK
    title VARCHAR(160) NOT NULL,
    description VARCHAR(1000) DEFAULT '' NOT NULL,
    url VARCHAR(1000),                       -- LINK only
    file_name VARCHAR(160),                  -- DOCUMENT only: the exact original filename
    content_type VARCHAR(120),               -- DOCUMENT only: the exact original content type
    body BYTEA,                              -- DOCUMENT only: the exact original bytes
    sort_position INTEGER DEFAULT 0 NOT NULL,
    created_at_epoch_ms BIGINT NOT NULL,
    created_by_user_id VARCHAR(36) NOT NULL,
    CONSTRAINT study_materials_pkey PRIMARY KEY (id),
    CONSTRAINT fk_study_materials_created_by_user_id__id FOREIGN KEY (created_by_user_id)
        REFERENCES users(id) ON UPDATE RESTRICT ON DELETE RESTRICT,
    -- The two shapes are disjoint: a LINK has a url and nothing file-ish; a DOCUMENT the reverse.
    CONSTRAINT study_materials_type_shape CHECK (
        (type = 'LINK' AND url IS NOT NULL AND file_name IS NULL AND content_type IS NULL AND body IS NULL)
     OR (type = 'DOCUMENT' AND url IS NULL AND file_name IS NOT NULL AND content_type IS NOT NULL AND body IS NOT NULL)
    )
);

-- Serves the per-section listing (every section-page view) already in display order.
CREATE INDEX study_materials_set_section_idx ON study_materials (study_set, section, sort_position);
