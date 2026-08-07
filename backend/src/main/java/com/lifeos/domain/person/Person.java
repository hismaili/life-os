package com.lifeos.domain.person;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.Value;

import java.util.UUID;

@Value
@Builder(access = AccessLevel.PRIVATE)
public class Person {
    UUID id;
    String name;
    Email email;

    public static Person create(String name, String email) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Person name must not be null or blank");
        }
        return Person.builder()
                .id(UUID.randomUUID())
                .name(name)
                .email(email == null ? null : Email.of(email))
                .build();
    }
}
