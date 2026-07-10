package com.modelgate.infrastructure.db;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneId;

final class JdbcTime {
    private JdbcTime() {
    }

    static Timestamp toTimestamp(OffsetDateTime value) {
        return value == null ? null : Timestamp.from(value.toInstant());
    }

    static OffsetDateTime toOffsetDateTime(Timestamp value) {
        return value == null ? null : value.toInstant().atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }
}
