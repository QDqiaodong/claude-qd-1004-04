package com.citygas.network.dto;

import java.math.BigDecimal;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GasPlanQuotaVO {

    private Long id;
    private Long planVersionId;
    private String stationType;
    private Long stationId;
    private String stationCode;
    private String stationName;
    private BigDecimal dailyFlowM3;
    private BigDecimal capacityM3;
    private BigDecimal quotaM3;
    private boolean negative;
    private boolean overCapacity;
}
