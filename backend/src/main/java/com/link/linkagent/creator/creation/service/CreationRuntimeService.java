package com.link.linkagent.creator.creation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.creation.mapper.CreationRuntimeMapper;
import com.link.linkagent.creator.creation.mapper.CreationWorkspaceMapper;
import com.link.linkagent.creator.creation.model.CreationRuntimeModels.*;
import com.link.linkagent.creator.media.upload.mapper.MediaUploadMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import static com.link.linkagent.creator.creation.service.CreationInputRules.*;

/** 所有写入先锁任务；租约只保护执行结果，事实费用允许在取消后补记。 */
@Service
@Transactional
public class CreationRuntimeService {
    private final MediaUploadMapper media;
    private final CreationRuntimeMapper mapper;
    private final CreationWorkspaceMapper workspace;
    private final ObjectMapper json;

    public CreationRuntimeService(MediaUploadMapper media, CreationRuntimeMapper mapper,
                                  CreationWorkspaceMapper workspace, ObjectMapper json) {
        this.media = media; this.mapper = mapper; this.workspace = workspace; this.json = json;
    }

    public Snapshot initialize(String taskId, String ownerId, String key, long expectedRevision, BigDecimal amount) {
        lock(taskId, ownerId); key(key); amount = money(amount, false);
        String requestHash = hash(amount.toPlainString() + ":" + expectedRevision);
        var existing = mapper.workspace(taskId, ownerId);
        if (existing.isPresent() && existing.get().budgetLimitCny() != null) {
            if (!key.equals(existing.get().budgetInitKey()) || !requestHash.equals(existing.get().budgetInitHash()))
                throw conflict("预算已初始化，请刷新后使用预算调整接口");
            return snapshot(taskId, ownerId);
        }
        if (expectedRevision < -1 || existing.map(Workspace::workspaceRevision).orElse(-1L) != expectedRevision)
            throw conflict("工作区已变化，请刷新后初始化预算");
        if (existing.isEmpty()) media.adoptMediaWorkspace(taskId, ownerId);
        var current = mapper.workspace(taskId, ownerId).orElseThrow();
        if (mapper.initialize(taskId, ownerId, amount, key, requestHash, current.workspaceRevision()) != 1)
            throw conflict("工作区已变化，请刷新后初始化预算");
        return snapshot(taskId, ownerId);
    }

    public Snapshot updateBudget(String taskId, String ownerId, long expectedRevision, BigDecimal amount) {
        lock(taskId, ownerId); var current = initialized(taskId, ownerId); revision(current, expectedRevision);
        amount = money(amount, false);
        if (amount.compareTo(mapper.totals(taskId, ownerId).occupied()) < 0)
            throw conflict("预算不能低于已结算、预留与待核实费用之和");
        if (mapper.budget(taskId, ownerId, amount, expectedRevision) != 1) throw conflict("工作区已变化");
        return snapshot(taskId, ownerId);
    }

    @Transactional(readOnly = true)
    public Snapshot snapshot(String taskId, String ownerId) {
        requireTask(taskId, ownerId);
        var current = mapper.workspace(taskId, ownerId);
        if (current.isEmpty()) return new Snapshot(false, -1, null, null, false, "CREATION_RUNTIME_ONLY");
        var value = current.get();
        RunView active = value.activeRunId() == null ? null : RunView.from(run(taskId, ownerId, value.activeRunId()));
        Budget budget = null;
        if (value.budgetLimitCny() != null) {
            var totals = mapper.totals(taskId, ownerId);
            budget = new Budget(value.budgetLimitCny(), totals.knownCny(), totals.reservedCny(), totals.unknownCny(),
                    value.budgetLimitCny().subtract(totals.occupied()).max(BigDecimal.ZERO),
                    blocked(totals) || totals.occupied().compareTo(value.budgetLimitCny()) >= 0);
        }
        // 执行器未接入前，初始化预算不代表可以自动制作，也不代表授权扣费。
        return new Snapshot(budget != null, value.workspaceRevision(), budget, active, false, "CREATION_RUNTIME_ONLY");
    }

