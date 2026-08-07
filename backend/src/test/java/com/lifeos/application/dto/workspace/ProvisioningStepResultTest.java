package com.lifeos.application.dto.workspace;

import com.lifeos.domain.workspace.ProvisionedResourceType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProvisioningStepResultTest {

    @Test
    void constructor_requiresDetailWhenFailed() {
        assertThatThrownBy(() -> new ProvisioningStepResult(
                ProvisionedResourceType.DASHBOARD, ProvisioningOutcome.FAILED, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ProvisioningStepResult(
                ProvisionedResourceType.DASHBOARD, ProvisioningOutcome.FAILED, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_requiresDetailWhenBlocked() {
        assertThatThrownBy(() -> new ProvisioningStepResult(
                ProvisionedResourceType.DASHBOARD, ProvisioningOutcome.BLOCKED, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @EnumSource(value = ProvisioningOutcome.class, names = {"CREATED", "RECONCILED", "REPAIRED"})
    void constructor_allowsNullDetailWhenCreated(ProvisioningOutcome outcome) {
        assertThatCode(() -> new ProvisioningStepResult(ProvisionedResourceType.DASHBOARD, outcome, null))
                .doesNotThrowAnyException();
    }
}
