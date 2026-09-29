package com.acme.performance.settlement.service;

import com.acme.performance.performance.service.PerformanceAnalyticsService;
import com.acme.performance.performance.service.PerformanceCaseService;
import com.acme.performance.common.service.BusinessDeadlineService;
import com.acme.performance.common.api.ApiException;
import com.acme.performance.admin.service.AuditLogService;
import com.acme.performance.notification.service.NotificationService;
import org.junit.jupiter.api.Test;
import java.time.YearMonth;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Audit reproductions: assertions describe existing defects, not desired behavior. */
class ReviewAudit20260916Test extends SettlementIsolationTest {
    @Test void auditOverviewLeaksForeignOrganization() {
        var foreign = user(orgB,"TECHNICIAN",false);
        assertThat(new PerformanceAnalyticsService(jdbc).overview(a,period).people())
            .anyMatch(person -> person.userId().equals(foreign.userId()));
    }

    @Test void auditPublicationIgnoresNewPendingBusinessAndChangedScores() {
        UUID run = preview();
        pendingFixtures(tech);
        jdbc.sql("INSERT INTO score_summary(user_id,period,total) VALUES (:user,:period,7)")
            .param("user",tech.userId()).param("period",period).update();
        assertThat(settlements.pendingCount(YearMonth.parse(period),orgA)).isEqualTo(5);
        assertThat(transaction(() -> settlements.confirm(a,run,"audit","audit")).status())
            .isEqualTo("PUBLISHED");
        assertThat(jdbc.sql("SELECT total FROM grade_snapshot WHERE settlement_run_id=:run AND user_id=:user")
            .param("run",run).param("user",tech.userId()).query(Integer.class).single()).isZero();
    }

    @Test void auditReturnedDeductionCannotResumeAndBlocksSettlement() {
        var assistant=user(orgA,"ASSISTANT_ENGINEER",false);
        var cases=new PerformanceCaseService(jdbc,mock(NotificationService.class),
            mock(BusinessDeadlineService.class),mock(AuditLogService.class));
        var created=transaction(() -> cases.createDeduction(assistant,
            new PerformanceCaseService.DeductionInput("BASE_DEDUCTION",tech.userId(),
                OffsetDateTime.parse("2025-01-10T10:00:00+08:00"),"audit",null,2,List.of(),null),"audit"));
        var returned=transaction(() -> cases.review(a,created.id(),created.version(),"RETURN",null,null,"revise","audit"));
        assertThat(returned.status()).isEqualTo("RETURNED");
        assertThatThrownBy(() -> transaction(() -> cases.resubmitBonus(assistant,returned.id(),returned.version(),"revised",List.of(),"audit")))
            .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> transaction(() -> cases.resubmitBonus(tech,returned.id(),returned.version(),"revised",List.of(),"audit")))
            .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> transaction(() -> cases.review(a,returned.id(),returned.version(),"REJECT",null,null,"close","audit")))
            .isInstanceOf(ApiException.class);
        assertThat(settlements.pendingCount(YearMonth.parse(period),orgA)).isEqualTo(1);
    }
}
