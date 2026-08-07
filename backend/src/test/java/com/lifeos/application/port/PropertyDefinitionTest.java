package com.lifeos.application.port;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PropertyDefinitionTest {

    @Test
    void constructor_rejectsBlankName() {
        assertThatThrownBy(() -> new PropertyDefinition(" ", NotionPropertyType.TITLE, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PropertyDefinition(null, NotionPropertyType.TITLE, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_rejectsNullType() {
        assertThatThrownBy(() -> new PropertyDefinition("Name", null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_rejectsOptionsOnNonSelectType() {
        assertThatThrownBy(() -> new PropertyDefinition("Name", NotionPropertyType.TITLE, List.of("a")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_allowsEmptyOptionsOnNonSelectType() {
        PropertyDefinition definition = new PropertyDefinition("Name", NotionPropertyType.TITLE, List.of());

        assertThat(definition.options()).isEmpty();
    }

    @Test
    void constructor_allowsOptionsOnSelectType() {
        PropertyDefinition definition = new PropertyDefinition("Status", NotionPropertyType.SELECT, List.of("Planned", "Active"));

        assertThat(definition.options()).containsExactly("Planned", "Active");
    }

    @Test
    void of_createsWithEmptyOptions() {
        PropertyDefinition definition = PropertyDefinition.of("Name", NotionPropertyType.TITLE);

        assertThat(definition.options()).isEmpty();
    }
}