    @Transactional(readOnly = true)
    public RunView getRun(String taskId, String ownerId, String runId) {
        requireTask(taskId, ownerId); return RunView.from(run(taskId, ownerId, runId));
    }

    /** 仅供后续真实确认流程调用；准备授权不能被用于渲染或整片制作。 */
    public Approval approvePreparation(String taskId, String ownerId, String key, long expectedRevision,
                                       String requestJson, BigDecimal maxCost) {
        lock(taskId, ownerId);
        // 在第一次快照读前等媒体工作释放版本锁，避免可重复读漏掉刚提交的子任务。
        media.lockTaskDraftVersions(taskId, ownerId);
        key(key); String input = canonical(json, requestJson);
        maxCost = money(maxCost, true);
        String fingerprint = hash(input);
        String requestHash = hash(fingerprint + ":" + maxCost.toPlainString() + ":" + expectedRevision);
        var existing = mapper.approvalByKey(taskId, ownerId, key);
        if (existing.isPresent()) {
            if (!existing.get().requestHash().equals(requestHash)) throw conflict("相同幂等键对应的授权内容不同");
            return existing.get();
        }
        var current = initialized(taskId, ownerId); revision(current, expectedRevision); idle(taskId, ownerId, current);
        if (maxCost.compareTo(current.budgetLimitCny()) > 0) throw conflict("授权费用不能超过任务预算");
        var approval = new Approval(newId(), taskId, ownerId, "PREPARATION", fingerprint, maxCost, key,
                requestHash, "ACTIVE", null);
        mapper.insertApproval(approval);
        if (mapper.bump(taskId, ownerId, expectedRevision) != 1) throw conflict("工作区已变化");
        return mapper.approval(taskId, ownerId, approval.approvalId()).orElseThrow();
    }

    public Run start(String taskId, String ownerId, String kind, String key, long expectedRevision,
                     String approvalId, String requestJson) {
        lock(taskId, ownerId);
        media.lockTaskDraftVersions(taskId, ownerId);
        return startLocked(taskId, ownerId, kind, key, expectedRevision, approvalId, requestJson, null);
    }

    /** 请求确认接入后以此为受理入口，授权与排队运行必须一起提交或回滚。 */
    public Run startPreparation(String taskId, String ownerId, String kind, String key, long expectedRevision,
                                String requestJson, BigDecimal maxCost) {
        key(key);
        var approval = approvePreparation(taskId, ownerId, "prepare-" + hash(key), expectedRevision, requestJson, maxCost);
        return startLocked(taskId, ownerId, kind, key, expectedRevision + 1, approval.approvalId(), requestJson, null);
    }

    public Run retry(String taskId, String ownerId, String originalRunId, String key, long expectedRevision) {
        lock(taskId, ownerId); media.lockTaskDraftVersions(taskId, ownerId);
        var original = run(taskId, ownerId, originalRunId);
        if (!Set.of("FAILED", "CANCELLED").contains(original.status())) throw conflict("仅失败或已取消的运行可以重试");
        return startLocked(taskId, ownerId, original.kind(), key, expectedRevision, original.approvalId(),
                original.requestJson(), original.runId());
    }

