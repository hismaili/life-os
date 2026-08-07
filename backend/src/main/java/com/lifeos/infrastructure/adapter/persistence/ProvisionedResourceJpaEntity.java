package com.lifeos.infrastructure.adapter.persistence;

import com.lifeos.domain.workspace.ProvisionedResourceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "provisioned_resources", uniqueConstraints = @UniqueConstraint(columnNames = {"workspace_id", "type"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
class ProvisionedResourceJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "provisioned_resource_id_seq")
    @SequenceGenerator(name = "provisioned_resource_id_seq", sequenceName = "provisioned_resource_id_seq", allocationSize = 50)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private WorkspaceJpaEntity workspace;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProvisionedResourceType type;

    @Column(name = "notion_id", nullable = false)
    private String notionId;

    @Column(name = "provisioned_at", nullable = false)
    private Instant provisionedAt;
}
