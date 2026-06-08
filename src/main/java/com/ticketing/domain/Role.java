package com.ticketing.domain;

/**
 * Application roles. Stored as a string in the DB (see {@code @Enumerated(STRING)})
 * so adding a role later never shifts ordinal values of existing rows.
 */
public enum Role {
    USER,
    ADMIN
}
