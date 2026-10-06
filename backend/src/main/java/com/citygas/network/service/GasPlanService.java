package com.citygas.network.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.citygas.network.common.BizException;
import com.citygas.network.common.ConflictException;
import com.citygas.network.common.Dict;
import com.citygas.network.dto.GasPlanActionRequest;
import com.citygas.network.dto.GasPlanContextVO;
import com.citygas.network.dto.GasPlanCopyRequest;
import com.citygas.network.dto.GasPlanDetailVO;
import com.citygas.network.dto.GasPlanHistoryVO;
import com.citygas.network.dto.GasPlanQuotaDiffVO;
import com.citygas.network.dto.GasPlanQuotaVO;
import com.citygas.network.dto.GasPlanSaveRequest;
import com.citygas.network.dto.GasPlanValidationVO;
import com.citygas.network.dto.GasPlanVersionCompareVO;
import com.citygas.network.entity.GasPlanHistory;
import com.citygas.network.entity.GasPlanQuota;
import com.citygas.network.entity.GasSupplyPlan;
import com.citygas.network.entity.GateStation;
import com.citygas.network.entity.RegulatorStation;
import com.citygas.network.repository.GasPlanHistoryRepository;
import com.citygas.network.repository.GasPlanQuotaRepository;
import com.citygas.network.repository.GasSupplyPlanRepository;
import com.citygas.network.repository.GasUserRepository;
import com.citygas.network.repository.GateStationRepository;
import com.citygas.network.repository.RegulatorStationRepository;

/**
 * 门站日输气计划与供气配额编排。
 *
 * <p>版本状态：草稿 → 待复核 → 已提交 → 已冻结；复核驳回回到草稿。
 * 提交前强制校验需求来源、非负配额、单站能力与门站总进站能力；提交、复核、冻结均在
 * 行锁内完成，两名操作员并发推进同一版本时只有一人能写入，另一人收到 409。
 * 已提交/已冻结版本和配额、意见均不可变，只能复制为下一版草稿。</p>
 */
@Service
public class GasPlanService {

    private final GasSupplyPlanRepository planRepository;
    private final GasPlanQuotaRepository quotaRepository;
    private final GasPlanHistoryRepository historyRepository;
    private final GateStationRepository gateRepository;
    private final RegulatorStationRepository regulatorRepository;
    private final GasUserRepository userRepository;

    public GasPlanService(GasSupplyPlanRepository planRepository,
                          GasPlanQuotaRepository quotaRepository,
                          GasPlanHistoryRepository historyRepository,
                          GateStationRepository gateRepository,
                          RegulatorStationRepository regulatorRepository,
                          GasUserRepository userRepository) {
        this.planRepository = planRepository;
        this.quotaRepository = quotaRepository;
        this.historyRepository = historyRepository;
        this.gateRepository = gateRepository;
        this.regulatorRepository = regulatorRepository;
        this.userRepository = userRepository;
    }

    // ==================== 查询 ====================

    @Transactional(readOnly = true)
    public Page<GasSupplyPlan> search(LocalDate planDate, String status, String keyword, int page, int size) {
        if (status != null) {
            status = normalize(status);
            if (status != null && !Dict.isValidGasPlanStatus(status)) {
                throw new BizException("计划状态不合法：" + status);
            }
        }
        keyword = normalize(keyword);
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        return planRepository.search(planDate, status, keyword, pageable);
    }

