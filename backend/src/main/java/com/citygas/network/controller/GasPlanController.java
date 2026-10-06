package com.citygas.network.controller;

import java.time.LocalDate;

import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.citygas.network.common.ApiResponse;
import com.citygas.network.dto.GasPlanActionRequest;
import com.citygas.network.dto.GasPlanContextVO;
import com.citygas.network.dto.GasPlanCopyRequest;
import com.citygas.network.dto.GasPlanDetailVO;
import com.citygas.network.dto.GasPlanHistoryVO;
import com.citygas.network.dto.GasPlanSaveRequest;
import com.citygas.network.dto.GasPlanValidationVO;
import com.citygas.network.dto.GasPlanVersionCompareVO;
import com.citygas.network.dto.PageResponse;
import com.citygas.network.entity.GasSupplyPlan;
import com.citygas.network.service.GasPlanService;

import jakarta.validation.Valid;

/**
 * 门站日输气计划与供气配额编排接口。所有站点、版本、配额均由 X-Tenant 对应片区隔离。
 */
@RestController
@RequestMapping("/api/gas-supply-plans")
public class GasPlanController {

    private final GasPlanService service;

    public GasPlanController(GasPlanService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PageResponse<GasSupplyPlan>> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate planDate,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        Page<GasSupplyPlan> result = service.search(planDate, status, keyword, page, size);
        return ApiResponse.success(PageResponse.of(result));
    }

    @GetMapping("/context")
    public ApiResponse<GasPlanContextVO> context(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.success(service.context(date));
    }

    @GetMapping("/{id}")
    public ApiResponse<GasPlanDetailVO> detail(@PathVariable Long id) {
        return ApiResponse.success(service.detail(id));
    }

    @GetMapping("/{id}/history")
    public ApiResponse<GasPlanHistoryVO> history(@PathVariable Long id) {
        return ApiResponse.success(service.history(id));
    }

    @GetMapping("/compare")
    public ApiResponse<GasPlanVersionCompareVO> compare(@RequestParam Long from, @RequestParam Long to) {
        return ApiResponse.success(service.compare(from, to));
    }

    @GetMapping("/{id}/validation")
    public ApiResponse<GasPlanValidationVO> validation(@PathVariable Long id) {
        return ApiResponse.success(service.validateForApi(id));
    }

    @PostMapping
    public ApiResponse<GasPlanDetailVO> create(@Valid @RequestBody GasPlanSaveRequest req) {
        return ApiResponse.success("供气计划草稿已创建", service.create(req));
    }

    @PutMapping("/{id}")
    public ApiResponse<GasPlanDetailVO> update(@PathVariable Long id,
                                               @Valid @RequestBody GasPlanSaveRequest req) {
        return ApiResponse.success("草稿配额已保存", service.update(id, req));
    }

    @PostMapping("/{id}/submit")
    public ApiResponse<GasPlanDetailVO> submit(@PathVariable Long id,
                                               @Valid @RequestBody GasPlanActionRequest req) {
        return ApiResponse.success("已提交复核，配额进入复核冻结状态", service.submit(id, req));
    }

    @PostMapping("/{id}/approve")
    public ApiResponse<GasPlanDetailVO> approve(@PathVariable Long id,
                                                @Valid @RequestBody GasPlanActionRequest req) {
        return ApiResponse.success("复核通过，计划已提交", service.approve(id, req));
    }

    @PostMapping("/{id}/reject")
    public ApiResponse<GasPlanDetailVO> reject(@PathVariable Long id,
                                               @Valid @RequestBody GasPlanActionRequest req) {
        return ApiResponse.success("已驳回，草稿配额保留并可继续调整", service.reject(id, req));
    }

    @PostMapping("/{id}/freeze")
    public ApiResponse<GasPlanDetailVO> freeze(@PathVariable Long id,
                                               @Valid @RequestBody GasPlanActionRequest req) {
        return ApiResponse.success("计划版本已冻结，历史配额和复核意见已留存", service.freeze(id, req));
    }

    @PostMapping("/{id}/copy")
    public ApiResponse<GasPlanDetailVO> copy(@PathVariable Long id,
                                             @Valid @RequestBody GasPlanCopyRequest req) {
        return ApiResponse.success("已复制为新草稿版本", service.copy(id, req));
    }
}
