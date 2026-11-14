package org.example.smashhub.chat.ai.repository;

import org.example.smashhub.chat.ai.entity.AiCostLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface AiCostLogRepository extends JpaRepository<AiCostLog, Long> {

    Optional<AiCostLog> findByLogDateAndModelName(LocalDate logDate, String modelName);

    List<AiCostLog> findByLogDate(LocalDate logDate);
}

