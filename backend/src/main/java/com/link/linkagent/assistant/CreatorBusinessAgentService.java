package com.link.linkagent.assistant;

import com.link.linkagent.core.multi.MultiAgentOrchestrator;
import com.link.linkagent.core.multi.WorkerCall;
import com.link.linkagent.core.multi.WorkerPlan;
import com.link.linkagent.dto.AgentChatResponse;
import org.springframework.stereotype.Service;
import java.util.List;

/** 复盘有固定的三类独立分析，复用已有 Worker 调度和证据审查，不让模型猜业务分工。 */
@Service
public class CreatorBusinessAgentService {
    private final MultiAgentOrchestrator multi;
    private final CreatorAgentTraceService traces;
    public CreatorBusinessAgentService(MultiAgentOrchestrator multi,CreatorAgentTraceService traces) {
        this.multi=multi; this.traces=traces;
    }
    public String review(String taskId,String source) {
        var plan=new WorkerPlan("对已发布视频做有依据的复盘",List.of(
                call(1,"只分析正式发布资料与公开指标：哪些表现有数据依据，哪些指标缺失，不能推导因果或平台算法。"),
                call(2,"只分析观众反馈：归纳反复出现的疑问、误解与可修改之处，标明样本限制，不把少数评论当作全体观众。"),
                call(3,"只分析可选竞品与跨期资料：区分真实对比与资料缺失，没有竞品时只分析自身，不能补造对比结论。")
        ),"三类资料可以独立分析，再统一核对原始事实","覆盖表现、反馈、对比与限制");
        var result=traces.capture(taskId,"总体复盘分工分析","MULTI_AGENT",() -> {
            var run=multi.analyze(plan,source,"基于所给发布后资料完成复盘分析；不使用发布前素材，不产生新业务数据。");
            return new AgentChatResponse(null,run.finalAnswer(),run.stopReason(),run.totalSteps(),run.steps(),
                    run.executionMode(),run.planTrace(),run.workerTraces());
        });
        return "\n\n【多 Agent 的分析意见，事实仍以原始资料为准】\n"+result.finalAnswer()
                +"\n以上是分析意见，不是新增证据；生成结构化报告时逐项对照前面的原始数据，排除无依据结论。";
    }
    private WorkerCall call(int id,String task) {
        return new WorkerCall(id,"direct_reasoning_worker",task,"仅使用所给资料；说明事实、推断和缺失。",List.of());
    }
}