    @Transactional(readOnly = true)
    public GasPlanContextVO context(LocalDate date) {
        LocalDate planDate = date == null ? LocalDate.now() : date;
        List<GateStation> gates = gateRepository.findAll();
        List<RegulatorStation> regulators = regulatorRepository.findAll();
        BigDecimal meterDemand = userRepository.findAll().stream()
                .map(u -> nz(u.getCurrentReading()).subtract(nz(u.getLastReading())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        GasPlanContextVO vo = new GasPlanContextVO();
        vo.setGateStations(gates);
        vo.setRegulatorStations(regulators);
        vo.setAreaDemandM3(meterDemand.max(BigDecimal.ZERO));
        vo.setDemandSource(Dict.DEMAND_METER_SNAPSHOT);
        vo.setDemandSourceLabel(Dict.demandSourceLabel(Dict.DEMAND_METER_SNAPSHOT));
        vo.setPlanDate(planDate);
        return vo;
    }

    @Transactional(readOnly = true)
    public GasPlanDetailVO detail(Long id) {
        GasSupplyPlan plan = getPlan(id);
        List<GasPlanQuota> quotas = quotaRepository.findByVersion(id);
        List<GasPlanHistory> histories = historyRepository.findByVersion(id);
        GasSupplyPlan latest = planRepository.findFirstByPlanNoOrderByPlanVersionDesc(plan.getPlanNo())
                .filter(p -> !p.getId().equals(plan.getId())).orElse(null);

        GasPlanDetailVO vo = new GasPlanDetailVO();
        vo.setPlan(plan);
        boolean enforceCurrentStations = Dict.GP_DRAFT.equals(plan.getStatus());
        vo.setQuotas(toQuotaVos(quotas, gates(), regulators(), false));
        vo.setHistories(histories);
        vo.setValidation(validate(plan, quotas, gates(), regulators(), enforceCurrentStations));
        vo.setLatestVersion(latest);
        return vo;
    }

    @Transactional(readOnly = true)
    public GasPlanHistoryVO history(Long id) {
        GasSupplyPlan plan = getPlan(id);
        List<GasSupplyPlan> versions = planRepository.findByPlanNoOrderByVersion(plan.getPlanNo());
        List<Long> ids = versions.stream().map(GasSupplyPlan::getId).toList();
        GasPlanHistoryVO vo = new GasPlanHistoryVO();
        vo.setVersions(versions);
        vo.setHistories(historyRepository.findByVersionIds(ids));
        return vo;
    }

    @Transactional(readOnly = true)
    public GasPlanVersionCompareVO compare(Long fromId, Long toId) {
        GasSupplyPlan from = getPlan(fromId);
        GasSupplyPlan to = getPlan(toId);
        if (!Objects.equals(from.getPlanNo(), to.getPlanNo())) {
            throw new BizException("只能比较同一计划编号下的版本");
        }
        Map<String, GasPlanQuota> fromMap = quotaMap(quotaRepository.findByVersion(fromId));
        Map<String, GasPlanQuota> toMap = quotaMap(quotaRepository.findByVersion(toId));
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(fromMap.keySet());
        keys.addAll(toMap.keySet());

        List<GasPlanQuotaDiffVO> diffs = new ArrayList<>();
        for (String key : keys) {
            GasPlanQuota a = fromMap.get(key);
            GasPlanQuota b = toMap.get(key);
            BigDecimal av = a == null ? null : nz(a.getQuotaM3());
            BigDecimal bv = b == null ? null : nz(b.getQuotaM3());
            GasPlanQuota sample = b != null ? b : a;
            GasPlanQuotaDiffVO d = new GasPlanQuotaDiffVO();
            d.setKey(key);
            d.setStationType(sample.getStationType());
            d.setStationId(sample.getStationId());
            d.setStationCode(sample.getStationCode());
            d.setStationName(sample.getStationName());
            d.setCapacityM3(sample.getCapacityM3());
            d.setFromQuotaM3(av);
            d.setToQuotaM3(bv);
            d.setDeltaM3(nz(bv).subtract(nz(av)));
            if (a == null) {
                d.setChangeType("新增");
            } else if (b == null) {
                d.setChangeType("删除");
            } else if (d.getDeltaM3().signum() == 0) {
                d.setChangeType("不变");
            } else {
                d.setChangeType("调整");
            }
            diffs.add(d);
        }

        GasPlanVersionCompareVO vo = new GasPlanVersionCompareVO();
        vo.setFromVersion(from);
        vo.setToVersion(to);
        vo.setFromAreaDemandM3(from.getAreaDemandM3());
        vo.setToAreaDemandM3(to.getAreaDemandM3());
        vo.setAreaDemandDeltaM3(nz(to.getAreaDemandM3()).subtract(nz(from.getAreaDemandM3())));
        vo.setQuotaDiffs(diffs);
        vo.setToHistories(historyRepository.findByVersion(toId));
        return vo;
    }

    @Transactional(readOnly = true)
    public GasPlanValidationVO validateForApi(Long id) {
        GasSupplyPlan plan = getPlan(id);
        boolean enforceCurrentStations = Dict.GP_DRAFT.equals(plan.getStatus());
        return validate(plan, quotaRepository.findByVersion(id), gates(), regulators(),
                enforceCurrentStations);
    }

    // ==================== 草稿 ====================

    @Transactional
    public GasPlanDetailVO create(GasPlanSaveRequest req) {
        String planNo = required(req.getPlanNo(), "计划编号不能为空");
        LocalDateTime now = LocalDateTime.now();
        String operator = required(req.getOperator(), "操作人不能为空");
        Integer next = planRepository.maxVersion(planNo) + 1;
        if (next != 1) {
            throw new BizException("计划编号已存在，请从已提交或已冻结版本复制生成新版本");
        }

        GasSupplyPlan plan = new GasSupplyPlan();
        plan.setPlanNo(planNo);
        plan.setPlanVersion(next);
        plan.setPlanDate(req.getPlanDate() == null ? LocalDate.now() : req.getPlanDate());
        plan.setAreaDemandM3(req.getAreaDemandM3() == null ? BigDecimal.ZERO : req.getAreaDemandM3());
        plan.setDemandSource(normalize(req.getDemandSource()));
        plan.setDemandReference(normalize(req.getDemandReference()));
        plan.setChangeNote(normalize(req.getChangeNote()));
        plan.setStatus(Dict.GP_DRAFT);
        plan.setCreatedBy(operator);
        plan.setUpdatedBy(operator);
        plan.setCreatedAt(now);
        plan.setUpdatedAt(now);
        plan = planRepository.saveAndFlush(plan);

        replaceQuotas(plan, req.getAllocations(), now);
        addHistory(plan.getId(), Dict.GP_ACTION_CREATE, null, Dict.GP_DRAFT, operator,
                firstText(plan.getChangeNote(), "创建 v" + plan.getPlanVersion() + " 草稿"), now);
        return detail(plan.getId());
    }

    @Transactional
    public GasPlanDetailVO update(Long id, GasPlanSaveRequest req) {
        GasSupplyPlan plan = lockPlan(id);
        if (!Dict.GP_DRAFT.equals(plan.getStatus())) {
            throw new ConflictException("当前版本为「" + plan.getStatus()
                    + "」，配额已锁定；请复制生成新版本后再调整");
        }
        String planNo = required(req.getPlanNo(), "计划编号不能为空");
        if (!Objects.equals(plan.getPlanNo(), planNo)) {
            throw new BizException("计划编号不能跨版本修改");
        }
        LocalDateTime now = LocalDateTime.now();
        String operator = required(req.getOperator(), "操作人不能为空");
        plan.setPlanDate(req.getPlanDate() == null ? plan.getPlanDate() : req.getPlanDate());
        plan.setAreaDemandM3(req.getAreaDemandM3() == null ? BigDecimal.ZERO : req.getAreaDemandM3());
        plan.setDemandSource(normalize(req.getDemandSource()));
        plan.setDemandReference(normalize(req.getDemandReference()));
        plan.setChangeNote(normalize(req.getChangeNote()));
        plan.setUpdatedBy(operator);
        plan.setUpdatedAt(now);
        planRepository.saveAndFlush(plan);

        replaceQuotas(plan, req.getAllocations(), now);
        addHistory(id, Dict.GP_ACTION_UPDATE, Dict.GP_DRAFT, Dict.GP_DRAFT, operator,
                firstText(plan.getChangeNote(), "调整门站/调压站配额"), now);
        return detail(id);
    }

    // ==================== 状态推进 ====================

    @Transactional
    public GasPlanDetailVO submit(Long id, GasPlanActionRequest req) {
        String operator = required(req.getOperator(), "操作人不能为空");
        GasSupplyPlan plan = lockPlan(id);
        if (!Dict.GP_DRAFT.equals(plan.getStatus())) {
            throw new ConflictException("只有草稿版本可以提交复核，当前状态：" + plan.getStatus());
        }
        List<GasPlanQuota> quotas = quotaRepository.findByVersion(id);
        GasPlanValidationVO validation = validate(plan, quotas, gates(), regulators(), true);
        if (!validation.isValidForSubmit()) {
            throw new BizException("提交被拦截：" + String.join("；", validation.getErrors()));
        }
        LocalDateTime now = LocalDateTime.now();
        plan.setStatus(Dict.GP_WAIT_REVIEW);
        plan.setSubmittedAt(now);
        plan.setSubmittedBy(operator);
        plan.setReviewOpinion(null);
        plan.setReviewedAt(null);
        plan.setReviewedBy(null);
        plan.setUpdatedBy(operator);
        plan.setUpdatedAt(now);
        planRepository.saveAndFlush(plan);
        addHistory(id, Dict.GP_ACTION_SUBMIT, Dict.GP_DRAFT, Dict.GP_WAIT_REVIEW, operator,
                firstText(req.getOpinion(), "提交复核"), now);
        return detail(id);
    }

    @Transactional
    public GasPlanDetailVO approve(Long id, GasPlanActionRequest req) {
        GasSupplyPlan plan = lockPlan(id);
        String operator = required(req.getOperator(), "操作人不能为空");
        if (!Dict.GP_WAIT_REVIEW.equals(plan.getStatus())) {
            throw new ConflictException("只有待复核版本可以复核，当前状态：" + plan.getStatus());
        }
        String opinion = required(req.getOpinion(), "复核意见不能为空");
        LocalDateTime now = LocalDateTime.now();
        plan.setStatus(Dict.GP_SUBMITTED);
        plan.setReviewedAt(now);
        plan.setReviewedBy(operator);
        plan.setReviewOpinion(opinion);
        plan.setUpdatedBy(operator);
        plan.setUpdatedAt(now);
        planRepository.saveAndFlush(plan);
        addHistory(id, Dict.GP_ACTION_APPROVE, Dict.GP_WAIT_REVIEW, Dict.GP_SUBMITTED, operator, opinion, now);
        return detail(id);
    }

    @Transactional
    public GasPlanDetailVO reject(Long id, GasPlanActionRequest req) {
        GasSupplyPlan plan = lockPlan(id);
        String operator = required(req.getOperator(), "操作人不能为空");
        if (!Dict.GP_WAIT_REVIEW.equals(plan.getStatus())) {
            throw new ConflictException("只有待复核版本可以驳回，当前状态：" + plan.getStatus());
        }
        String opinion = required(req.getOpinion(), "驳回原因不能为空");
        LocalDateTime now = LocalDateTime.now();
        plan.setStatus(Dict.GP_DRAFT);
        plan.setReviewedAt(now);
        plan.setReviewedBy(operator);
        plan.setReviewOpinion(opinion);
        plan.setUpdatedBy(operator);
        plan.setUpdatedAt(now);
        planRepository.saveAndFlush(plan);
        addHistory(id, Dict.GP_ACTION_REJECT, Dict.GP_WAIT_REVIEW, Dict.GP_DRAFT, operator, opinion, now);
        return detail(id);
    }

    @Transactional
    public GasPlanDetailVO freeze(Long id, GasPlanActionRequest req) {
        GasSupplyPlan plan = lockPlan(id);
        String operator = required(req.getOperator(), "操作人不能为空");
        if (!Dict.GP_SUBMITTED.equals(plan.getStatus())) {
            throw new ConflictException("只有已提交版本可以冻结，当前状态：" + plan.getStatus());
        }
        String opinion = required(req.getOpinion(), "冻结说明不能为空");
        LocalDateTime now = LocalDateTime.now();
        plan.setStatus(Dict.GP_FROZEN);
        plan.setFrozenAt(now);
        plan.setFrozenBy(operator);
        plan.setReviewOpinion(plan.getReviewOpinion() == null ? opinion : plan.getReviewOpinion() + "；冻结：" + opinion);
        plan.setUpdatedBy(operator);
        plan.setUpdatedAt(now);
        planRepository.saveAndFlush(plan);
        addHistory(id, Dict.GP_ACTION_FREEZE, Dict.GP_SUBMITTED, Dict.GP_FROZEN, operator, opinion, now);
        return detail(id);
    }

    @Transactional
    public GasPlanDetailVO copy(Long id, GasPlanCopyRequest req) {
        GasSupplyPlan source = lockPlan(id);
        if (!Dict.GP_SUBMITTED.equals(source.getStatus()) && !Dict.GP_FROZEN.equals(source.getStatus())) {
            throw new ConflictException("只有已提交或已冻结版本可以复制生成新版本，当前状态：" + source.getStatus());
        }
        String operator = required(req.getOperator(), "操作人不能为空");
        LocalDateTime now = LocalDateTime.now();
        Integer nextVersion = planRepository.maxVersion(source.getPlanNo()) + 1;

        GasSupplyPlan copy = new GasSupplyPlan();
        copy.setPlanNo(source.getPlanNo());
        copy.setPlanVersion(nextVersion);
        copy.setPlanDate(source.getPlanDate());
        copy.setAreaDemandM3(source.getAreaDemandM3());
        copy.setDemandSource(source.getDemandSource());
        copy.setDemandReference(source.getDemandReference());
        copy.setChangeNote(normalize(req.getChangeNote()));
        copy.setStatus(Dict.GP_DRAFT);
        copy.setSourceVersionId(source.getId());
        copy.setCreatedBy(operator);
        copy.setUpdatedBy(operator);
        copy.setCreatedAt(now);
        copy.setUpdatedAt(now);
        copy = planRepository.saveAndFlush(copy);

        Map<Long, GateStation> currentGates = gates().stream()
                .collect(Collectors.toMap(GateStation::getId, g -> g));
        Map<Long, RegulatorStation> currentRegulators = regulators().stream()
                .collect(Collectors.toMap(RegulatorStation::getId, r -> r));
        for (GasPlanQuota old : quotaRepository.findByVersion(id)) {
            GateStation currentGate = Dict.GP_GATE.equals(old.getStationType())
                    ? currentGates.get(old.getStationId()) : null;
            RegulatorStation currentRegulator = Dict.GP_REGULATOR.equals(old.getStationType())
                    ? currentRegulators.get(old.getStationId()) : null;
            if ((Dict.GP_GATE.equals(old.getStationType()) && currentGate == null)
                    || (Dict.GP_REGULATOR.equals(old.getStationType()) && currentRegulator == null)) {
                throw new ConflictException("站点「" + old.getStationName()
                        + "」不属于当前片区或已删除，不能复制；历史版本仍保留原配额");
            }
            GasPlanQuota q = new GasPlanQuota();
            q.setPlanVersionId(copy.getId());
            q.setStationType(old.getStationType());
            q.setStationId(old.getStationId());
            q.setStationCode(old.getStationCode());
            q.setStationName(old.getStationName());
            q.setDailyFlowM3(currentGate != null ? nz(currentGate.getDailyFlowM3()) : null);
            q.setCapacityM3(currentGate != null
                    ? nz(currentGate.getInletCapacityM3()) : nz(currentRegulator.getOutletCapacityM3()));
            q.setQuotaM3(old.getQuotaM3());
            q.setCreatedAt(now);
            q.setUpdatedAt(now);
            quotaRepository.save(q);
        }
        Set<Long> copiedGateIds = quotaRepository.findByVersion(copy.getId()).stream()
                .filter(q -> Dict.GP_GATE.equals(q.getStationType()))
                .map(GasPlanQuota::getStationId).collect(Collectors.toSet());
        for (GateStation g : currentGates.values()) {
            if (!copiedGateIds.contains(g.getId())) {
                quotaRepository.save(newQuotaRow(copy, Dict.GP_GATE, g.getId(), g.getStationCode(),
                        g.getStationName(), nz(g.getDailyFlowM3()), nz(g.getInletCapacityM3()), now));
            }
        }
        Set<Long> copiedRegulatorIds = quotaRepository.findByVersion(copy.getId()).stream()
                .filter(q -> Dict.GP_REGULATOR.equals(q.getStationType()))
                .map(GasPlanQuota::getStationId).collect(Collectors.toSet());
        for (RegulatorStation r : currentRegulators.values()) {
            if (!copiedRegulatorIds.contains(r.getId())) {
                quotaRepository.save(newQuotaRow(copy, Dict.GP_REGULATOR, r.getId(), r.getStationCode(),
                        r.getStationName(), null, nz(r.getOutletCapacityM3()), now));
            }
        }
        addHistory(copy.getId(), Dict.GP_ACTION_COPY, null, Dict.GP_DRAFT, operator,
                "从 v" + source.getPlanVersion() + "（" + source.getStatus() + "）复制"
                        + firstText(copy.getChangeNote(), ""), now);
        return detail(copy.getId());
    }

    // ==================== 校验与配额落库 ====================

    private GasPlanQuota newQuotaRow(GasSupplyPlan plan, String stationType, Long stationId,
                                     String code, String name, BigDecimal dailyFlow,
                                     BigDecimal capacity, LocalDateTime now) {
        GasPlanQuota q = new GasPlanQuota();
        q.setPlanVersionId(plan.getId());
        q.setStationType(stationType);
        q.setStationId(stationId);
        q.setStationCode(code);
        q.setStationName(name);
        q.setDailyFlowM3(dailyFlow);
        q.setCapacityM3(capacity);
        q.setQuotaM3(BigDecimal.ZERO);
        q.setCreatedAt(now);
        q.setUpdatedAt(now);
        return q;
    }

    private void replaceQuotas(GasSupplyPlan plan, List<GasPlanSaveRequest.Allocation> input, LocalDateTime now) {
        List<GasPlanSaveRequest.Allocation> allocations = input == null ? List.of() : input;
        quotaRepository.deleteByVersion(plan.getId());
        quotaRepository.flush();

        List<GateStation> gates = gates();
        List<RegulatorStation> regulators = regulators();
        Map<Long, GateStation> gateMap = gates.stream().collect(Collectors.toMap(GateStation::getId, g -> g));
        Map<Long, RegulatorStation> regulatorMap = regulators.stream()
                .collect(Collectors.toMap(RegulatorStation::getId, r -> r));
        Set<String> seen = new HashSet<>();
        List<GasPlanQuota> rows = new ArrayList<>();

        for (GasPlanSaveRequest.Allocation a : allocations) {
            if (a == null || a.getStationType() == null || a.getStationId() == null) {
                throw new BizException("配额站点类型和站点 ID 不能为空");
            }
            String type = a.getStationType().trim();
            if (!Dict.GP_GATE.equals(type) && !Dict.GP_REGULATOR.equals(type)) {
                throw new BizException("配额站点类型不合法：" + type);
            }
            String key = type + ":" + a.getStationId();
            if (!seen.add(key)) {
                throw new BizException("同一站点不能重复编排配额");
            }
            BigDecimal quota = a.getQuotaM3() == null ? BigDecimal.ZERO : a.getQuotaM3();
            GasPlanQuota q = new GasPlanQuota();
            q.setPlanVersionId(plan.getId());
            q.setStationType(type);
            q.setStationId(a.getStationId());
            if (Dict.GP_GATE.equals(type)) {
                GateStation g = Optional.ofNullable(gateMap.get(a.getStationId()))
                        .orElseThrow(() -> new BizException("门站不存在或不属于当前片区：" + a.getStationId()));
                q.setStationCode(g.getStationCode());
                q.setStationName(g.getStationName());
                q.setDailyFlowM3(nz(g.getDailyFlowM3()));
                q.setCapacityM3(nz(g.getInletCapacityM3()));
            } else {
                RegulatorStation r = Optional.ofNullable(regulatorMap.get(a.getStationId()))
                        .orElseThrow(() -> new BizException("调压站不存在或不属于当前片区：" + a.getStationId()));
                q.setStationCode(r.getStationCode());
                q.setStationName(r.getStationName());
                q.setDailyFlowM3(null);
                q.setCapacityM3(nz(r.getOutletCapacityM3()));
            }
            q.setQuotaM3(quota);
            q.setCreatedAt(now);
            q.setUpdatedAt(now);
            rows.add(q);
        }
        quotaRepository.saveAll(rows);
    }

    private GasPlanValidationVO validate(GasSupplyPlan plan, List<GasPlanQuota> quotas,
                                         List<GateStation> gates, List<RegulatorStation> regulators,
                                         boolean enforceCurrentStations) {
        GasPlanValidationVO vo = new GasPlanValidationVO();
        vo.setDemandSourceLabel(Dict.demandSourceLabel(plan.getDemandSource()));
        List<String> errors = vo.getErrors();

        boolean missingSource = normalize(plan.getDemandSource()) == null;
        if (missingSource) {
            errors.add("缺少需求来源");
        } else if (!Dict.isValidDemandSource(plan.getDemandSource())) {
            errors.add("需求来源不合法：" + plan.getDemandSource());
        }
        if (plan.getAreaDemandM3() == null || plan.getAreaDemandM3().signum() < 0) {
            errors.add("片区需求量不能为负");
        }

        Map<Long, GateStation> gateMap = gates.stream().collect(Collectors.toMap(GateStation::getId, g -> g));
        Map<Long, RegulatorStation> regulatorMap = regulators.stream()
                .collect(Collectors.toMap(RegulatorStation::getId, r -> r));
        Set<Long> gateIds = new HashSet<>();
        Set<Long> regulatorIds = new HashSet<>();
        BigDecimal gateTotal = BigDecimal.ZERO;
        BigDecimal regulatorTotal = BigDecimal.ZERO;
        boolean negative = false;
        boolean missingStation = enforceCurrentStations && (gates.isEmpty() || regulators.isEmpty());

        for (GasPlanQuota q : quotas) {
            BigDecimal quota = nz(q.getQuotaM3());
            if (quota.signum() < 0) {
                negative = true;
                errors.add(q.getStationName() + " 配额不能为负");
            }
            if (Dict.GP_GATE.equals(q.getStationType())) {
                GateStation g = gateMap.get(q.getStationId());
                if (g == null) {
                    if (enforceCurrentStations) {
                        missingStation = true;
                        errors.add(q.getStationName() + " 不属于当前片区或已删除");
                    }
                } else {
                    gateIds.add(g.getId());
                    BigDecimal capacityLimit = enforceCurrentStations
                            ? nz(g.getInletCapacityM3()) : nz(q.getCapacityM3());
                    if (quota.compareTo(capacityLimit) > 0) {
                        errors.add("门站「" + g.getStationName() + "」配额超过进站能力");
                    }
                }
                gateTotal = gateTotal.add(quota);
            } else {
                RegulatorStation r = regulatorMap.get(q.getStationId());
                if (r == null) {
                    if (enforceCurrentStations) {
                        missingStation = true;
                        errors.add(q.getStationName() + " 不属于当前片区或已删除");
                    }
                } else {
                    regulatorIds.add(r.getId());
                    BigDecimal capacityLimit = enforceCurrentStations
                            ? nz(r.getOutletCapacityM3()) : nz(q.getCapacityM3());
                    if (quota.compareTo(capacityLimit) > 0) {
                        errors.add("调压站「" + r.getStationName() + "」配额超过出口上限");
                    }
                }
                regulatorTotal = regulatorTotal.add(quota);
            }
        }

        if (enforceCurrentStations && gates.stream().anyMatch(g -> !gateIds.contains(g.getId()))) {
            missingStation = true;
            String names = gates.stream().filter(g -> !gateIds.contains(g.getId()))
                    .map(GateStation::getStationName).collect(Collectors.joining("、"));
            errors.add("缺少门站配额：" + names);
        }
        if (enforceCurrentStations && regulators.stream().anyMatch(r -> !regulatorIds.contains(r.getId()))) {
            missingStation = true;
            String names = regulators.stream().filter(r -> !regulatorIds.contains(r.getId()))
                    .map(RegulatorStation::getStationName).collect(Collectors.joining("、"));
            errors.add("缺少调压站配额：" + names);
        }

        BigDecimal gateCapacity = enforceCurrentStations
                ? gates.stream().map(GateStation::getInletCapacityM3)
                        .reduce(BigDecimal.ZERO, (a, b) -> a.add(nz(b)))
                : quotas.stream().filter(q -> Dict.GP_GATE.equals(q.getStationType()))
                        .map(q -> nz(q.getCapacityM3())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal regulatorCapacity = enforceCurrentStations
                ? regulators.stream().map(RegulatorStation::getOutletCapacityM3)
                        .reduce(BigDecimal.ZERO, (a, b) -> a.add(nz(b)))
                : quotas.stream().filter(q -> Dict.GP_REGULATOR.equals(q.getStationType()))
                        .map(q -> nz(q.getCapacityM3())).reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean gateTotalOver = gateTotal.compareTo(gateCapacity) > 0;
        if (gateTotalOver) {
            errors.add("门站配额合计 " + gateTotal + " m³ 超过进站能力合计 " + gateCapacity + " m³");
        }
        if (regulatorTotal.compareTo(regulatorCapacity) > 0) {
            errors.add("调压站配额合计超过出口能力合计");
        }
        if (regulatorTotal.compareTo(gateTotal) > 0) {
            vo.getWarnings().add("调压站配额合计大于门站配额合计，请核对输配平衡");
        }
        BigDecimal demand = nz(plan.getAreaDemandM3());
        if (gateTotal.compareTo(demand) < 0) {
            vo.getWarnings().add("门站配额合计小于片区需求，可能无法满足当日供气");
        }

        vo.setGateTotalQuotaM3(gateTotal);
        vo.setGateTotalCapacityM3(gateCapacity);
        vo.setRegulatorTotalQuotaM3(regulatorTotal);
        vo.setRegulatorTotalCapacityM3(regulatorCapacity);
        vo.setGateTotalOverCapacity(gateTotalOver);
        vo.setMissingDemandSource(missingSource);
        vo.setHasNegativeQuota(negative);
        vo.setMissingStation(missingStation);
        vo.setValidForSubmit(errors.isEmpty());
        return vo;
    }

    private List<GasPlanQuotaVO> toQuotaVos(List<GasPlanQuota> rows, List<GateStation> gates,
                                            List<RegulatorStation> regulators, boolean markOverByCurrent) {
        Map<Long, GateStation> gateMap = gates.stream().collect(Collectors.toMap(GateStation::getId, g -> g));
        Map<Long, RegulatorStation> regulatorMap = regulators.stream()
                .collect(Collectors.toMap(RegulatorStation::getId, r -> r));
        return rows.stream().map(q -> {
            GasPlanQuotaVO vo = new GasPlanQuotaVO();
            vo.setId(q.getId());
            vo.setPlanVersionId(q.getPlanVersionId());
            vo.setStationType(q.getStationType());
            vo.setStationId(q.getStationId());
            vo.setStationCode(q.getStationCode());
            vo.setStationName(q.getStationName());
            vo.setDailyFlowM3(q.getDailyFlowM3());
            vo.setCapacityM3(q.getCapacityM3());
            vo.setQuotaM3(q.getQuotaM3());
            BigDecimal currentCapacity = q.getCapacityM3();
            if (markOverByCurrent) {
                if (Dict.GP_GATE.equals(q.getStationType()) && gateMap.containsKey(q.getStationId())) {
                    currentCapacity = nz(gateMap.get(q.getStationId()).getInletCapacityM3());
                }
                if (Dict.GP_REGULATOR.equals(q.getStationType()) && regulatorMap.containsKey(q.getStationId())) {
                    currentCapacity = nz(regulatorMap.get(q.getStationId()).getOutletCapacityM3());
                }
            }
            vo.setNegative(nz(q.getQuotaM3()).signum() < 0);
            vo.setOverCapacity(nz(q.getQuotaM3()).compareTo(nz(currentCapacity)) > 0);
            return vo;
        }).toList();
    }

    private void addHistory(Long versionId, String action, String from, String to,
                            String operator, String note, LocalDateTime at) {
        GasPlanHistory h = new GasPlanHistory();
        h.setPlanVersionId(versionId);
        h.setAction(action);
        h.setFromStatus(from);
        h.setToStatus(to);
        h.setOperatorName(operator);
        h.setNote(normalize(note));
        h.setCreatedAt(at);
        historyRepository.save(h);
    }

    private GasSupplyPlan getPlan(Long id) {
        return planRepository.findScopedById(id)
                .orElseThrow(() -> new BizException("供气计划版本不存在：" + id));
    }

    private GasSupplyPlan lockPlan(Long id) {
        try {
            return planRepository.findByIdForUpdate(id)
                    .orElseThrow(() -> new BizException("供气计划版本不存在：" + id));
        } catch (PessimisticLockingFailureException | ObjectOptimisticLockingFailureException e) {
            throw new ConflictException("该计划正由其他操作员复核或提交，请刷新后重试；您的配额、意见和状态均未改写");
        }
    }

    private List<GateStation> gates() {
        return gateRepository.findAll();
    }

    private List<RegulatorStation> regulators() {
        return regulatorRepository.findAll();
    }

    private Map<String, GasPlanQuota> quotaMap(List<GasPlanQuota> quotas) {
        Map<String, GasPlanQuota> map = new LinkedHashMap<>();
        for (GasPlanQuota q : quotas) {
            map.put(q.getStationType() + ":" + q.getStationId(), q);
        }
        return map;
    }

    private BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private String normalize(String s) {
        if (s == null) {
            return null;
        }
        String v = s.trim();
        return v.isEmpty() ? null : v;
    }

    private String required(String s, String message) {
        String v = normalize(s);
        if (v == null) {
            throw new BizException(message);
        }
        return v;
    }

    private String firstText(String a, String b) {
        return normalize(a) != null ? a : b;
    }
}