    private Run startLocked(String taskId, String ownerId, String kind, String key, long expectedRevision,
                            String approvalId, String requestJson, String parent) {
        key(key); id(approvalId);
        if (kind == null || !Set.of("TEXT", "PREPARE").contains(kind)) throw conflict("当前运行基础仅接受文稿或素材准备任务");
        String input = canonical(json, requestJson); String fingerprint = hash(input);
        String requestHash = hash(kind + ":" + fingerprint + ":" + approvalId + ":" + expectedRevision + ":" + parent);
        var previous = mapper.runByKey(taskId, ownerId, key);
        if (previous.isPresent()) {
            if (!previous.get().requestHash().equals(requestHash)) throw conflict("相同幂等键对应的运行内容不同");
            return previous.get();
        }
        var current = initialized(taskId, ownerId); revision(current, expectedRevision); idle(taskId, ownerId, current);
        if (media.countPublishingFacts(taskId, ownerId) > 0) throw conflict("已进入发布后流程，不能启动制作运行");
        var approval = approval(taskId, ownerId, approvalId);
        if (!"ACTIVE".equals(approval.status()) || !"PREPARATION".equals(approval.approvalKind())
                || !fingerprint.equals(approval.inputFingerprint())) throw conflict("授权已失效或与当前输入不一致");
        var value = new Run(newId(), taskId, ownerId, kind, "QUEUED", "QUEUED", parent, approvalId,
                approval.maxAdditionalCostCny(), fingerprint, input, key, requestHash, 0, null, 0, null, null,
                0, null, null, null, null, null);
        mapper.insertRun(value);
        if (mapper.activate(taskId, ownerId, value.runId(), expectedRevision) != 1) throw conflict("工作区已变化");
        return run(taskId, ownerId, value.runId());
    }

    public Optional<Run> claim(String taskId, String ownerId, String runId, String worker, int leaseSeconds) {
        lock(taskId, ownerId); id(worker); leaseSeconds(leaseSeconds);
        active(taskId, ownerId, runId);
        if (mapper.claim(taskId, ownerId, runId, worker, leaseSeconds) != 1) return Optional.empty();
        // 接管失联执行器时，未发送预留可以释放；已发送调用必须保留待核实费用。
        mapper.closeReservations(taskId, ownerId, runId);
        return Optional.of(run(taskId, ownerId, runId));
    }

    public boolean heartbeat(String taskId, String ownerId, String runId, String worker, long generation, int seconds) {
        lock(taskId, ownerId); id(worker); leaseSeconds(seconds);
        return mapper.heartbeat(taskId, ownerId, runId, worker, generation, seconds) == 1;
    }

    public RunView cancel(String taskId, String ownerId, String runId) {
        lock(taskId, ownerId); run(taskId, ownerId, runId);
        mapper.cancel(taskId, ownerId, runId);
        var current = run(taskId, ownerId, runId);
        if ("CANCELLED".equals(current.status())) close(taskId, ownerId, runId);
        return RunView.from(current);
    }

    public Run acknowledgeCancellation(String taskId, String ownerId, String runId, String worker, long generation) {
        return finish(taskId, ownerId, runId, worker, generation, "CANCEL_REQUESTED", "CANCELLED", null, null, null);
    }

    public Run complete(String taskId, String ownerId, String runId, String worker, long generation, String resultJson) {
        lock(taskId, ownerId);
        if (mapper.pendingReservations(taskId, ownerId, runId) > 0) throw conflict("请先结算或标记调用费用状态");
        return finish(taskId, ownerId, runId, worker, generation, "RUNNING", "COMPLETED", null,
                canonical(json, resultJson), null);
    }

    public Run fail(String taskId, String ownerId, String runId, String worker, long generation, String errorCode) {
        return finish(taskId, ownerId, runId, worker, generation, "RUNNING", "FAILED", null, null, id(errorCode));
    }

    public Run waitForDependency(String taskId, String ownerId, String runId, String worker, long generation, String dependencyId) {
        return finish(taskId, ownerId, runId, worker, generation, "RUNNING", "WAITING_DEPENDENCY", id(dependencyId), null, null);
    }

    public Run wake(String taskId, String ownerId, String runId, long expectedRunRevision) {
        lock(taskId, ownerId); active(taskId, ownerId, runId);
        if (mapper.wake(taskId, ownerId, runId, expectedRunRevision) != 1) throw conflict("运行已变化，不能重复唤醒");
        return run(taskId, ownerId, runId);
    }

