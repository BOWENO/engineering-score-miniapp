package com.acme.performance.settlement.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DGradeDeadlineScheduler {
    private final DGradeService service; public DGradeDeadlineScheduler(DGradeService service){this.service=service;}
    @Scheduled(cron="0 */5 * * * *",zone="Asia/Shanghai") public void expireAppeals(){service.expireAppeals();}
}
