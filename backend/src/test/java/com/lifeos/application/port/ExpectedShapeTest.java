package com.lifeos.application.port;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExpectedShapeTest {

    @Test
    void constructor_rejectsBlankTitle() {
        List<PropertyDefinition> properties = List.of(PropertyDefinition.of("Name", NotionPropertyType.TITLE));

        assertThatThrownBy(() -> new ExpectedShape(" ", properties))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_rejectsNullOrEmptyRequiredProperties() {
        assertThatThrownBy(() -> new ExpectedShape("Projects", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ExpectedShape("Projects", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_acceptsValidInput() {
        List<PropertyDefinition> properties = List.of(PropertyDefinition.of("Name", NotionPropertyType.TITLE));

        ExpectedShape shape = new ExpectedShape("Projects", properties);

        assertThat(shape.requiredProperties()).hasSize(1);
    }
}
