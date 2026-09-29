package com.acme.performance.settlement.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SettlementScheduler {
    private final SettlementService service;public SettlementScheduler(SettlementService service){this.service=service;}
    @Scheduled(cron="0 0 9 * * *",zone="Asia/Shanghai") public void preparePreviousMonth(){service.autoPrepare();}
}
