package com.citygas.network.dto;

import java.math.BigDecimal;
import java.util.List;

import com.citygas.network.entity.GasPlanHistory;
import com.citygas.network.entity.GasSupplyPlan;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GasPlanVersionCompareVO {

    private GasSupplyPlan fromVersion;
    private GasSupplyPlan toVersion;
    private BigDecimal fromAreaDemandM3;
    private BigDecimal toAreaDemandM3;
    private BigDecimal areaDemandDeltaM3;
    private List<GasPlanQuotaDiffVO> quotaDiffs;
    private List<GasPlanHistory> toHistories;
}
