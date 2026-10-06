package com.citygas.network.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GasPlanValidationVO {

    private boolean validForSubmit;
    private String demandSourceLabel;
    private BigDecimal gateTotalQuotaM3;
    private BigDecimal gateTotalCapacityM3;
    private BigDecimal regulatorTotalQuotaM3;
    private BigDecimal regulatorTotalCapacityM3;
    private boolean gateTotalOverCapacity;
    private boolean missingDemandSource;
    private boolean hasNegativeQuota;
    private boolean missingStation;
    private List<String> errors = new ArrayList<>();
    private List<String> warnings = new ArrayList<>();
}
