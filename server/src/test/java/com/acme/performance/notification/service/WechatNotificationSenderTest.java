package com.acme.performance.notification.service;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import static org.assertj.core.api.Assertions.assertThat;

class WechatNotificationSenderTest {

    @Test
    void transactionalEventListenerUsesAnAllowedIndependentTransaction() throws Exception {
        var method = WechatNotificationSender.class.getMethod("deliver", NotificationCreatedEvent.class);
        assertThat(method.getAnnotation(TransactionalEventListener.class)).isNotNull();
        assertThat(method.getAnnotation(Transactional.class).propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }
}
