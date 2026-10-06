package com.citygas.network.dto;

import java.util.List;

import com.citygas.network.entity.GasPlanHistory;
import com.citygas.network.entity.GasSupplyPlan;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GasPlanDetailVO {

    private GasSupplyPlan plan;
    private List<GasPlanQuotaVO> quotas;
    private List<GasPlanHistory> histories;
    private GasPlanValidationVO validation;
    private GasSupplyPlan latestVersion;
}
