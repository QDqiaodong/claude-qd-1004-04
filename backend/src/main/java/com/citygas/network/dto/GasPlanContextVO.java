package com.citygas.network.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.citygas.network.entity.GateStation;
import com.citygas.network.entity.RegulatorStation;

import lombok.Getter;
import lombok.Setter;

/**
 * 编制页上下文：只包含当前片区站点。门站展示实际日输气量与进站能力，
 * 调压站展示出口能力作为配额上限。
 */
@Getter
@Setter
public class GasPlanContextVO {

    private LocalDate planDate;
    private Iterable<GateStation> gateStations;
    private Iterable<RegulatorStation> regulatorStations;
    private BigDecimal areaDemandM3;
    private String demandSource;
    private String demandSourceLabel;
}
