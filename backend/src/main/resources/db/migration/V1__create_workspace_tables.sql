CREATE TABLE workspaces (
    id UUID PRIMARY KEY,
    person_id UUID NOT NULL,
    name VARCHAR(255) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_workspaces_person_id_name UNIQUE (person_id, name)
);

CREATE SEQUENCE provisioned_resource_id_seq START WITH 1 INCREMENT BY 50;

CREATE TABLE provisioned_resources (
    id BIGINT PRIMARY KEY,
    workspace_id UUID NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    type VARCHAR(50) NOT NULL,
    notion_id VARCHAR(255) NOT NULL,
    provisioned_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_provisioned_resources_workspace_type UNIQUE (workspace_id, type)
);

CREATE INDEX idx_provisioned_resources_workspace_id ON provisioned_resources (workspace_id);
