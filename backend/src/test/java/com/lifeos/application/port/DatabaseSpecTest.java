package com.lifeos.application.port;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseSpecTest {

    @Test
    void constructor_rejectsBlankTitle() {
        List<PropertyDefinition> properties = List.of(PropertyDefinition.of("Name", NotionPropertyType.TITLE));

        assertThatThrownBy(() -> new DatabaseSpec(" ", properties))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_rejectsNullOrEmptyProperties() {
        assertThatThrownBy(() -> new DatabaseSpec("Projects", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DatabaseSpec("Projects", List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_rejectsZeroTitleProperties() {
        List<PropertyDefinition> properties = List.of(PropertyDefinition.of("Description", NotionPropertyType.RICH_TEXT));

        assertThatThrownBy(() -> new DatabaseSpec("Projects", properties))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_rejectsMoreThanOneTitleProperty() {
        List<PropertyDefinition> properties = List.of(
                PropertyDefinition.of("Name", NotionPropertyType.TITLE),
                PropertyDefinition.of("Other", NotionPropertyType.TITLE));

        assertThatThrownBy(() -> new DatabaseSpec("Projects", properties))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_acceptsExactlyOneTitleProperty() {
        List<PropertyDefinition> properties = List.of(
                PropertyDefinition.of("Name", NotionPropertyType.TITLE),
                PropertyDefinition.of("Description", NotionPropertyType.RICH_TEXT));

        DatabaseSpec spec = new DatabaseSpec("Projects", properties);

        assertThat(spec.properties()).hasSize(2);
    }
}
