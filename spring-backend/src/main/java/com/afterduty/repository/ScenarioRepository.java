package com.afterduty.repository;

import com.afterduty.model.ClaimScenario;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ScenarioRepository extends JpaRepository<ClaimScenario, Long> {
    List<ClaimScenario> findByUserIdOrderByCreatedAtDesc(Long userId);
    Optional<ClaimScenario> findByIdAndUserId(Long id, Long userId);
}
