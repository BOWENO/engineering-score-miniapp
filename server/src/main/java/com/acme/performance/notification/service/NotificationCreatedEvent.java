package com.acme.performance.notification.service;

import java.util.UUID;

public record NotificationCreatedEvent(UUID notificationId) {}