    private Run finish(String taskId, String ownerId, String runId, String worker, long generation,
                       String expectedState, String state, String dependency, String result, String error) {
        lock(taskId, ownerId); id(worker); active(taskId, ownerId, runId);
        if (mapper.finish(taskId, ownerId, runId, worker, generation, expectedState, state, dependency, result, error) != 1)
            throw conflict("运行已取消、租约已过期或已被其他执行器接管");
        if (!"WAITING_DEPENDENCY".equals(state)) close(taskId, ownerId, runId);
        return run(taskId, ownerId, runId);
    }

    public Charge reserve(String taskId, String ownerId, String runId, String worker, long generation,
                          String chargeKey, String capability, BigDecimal amount, String priceJson) {
        lock(taskId, ownerId); key(chargeKey); id(capability); id(worker);
        amount = money(amount, false); String price = canonical(json, priceJson);
        var previous = mapper.charge(taskId, ownerId, chargeKey);
        if (previous.isPresent()) {
            var charge = previous.get();
            if (!runId.equals(charge.runId()) || generation != charge.leaseGeneration()
                    || !capability.equals(charge.capability()) || amount.compareTo(charge.reservedCny()) != 0
                    || !price.equals(charge.priceSnapshotJson())) throw conflict("调用标识已经用于其他费用预留");
            return charge;
        }
        var current = active(taskId, ownerId, runId); live(taskId, ownerId, runId, worker, generation);
        var run = run(taskId, ownerId, runId); var approval = approval(taskId, ownerId, run.approvalId());
        if (!"ACTIVE".equals(approval.status())) throw conflict("费用授权已撤销");
        var totals = mapper.totals(taskId, ownerId);
        if (blocked(totals)) throw conflict("存在待核实或超出预留的费用，暂停新增付费调用");
        if (totals.occupied().add(amount).compareTo(current.budgetLimitCny()) > 0) throw conflict("任务剩余预算不足");
        if (mapper.approvalTotals(taskId, ownerId, run.approvalId()).occupied().add(amount)
                .compareTo(run.authorizationLimitCny()) > 0) throw conflict("本次授权剩余金额不足，重试不会扩大授权");
        var charge = new Charge(newId(), chargeKey, taskId, ownerId, runId, run.approvalId(), generation, capability,
                amount, null, "RESERVED", null, null, price, null);
        try { mapper.insertCharge(charge); }
        catch (DuplicateKeyException exception) { throw conflict("调用标识已被占用"); }
        return charge;
    }

    /** 必须在外部请求发出前调用；只有第一次返回 true，重放不能再次发送物理请求。 */
    public boolean markSent(String taskId, String ownerId, String chargeKey, String worker, long generation) {
        lock(taskId, ownerId); id(worker); var charge = charge(taskId, ownerId, chargeKey);
        if (charge.leaseGeneration() != generation) throw conflict("费用预留不属于当前执行尝试");
        live(taskId, ownerId, charge.runId(), worker, generation);
        return mapper.sent(taskId, ownerId, chargeKey) == 1;
    }

    public Charge settle(String taskId, String ownerId, String chargeKey, BigDecimal actual, String kind, String evidenceRef) {
        lock(taskId, ownerId); actual = money(actual, true); evidence(evidenceRef);
        if (kind == null || !Set.of("USAGE_ESTIMATE", "PROVIDER_REPORTED").contains(kind)) throw conflict("费用结算类型不正确");
        var charge = charge(taskId, ownerId, chargeKey);
        if ("SETTLED".equals(charge.status())) {
            if (actual.compareTo(charge.knownCostCny()) != 0 || !kind.equals(charge.settlementKind())
                    || !evidenceRef.equals(charge.evidenceRef())) throw conflict("重复结算内容不一致");
            return charge;
        }
        // 真实费用即使超出预留也必须记账；之后暂停新增付费调用，不能把事实截断为预算。
        if (mapper.settle(taskId, ownerId, chargeKey, actual, kind, evidenceRef) != 1) throw conflict("该调用尚未发送或预留已释放");
        return charge(taskId, ownerId, chargeKey);
    }

