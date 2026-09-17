package com.afterduty.repository;

import com.afterduty.model.ServiceProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ServiceProfileRepository extends JpaRepository<ServiceProfile, Long> {
    Optional<ServiceProfile> findByUserId(Long userId);
}
