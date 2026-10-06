package com.citygas.network;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.Commit;

import com.citygas.network.common.ConflictException;
import com.citygas.network.common.Dict;
import com.citygas.network.dto.GasPlanActionRequest;
import com.citygas.network.dto.GasPlanSaveRequest;
import com.citygas.network.entity.GateStation;
import com.citygas.network.entity.GasSupplyPlan;
import com.citygas.network.entity.RegulatorStation;
import com.citygas.network.repository.GasSupplyPlanRepository;
import com.citygas.network.service.GasPlanService;
import com.citygas.network.tenant.TenantContext;

import static org.assertj.core.api.Assertions.assertThat;

@Commit
class ConcurrentGasPlanTest extends BaseIntegrationTest {

    private static final String TENANT = "A片区";

    @Autowired
    private GasPlanService service;
    @Autowired
    private GasSupplyPlanRepository planRepository;

    @Test
    void concurrent_submit_only_one_advances_and_conflict_changes_nothing() throws Exception {
        Long id = prepareDraft();
        List<Outcome> outcomes = runParallel(
                () -> service.submit(id, action("提交人甲", "按计划提交")),
                () -> service.submit(id, action("提交人乙", "重复提交不得覆盖")));

        assertThat(outcomes).filteredOn(Outcome::ok).hasSize(1);
        assertThat(outcomes).filteredOn(o -> !o.ok()).hasSize(1)
                .first().extracting(Outcome::error).asString().contains("其他操作员");

        TenantContext.set(TENANT);
        GasSupplyPlan after = planRepository.findScopedById(id).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(Dict.GP_WAIT_REVIEW);
        assertThat(after.getSubmittedBy()).isIn("提交人甲", "提交人乙");
        assertThat(after.getLockVersion()).isEqualTo(1L);
    }

    @Test
    void concurrent_review_only_one_writes_opinion_other_gets_conflict() throws Exception {
        Long id = prepareWaitReview();
        List<Outcome> outcomes = runParallel(
                () -> service.approve(id, action("复核人甲", "甲的复核意见")),
                () -> service.approve(id, action("复核人乙", "乙的复核意见不得覆盖")));

        assertThat(outcomes).filteredOn(Outcome::ok).hasSize(1);
        assertThat(outcomes).filteredOn(o -> !o.ok()).hasSize(1)
                .first().extracting(Outcome::error).asString().contains("冲突");

        TenantContext.set(TENANT);
        GasSupplyPlan after = planRepository.findScopedById(id).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(Dict.GP_SUBMITTED);
        String winner = after.getReviewedBy();
        assertThat(winner).isIn("复核人甲", "复核人乙");
        assertThat(after.getReviewOpinion()).isEqualTo(winner.equals("复核人甲") ? "甲的复核意见" : "乙的复核意见");
        assertThat(after.getReviewOpinion()).doesNotContain(winner.equals("复核人甲") ? "乙" : "甲");
    }

    private Long prepareDraft() {
        useTenant(TENANT);
        GateStation gate = newGate(TENANT, "GS-CC1", "并发门站");
        gate.setInletCapacityM3(new BigDecimal("100000"));
        gate = gateStationRepository.saveAndFlush(gate);
        RegulatorStation regulator = newRegulator(TENANT, "RS-CC1");
        regulator.setOutletCapacityM3(new BigDecimal("80000"));
        regulator = regulatorRepository.saveAndFlush(regulator);

        GasPlanSaveRequest req = new GasPlanSaveRequest();
        req.setPlanNo("GP-CC");
        req.setPlanDate(LocalDate.of(2026, 10, 6));
        req.setAreaDemandM3(new BigDecimal("100000"));
        req.setDemandSource(Dict.DEMAND_METER_SNAPSHOT);
        req.setOperator("编制人甲");
        req.setAllocations(List.of(
                allocation(Dict.GP_GATE, gate.getId(), new BigDecimal("70000")),
                allocation(Dict.GP_REGULATOR, regulator.getId(), new BigDecimal("50000"))));
        return service.create(req).getPlan().getId();
    }

    private Long prepareWaitReview() {
        Long id = prepareDraft();
        service.submit(id, action("提交人甲", "进入复核"));
        return id;
    }

    private GasPlanSaveRequest.Allocation allocation(String type, Long id, BigDecimal quota) {
        GasPlanSaveRequest.Allocation a = new GasPlanSaveRequest.Allocation();
        a.setStationType(type);
        a.setStationId(id);
        a.setQuotaM3(quota);
        return a;
    }

    private GasPlanActionRequest action(String operator, String opinion) {
        GasPlanActionRequest r = new GasPlanActionRequest();
        r.setOperator(operator);
        r.setOpinion(opinion);
        return r;
    }

    private List<Outcome> runParallel(java.util.function.Supplier<Object> a,
                                      java.util.function.Supplier<Object> b) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Outcome>> futures = new ArrayList<>();
        futures.add(pool.submit(() -> runOne(a, ready, go)));
        futures.add(pool.submit(() -> runOne(b, ready, go)));
        ready.await(10, TimeUnit.SECONDS);
        go.countDown();
        List<Outcome> outcomes = new ArrayList<>();
        for (Future<Outcome> f : futures) {
            outcomes.add(f.get(60, TimeUnit.SECONDS));
        }
        pool.shutdown();
        return outcomes;
    }

    private Outcome runOne(java.util.function.Supplier<Object> job,
                           CountDownLatch ready, CountDownLatch go) {
        TenantContext.set(TENANT);
        ready.countDown();
        try {
            go.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            Object value = job.get();
            return new Outcome(true, String.valueOf(value), null);
        } catch (ConflictException e) {
            return new Outcome(false, null, e.getMessage());
        } catch (Exception e) {
            return new Outcome(false, null, "OTHER:" + e.getClass().getSimpleName() + ":" + e.getMessage());
        }
    }

    private record Outcome(boolean ok, String value, String error) {
    }
}
