package com.lifeos.application.port;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PageShapeTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "  "})
    void constructor_rejectsBlankTitle(String title) {
        assertThatThrownBy(() -> new PageShape(title, ParentConstraint.ROOT_PARENT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_rejectsNullParent() {
        assertThatThrownBy(() -> new PageShape("LifeOS — Personal", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void constructor_acceptsValidInputs() {
        PageShape shape = new PageShape("LifeOS — Personal", ParentConstraint.ROOT_PARENT);

        assertThat(shape.title()).isEqualTo("LifeOS — Personal");
        assertThat(shape.parent()).isEqualTo(ParentConstraint.ROOT_PARENT);
    }
}
