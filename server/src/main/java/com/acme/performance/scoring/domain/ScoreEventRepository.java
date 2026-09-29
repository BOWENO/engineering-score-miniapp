package com.acme.performance.scoring.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ScoreEventRepository extends JpaRepository<ScoreEvent, UUID> {
}
