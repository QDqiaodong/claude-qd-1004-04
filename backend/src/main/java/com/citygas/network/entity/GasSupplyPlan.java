package com.citygas.network.entity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

import org.hibernate.annotations.TenantId;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.Setter;

/**
 * 门站日输气计划的一个不可跨状态覆盖的版本。
 *
 * <p>状态：草稿 → 待复核 → 已提交 → 已冻结。待复核被驳回时回到草稿，配额保留，
 * 已提交/已冻结版本只读，只能复制生成下一版草稿。{@code lockVersion} 是并发收口用的
 * JPA 乐观锁，{@code planVersion} 是面向业务的版本号。</p>
 */
@Entity
@Table(name = "gas_supply_plan")
@Getter
@Setter
public class GasSupplyPlan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, length = 16)
    private String tenantId;

    @Column(name = "plan_no", nullable = false, length = 40)
    private String planNo;

    @Column(name = "plan_version", nullable = false)
    private Integer planVersion;

    @Column(name = "plan_date", nullable = false)
    private LocalDate planDate;

    @Column(name = "area_demand_m3", nullable = false, precision = 12, scale = 2)
    private BigDecimal areaDemandM3;

    /** 草稿可暂缺；METER_SNAPSHOT / MANUAL */
    @Column(name = "demand_source", length = 24)
    private String demandSource;

    @Column(name = "demand_reference", length = 256)
    private String demandReference;

    /** 草稿 / 待复核 / 已提交 / 已冻结 */
    @Column(name = "status", nullable = false, length = 16)
    private String status;

    @Column(name = "change_note", length = 512)
    private String changeNote;

    @Column(name = "source_version_id")
    private Long sourceVersionId;

    @Column(name = "created_by", length = 32)
    private String createdBy;

    @Column(name = "updated_by", length = 32)
    private String updatedBy;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    @Column(name = "submitted_by", length = 32)
    private String submittedBy;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;

    @Column(name = "reviewed_by", length = 32)
    private String reviewedBy;

    @Column(name = "review_opinion", length = 512)
    private String reviewOpinion;

    @Column(name = "frozen_at")
    private LocalDateTime frozenAt;

    @Column(name = "frozen_by", length = 32)
    private String frozenBy;

    /** 并发复核/提交收口；不与业务版本号混用 */
    @Version
    @Column(name = "lock_version", nullable = false)
    private Long lockVersion;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
