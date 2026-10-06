package com.citygas.network.dto;

import java.util.List;

import com.citygas.network.entity.GasPlanHistory;
import com.citygas.network.entity.GasSupplyPlan;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GasPlanHistoryVO {

    private List<GasSupplyPlan> versions;
    private List<GasPlanHistory> histories;
}
