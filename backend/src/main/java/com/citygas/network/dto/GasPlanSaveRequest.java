package com.citygas.network.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

/** 创建/保存日输气计划草稿。 */
@Getter
@Setter
public class GasPlanSaveRequest {

    @NotBlank(message = "计划编号不能为空")
    private String planNo;

    @NotNull(message = "计划日期不能为空")
    private LocalDate planDate;

    @NotNull(message = "片区需求量不能为空")
    @DecimalMin(value = "0.00", message = "片区需求量不能为负")
    private BigDecimal areaDemandM3;

    /** 草稿可暂缺需求来源；提交复核时必须补齐。 */
    private String demandSource;

    private String demandReference;

    private String changeNote;

    @NotBlank(message = "操作人不能为空")
    private String operator;

    @Valid
    private List<Allocation> allocations = new ArrayList<>();

    @Getter
    @Setter
    public static class Allocation {
        @NotBlank(message = "站点类型不能为空")
        private String stationType;

        @NotNull(message = "站点 ID 不能为空")
        private Long stationId;

        @NotNull(message = "配额不能为空")
        private BigDecimal quotaM3;
    }
}
