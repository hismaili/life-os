package com.lifeos.infrastructure.adapter.persistence;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "workspaces", uniqueConstraints = @UniqueConstraint(columnNames = {"person_id", "name"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
class WorkspaceJpaEntity {

    @Id
    private UUID id;

    @Column(name = "person_id", nullable = false)
    private UUID personId;

    @Column(nullable = false)
    private String name;

    @OneToMany(mappedBy = "workspace", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<ProvisionedResourceJpaEntity> resources = new ArrayList<>();

    @Version
    private long version;
}
