package com.acme.performance.common.service;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.UUID;

@Service
public class BusinessDeadlineService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final JdbcClient jdbc;

    public BusinessDeadlineService(JdbcClient jdbc) { this.jdbc = jdbc; }

    public OffsetDateTime addHours(OffsetDateTime start, int hours, UUID userId) {
        ZonedDateTime cursor = start.atZoneSameInstant(ZONE).truncatedTo(java.time.temporal.ChronoUnit.MINUTES);
        long remainingMinutes = hours * 60L;
        while (remainingMinutes > 0) {
            ZonedDateTime nextDay = cursor.toLocalDate().plusDays(1).atStartOfDay(ZONE);
            if (!counts(cursor.toLocalDate(), userId)) {
                cursor = nextDay;
                continue;
            }
            long available = Math.max(1, Duration.between(cursor, nextDay).toMinutes());
            long consumed = Math.min(remainingMinutes, available);
            cursor = cursor.plusMinutes(consumed);
            remainingMinutes -= consumed;
        }
        return cursor.toOffsetDateTime();
    }

    public boolean isPast(OffsetDateTime deadline) {
        return deadline != null && OffsetDateTime.now(ZONE).isAfter(deadline);
    }

    private boolean counts(LocalDate date, UUID userId) {
        String type = jdbc.sql("SELECT day_type FROM work_calendar WHERE calendar_date=:date")
                .param("date", date).query(String.class).optional().orElse("WORKDAY");
        if (!"HOLIDAY".equals(type)) return true;
        if (userId == null) return false;
        Long scheduled = jdbc.sql("""
                SELECT COUNT(*) FROM schedule_assignment WHERE user_id=:userId AND business_date=:date AND status='PUBLISHED'
                """).param("userId", userId).param("date", date).query(Long.class).single();
        return scheduled > 0;
    }
}
