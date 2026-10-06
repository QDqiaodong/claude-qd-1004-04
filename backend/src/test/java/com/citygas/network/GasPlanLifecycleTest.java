package com.citygas.network;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.citygas.network.common.BizException;
import com.citygas.network.common.ConflictException;
import com.citygas.network.common.Dict;
import com.citygas.network.dto.GasPlanActionRequest;
import com.citygas.network.dto.GasPlanCopyRequest;
import com.citygas.network.dto.GasPlanDetailVO;
import com.citygas.network.dto.GasPlanQuotaDiffVO;
import com.citygas.network.dto.GasPlanSaveRequest;
import com.citygas.network.dto.GasPlanVersionCompareVO;
import com.citygas.network.entity.GasPlanQuota;
import com.citygas.network.entity.GasSupplyPlan;
import com.citygas.network.entity.GateStation;
import com.citygas.network.entity.RegulatorStation;
import com.citygas.network.repository.GasPlanHistoryRepository;
import com.citygas.network.repository.GasPlanQuotaRepository;
import com.citygas.network.repository.GasSupplyPlanRepository;
import com.citygas.network.service.GasPlanService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GasPlanLifecycleTest extends BaseIntegrationTest {

    private static final String TENANT = "A片区";

    @Autowired
    private GasPlanService service;
    @Autowired
    private GasSupplyPlanRepository planRepository;
    @Autowired
    private GasPlanQuotaRepository quotaRepository;
    @Autowired
    private GasPlanHistoryRepository historyRepository;

    @Test
    void capacity_constraints_block_submit_but_negative_draft_is_kept() {
        Fixture f = prepareStations();
        GasPlanSaveRequest req = request(f, "GP-CAP", new BigDecimal("100000"),
                new BigDecimal("130000"), new BigDecimal("60000"));
        GasPlanDetailVO draft = service.create(req);
        assertThat(draft.getValidation().isValidForSubmit()).isFalse();
        assertThat(draft.getValidation().isGateTotalOverCapacity()).isTrue();
        assertThat(draft.getValidation().getErrors()).anyMatch(e -> e.contains("进站能力合计"));
        assertThatThrownBy(() -> service.submit(draft.getPlan().getId(), action("提交人甲")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("进站能力合计");

        // 改成负数：草稿可以保存，提交继续拦截且行原样保留
        req.setOperator("编制人乙");
        req.setAllocations(List.of(
                allocation(Dict.GP_GATE, f.gateId(), new BigDecimal("-1")),
                allocation(Dict.GP_REGULATOR, f.regulatorId(), new BigDecimal("10"))));
        draft = service.update(draft.getPlan().getId(), req);
        assertThat(draft.getPlan().getStatus()).isEqualTo(Dict.GP_DRAFT);
        GasPlanQuota negative = quotaRepository.findByVersion(draft.getPlan().getId()).stream()
                .filter(q -> Dict.GP_GATE.equals(q.getStationType())).findFirst().orElseThrow();
        assertThat(negative.getQuotaM3()).isEqualByComparingTo("-1");
        assertThat(draft.getValidation().isHasNegativeQuota()).isTrue();
        assertThatThrownBy(() -> service.submit(draft.getPlan().getId(), action("提交人甲")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("配额不能为负");
    }

    @Test
    void submit_approve_freeze_copy_keeps_history_and_diffs() {
        Fixture f = prepareStations();
        GasPlanDetailVO v1 = service.create(request(f, "GP-LIFE", new BigDecimal("100000"),
                new BigDecimal("90000"), new BigDecimal("40000")));
        Long v1Id = v1.getPlan().getId();
        service.submit(v1Id, action("提交人甲"));
        service.approve(v1Id, opinion("复核人甲", "符合能力边界"));
        service.freeze(v1Id, opinion("调度甲", "执行冻结"));

        GasSupplyPlan frozen = planRepository.findScopedById(v1Id).orElseThrow();
        assertThat(frozen.getStatus()).isEqualTo(Dict.GP_FROZEN);
        assertThatThrownBy(() -> service.update(v1Id, request(f, "GP-LIFE", new BigDecimal("100000"),
                new BigDecimal("90000"), new BigDecimal("40000"))))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("配额已锁定");
        assertThat(frozen.getReviewOpinion()).contains("符合能力边界").contains("执行冻结");
        assertThat(quotaRepository.findByVersion(v1Id))
                .allSatisfy(q -> assertThat(q.getQuotaM3()).isNotNull());

        GasPlanCopyRequest copy = new GasPlanCopyRequest();
        copy.setOperator("编制人乙");
        copy.setChangeNote("次日调增调压站配额");
        GasPlanDetailVO v2 = service.copy(v1Id, copy);
        assertThat(v2.getPlan().getStatus()).isEqualTo(Dict.GP_DRAFT);
        assertThat(v2.getPlan().getPlanVersion()).isEqualTo(2);
        assertThat(v2.getPlan().getSourceVersionId()).isEqualTo(v1Id);

        // 已冻结版本仍可查，复制不改写它的历史意见
        GasSupplyPlan frozenAgain = planRepository.findScopedById(v1Id).orElseThrow();
        assertThat(frozenAgain.getStatus()).isEqualTo(Dict.GP_FROZEN);
        assertThat(historyRepository.findByVersion(v1Id)).extracting("action")
                .contains(Dict.GP_ACTION_CREATE, Dict.GP_ACTION_SUBMIT, Dict.GP_ACTION_APPROVE,
                        Dict.GP_ACTION_FREEZE);
        assertThat(historyRepository.findByVersion(v1Id)).extracting("note")
                .contains("符合能力边界", "执行冻结");

        // 修改 v2 后对比：门站不变，调压站 +10000
        GasPlanSaveRequest update = request(f, "GP-LIFE", new BigDecimal("110000"),
                new BigDecimal("90000"), new BigDecimal("50000"));
        update.setChangeNote("调压站调增");
        service.update(v2.getPlan().getId(), update);
        GasPlanVersionCompareVO compare = service.compare(v1Id, v2.getPlan().getId());
        assertThat(compare.getAreaDemandDeltaM3()).isEqualByComparingTo("10000");
        GasPlanQuotaDiffVO regulatorDiff = compare.getQuotaDiffs().stream()
                .filter(d -> Dict.GP_REGULATOR.equals(d.getStationType())).findFirst().orElseThrow();
        assertThat(regulatorDiff.getDeltaM3()).isEqualByComparingTo("10000");
        assertThat(regulatorDiff.getChangeType()).isEqualTo("调整");
    }

    @Test
    void missing_demand_source_blocks_submit_and_readonly_version_cannot_change_quotas() {
        Fixture f = prepareStations();
        GasPlanSaveRequest req = request(f, "GP-SRC", null, new BigDecimal("80"), new BigDecimal("20"));
        GasPlanDetailVO draft = service.create(req);
        assertThat(draft.getValidation().isMissingDemandSource()).isTrue();
        assertThatThrownBy(() -> service.submit(draft.getPlan().getId(), action("提交人甲")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("缺少需求来源");

        req.setDemandSource(Dict.DEMAND_METER_SNAPSHOT);
        service.update(draft.getPlan().getId(), req);
        Long id = draft.getPlan().getId();
        service.submit(id, action("提交人甲"));
        assertThatThrownBy(() -> service.update(id, req))
                .hasMessageContaining("配额已锁定");
    }

    @Test
    void only_current_tenant_stations_can_be_used_in_plan() {
        Fixture a = prepareStations();
        useTenant("B片区");
        GateStation gateB = newGate("B片区", "GS-BP1", "B门站");
        RegulatorStation regulatorB = newRegulator("B片区", "RS-BP1");
        regulatorB.setOutletCapacityM3(new BigDecimal("50000"));
        regulatorRepository.saveAndFlush(regulatorB);

        useTenant(TENANT);
        GasPlanSaveRequest req = request(a, "GP-TENANT", new BigDecimal("50000"),
                new BigDecimal("10000"), new BigDecimal("10000"));
        req.getAllocations().add(allocation(Dict.GP_GATE, gateB.getId(), new BigDecimal("10000")));
        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不存在或不属于当前片区");

        req.setAllocations(List.of(
                allocation(Dict.GP_GATE, a.gateId(), new BigDecimal("10000")),
                allocation(Dict.GP_REGULATOR, regulatorB.getId(), new BigDecimal("10000"))));
        assertThatThrownBy(() -> service.create(req))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不存在或不属于当前片区");
    }

    private Fixture prepareStations() {
        useTenant(TENANT);
        GateStation gate = newGate(TENANT, "GS-P1", "计划门站");
        gate.setInletCapacityM3(new BigDecimal("100000"));
        gate.setDailyFlowM3(new BigDecimal("88000"));
        gate = gateStationRepository.saveAndFlush(gate);

        RegulatorStation regulator = newRegulator(TENANT, "RS-P1");
        regulator.setOutletCapacityM3(new BigDecimal("60000"));
        regulator = regulatorRepository.saveAndFlush(regulator);
        return new Fixture(gate.getId(), regulator.getId());
    }

    private GasPlanSaveRequest request(Fixture f, String no, BigDecimal demand,
                                       BigDecimal gateQuota, BigDecimal regulatorQuota) {
        GasPlanSaveRequest req = new GasPlanSaveRequest();
        req.setPlanNo(no);
        req.setPlanDate(LocalDate.of(2026, 10, 6));
        req.setAreaDemandM3(demand == null ? BigDecimal.ZERO : demand);
        req.setDemandSource(demand == null ? null : Dict.DEMAND_METER_SNAPSHOT);
        req.setDemandReference("抄表快照");
        req.setChangeNote("测试编排");
        req.setOperator("编制人甲");
        req.setAllocations(List.of(
                allocation(Dict.GP_GATE, f.gateId(), gateQuota),
                allocation(Dict.GP_REGULATOR, f.regulatorId(), regulatorQuota)));
        return req;
    }

    private GasPlanSaveRequest.Allocation allocation(String type, Long id, BigDecimal quota) {
        GasPlanSaveRequest.Allocation a = new GasPlanSaveRequest.Allocation();
        a.setStationType(type);
        a.setStationId(id);
        a.setQuotaM3(quota);
        return a;
    }

    private GasPlanActionRequest action(String operator) {
        GasPlanActionRequest r = new GasPlanActionRequest();
        r.setOperator(operator);
        return r;
    }

    private GasPlanActionRequest opinion(String operator, String opinion) {
        GasPlanActionRequest r = action(operator);
        r.setOpinion(opinion);
        return r;
    }

    private record Fixture(Long gateId, Long regulatorId) {
    }
}
