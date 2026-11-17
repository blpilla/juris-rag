CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE documents (
    document_id UUID PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    title VARCHAR(200) NOT NULL,
    sha256 CHAR(64) NOT NULL,
    pdf_bytes BYTEA NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING', 'READY', 'FAILED')),
    failure_code VARCHAR(80),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT documents_tenant_sha256_uk UNIQUE (tenant_id, sha256),
    CONSTRAINT documents_pdf_size_ck CHECK (octet_length(pdf_bytes) <= 10485760)
);

CREATE INDEX documents_tenant_status_idx ON documents (tenant_id, status);

CREATE TABLE document_chunks (
    chunk_id CHAR(64) PRIMARY KEY,
    tenant_id VARCHAR(128) NOT NULL,
    document_id UUID NOT NULL REFERENCES documents(document_id) ON DELETE CASCADE,
    page_number INTEGER NOT NULL CHECK (page_number > 0),
    position INTEGER NOT NULL CHECK (position >= 0),
    section VARCHAR(240),
    content TEXT NOT NULL,
    embedding VECTOR(768) NOT NULL,
    embedding_model VARCHAR(160) NOT NULL,
    search_vector TSVECTOR GENERATED ALWAYS AS (to_tsvector('portuguese'::regconfig, content)) STORED,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT document_chunks_document_page_position_uk UNIQUE (document_id, page_number, position)
);

CREATE INDEX document_chunks_tenant_document_idx ON document_chunks (tenant_id, document_id);
CREATE INDEX document_chunks_search_vector_idx ON document_chunks USING GIN (search_vector);
