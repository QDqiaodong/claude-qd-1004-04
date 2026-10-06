package com.citygas.network.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

/** 从已提交/已冻结版本复制出新草稿版本。 */
@Getter
@Setter
public class GasPlanCopyRequest {

    @NotBlank(message = "操作人不能为空")
    private String operator;

    private String changeNote;
}
