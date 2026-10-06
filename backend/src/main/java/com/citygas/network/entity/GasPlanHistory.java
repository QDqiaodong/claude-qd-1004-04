package com.citygas.network.entity;

import java.time.LocalDateTime;

import org.hibernate.annotations.TenantId;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * 供气计划版本历史。复核意见、冻结动作、复制来源及配额变更说明均在此留痕；
 * 已冻结版本的配额明细由 gas_plan_quota 原样保存，历史意见不可被后续版本改写。
 */
@Entity
@Table(name = "gas_plan_history")
@Getter
@Setter
public class GasPlanHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, length = 16)
    private String tenantId;

    @Column(name = "plan_version_id", nullable = false)
    private Long planVersionId;

    @Column(name = "action", nullable = false, length = 24)
    private String action;

    @Column(name = "from_status", length = 16)
    private String fromStatus;

    @Column(name = "to_status", length = 16)
    private String toStatus;

    @Column(name = "operator_name", nullable = false, length = 32)
    private String operatorName;

    @Column(name = "note", length = 1024)
    private String note;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
