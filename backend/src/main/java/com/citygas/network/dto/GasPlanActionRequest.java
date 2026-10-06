package com.citygas.network.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/** 提交复核 / 复核通过 / 驳回 / 冻结。 */
@Getter
@Setter
public class GasPlanActionRequest {

    @NotBlank(message = "操作人不能为空")
    private String operator;

    /** 复核意见：复核通过、驳回、冻结时必须填写 */
    private String opinion;
}
