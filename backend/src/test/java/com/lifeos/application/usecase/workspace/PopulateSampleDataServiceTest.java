package com.lifeos.application.usecase.workspace;

import com.lifeos.application.port.NotionProvisioningPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(MockitoExtension.class)
class PopulateSampleDataServiceTest {

    @Mock private NotionProvisioningPort notion;
    @Mock private WorkspaceLedgerWriter ledger;

    @Test
    void execute_throwsUnsupportedOperationExceptionUntilNotionAdapterExists() {
        PopulateSampleDataService service = new PopulateSampleDataService(notion, ledger);

        assertThatThrownBy(() -> service.execute(UUID.randomUUID()))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
