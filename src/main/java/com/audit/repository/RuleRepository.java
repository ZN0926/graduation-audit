package com.audit.repository;

import com.audit.model.Rule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Rule 的持久层。
 */
public interface RuleRepository extends JpaRepository<Rule, Long> {
    List<Rule> findByLanguage(String language);
    List<Rule> findByLanguageAndCategory(String language, String category);
    Optional<Rule> findByRuleId(String ruleId);
    boolean existsByRuleId(String ruleId);
    long countByLanguage(String language);
}