    public Charge markUnknown(String taskId, String ownerId, String chargeKey) {
        lock(taskId, ownerId); var charge = charge(taskId, ownerId, chargeKey);
        if (Set.of("UNKNOWN", "SETTLED").contains(charge.status())) return charge;
        if (mapper.unknown(taskId, ownerId, chargeKey) != 1) throw conflict("只有已发送的调用可以记为费用未知");
        return charge(taskId, ownerId, chargeKey);
    }

    public void revokeApproval(String taskId, String ownerId, String approvalId) {
        lock(taskId, ownerId); approval(taskId, ownerId, approvalId);
        int changed = mapper.revoke(taskId, ownerId, approvalId);
        var current = initialized(taskId, ownerId);
        if (changed == 1 && mapper.bump(taskId, ownerId, current.workspaceRevision()) != 1) throw conflict("工作区已变化");
        if (current.activeRunId() != null && approvalId.equals(run(taskId, ownerId, current.activeRunId()).approvalId()))
            cancel(taskId, ownerId, current.activeRunId());
    }

    private void close(String taskId, String ownerId, String runId) {
        mapper.closeReservations(taskId, ownerId, runId); mapper.deactivate(taskId, ownerId, runId);
    }
    private void idle(String taskId, String ownerId, Workspace current) {
        media.lockTaskDraftVersions(taskId, ownerId);
        if (current.activeRunId() != null || workspace.countActiveWork(taskId, ownerId) > 0)
            throw conflict("任务仍有制作、上传或试映工作在运行");
    }
    private Workspace active(String taskId, String ownerId, String runId) {
        var current = initialized(taskId, ownerId);
        if (!Objects.equals(current.activeRunId(), runId)) throw conflict("运行已不再是当前活动运行");
        return current;
    }
    private void live(String taskId, String ownerId, String runId, String worker, long generation) {
        if (mapper.liveLease(taskId, ownerId, runId, worker, generation, "RUNNING") != 1) throw conflict("执行租约已失效或运行已取消");
    }
    private Workspace initialized(String taskId, String ownerId) {
        var current = mapper.workspace(taskId, ownerId).orElseThrow(() -> conflict("请先初始化制作预算"));
        if (current.budgetLimitCny() == null) throw conflict("请先初始化制作预算");
        return current;
    }
    private void revision(Workspace current, long revision) {
        if (current.workspaceRevision() != revision) throw conflict("工作区已变化，请刷新后重试");
    }
    private Run run(String taskId, String ownerId, String runId) {
        id(runId); return mapper.run(taskId, ownerId, runId).orElseThrow(() -> missing("制作运行不存在"));
    }
    private Approval approval(String taskId, String ownerId, String approvalId) {
        id(approvalId); return mapper.approval(taskId, ownerId, approvalId).orElseThrow(() -> missing("制作授权不存在"));
    }
    private Charge charge(String taskId, String ownerId, String chargeKey) {
        key(chargeKey); return mapper.charge(taskId, ownerId, chargeKey).orElseThrow(() -> missing("调用费用预留不存在"));
    }
    private void requireTask(String taskId, String ownerId) {
        id(taskId);
        if (media.countTaskByOwner(taskId, ownerId) != 1) throw missing("创作任务不存在");
    }
    private void lock(String taskId, String ownerId) {
        id(taskId);
        if (media.lockMediaTask(taskId, ownerId).isEmpty()) throw missing("创作任务不存在");
    }
    private static boolean blocked(Totals totals) { return totals.unknownCny().signum() > 0 || totals.overrunCount() > 0; }
    private static void leaseSeconds(int seconds) { if (seconds < 5 || seconds > 600) throw conflict("执行租约应在 5 到 600 秒之间"); }
    private static String newId() { return UUID.randomUUID().toString().replace("-", ""); }
    private static ResponseStatusException missing(String message) { return new ResponseStatusException(HttpStatus.NOT_FOUND, message); }
}
