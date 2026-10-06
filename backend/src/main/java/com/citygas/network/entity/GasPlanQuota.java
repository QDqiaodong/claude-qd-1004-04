package com.citygas.network.entity;

import java.math.BigDecimal;
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
 * 计划版本下单个门站或调压站的配额行。提交/冻结后跟随版本只读，复制时复制为新草稿行。
 */
@Entity
@Table(name = "gas_plan_quota")
@Getter
@Setter
public class GasPlanQuota {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @TenantId
    @Column(name = "tenant_id", nullable = false, length = 16)
    private String tenantId;

    @Column(name = "plan_version_id", nullable = false)
    private Long planVersionId;

    /** GATE_STATION / REGULATOR_STATION */
    @Column(name = "station_type", nullable = false, length = 24)
    private String stationType;

    @Column(name = "station_id", nullable = false)
    private Long stationId;

    @Column(name = "station_code", nullable = false, length = 24)
    private String stationCode;

    @Column(name = "station_name", nullable = false, length = 64)
    private String stationName;

    /** 编制时门站实际日输气量；调压站为空 */
    @Column(name = "daily_flow_m3", precision = 12, scale = 2)
    private BigDecimal dailyFlowM3;

    /** 编制时进站/出口能力快照 */
    @Column(name = "capacity_m3", nullable = false, precision = 12, scale = 2)
    private BigDecimal capacityM3;

    @Column(name = "quota_m3", nullable = false, precision = 12, scale = 2)
    private BigDecimal quotaM3;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
