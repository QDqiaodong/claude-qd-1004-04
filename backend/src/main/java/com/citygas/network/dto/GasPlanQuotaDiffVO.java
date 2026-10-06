package com.citygas.network.dto;

import java.math.BigDecimal;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GasPlanQuotaDiffVO {

    private String key;
    private String stationType;
    private Long stationId;
    private String stationCode;
    private String stationName;
    private BigDecimal capacityM3;
    private BigDecimal fromQuotaM3;
    private BigDecimal toQuotaM3;
    private BigDecimal deltaM3;
    private String changeType;
}
