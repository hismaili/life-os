package com.lifeos.domain.person;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PersonTest {

    @Test
    void create_wrapsValidEmailInValueObject() {
        Person person = Person.create("Ada", "ada@example.com");

        assertThat(person.getEmail()).isEqualTo(Email.of("ada@example.com"));
        assertThat(person.getId()).isNotNull();
    }

    @Test
    void create_allowsNullEmail() {
        Person person = Person.create("Ada", null);

        assertThat(person.getEmail()).isNull();
    }

    @Test
    void create_rejectsInvalidEmail() {
        assertThatThrownBy(() -> Person.create("Ada", "bogus")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void create_rejectsBlankName() {
        assertThatThrownBy(() -> Person.create("   ", "ada@example.com")).isInstanceOf(IllegalArgumentException.class);
    }
}
