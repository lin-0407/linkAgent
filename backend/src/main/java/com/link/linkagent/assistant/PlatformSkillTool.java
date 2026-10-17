package com.link.linkagent.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.tool.Tool;
import org.springframework.stereotype.Component;
import java.util.*;

/** 运行时流程说明按需加载；不读取开发机器文件，也不把开发协作技能当成产品使用说明。 */
@Component
public class PlatformSkillTool implements Tool {
    private final ObjectMapper json;
    private final Map<String,String> skills=new LinkedHashMap<>();
    public PlatformSkillTool(ObjectMapper json) {
        this.json=json;
        skills.put("创作任务", "用户只有想法时使用creative.createInteractiveTask，已有稿件用tasks.createTask。创建前展示保存内容。创建后用返回的taskId继续，不能凭空填写任务ID。缺关键创作事实先问用户；可用{$input:说明}暂停等待。" );
        skills.put("发布方案", "先查询creative.getInteractiveTask或工作流记录；普通想法路径为理解创作想法、生成方案、用户选择并确认方案。已有发布前工作流则先沟通和对齐再生成。不能混用创意方案optionId与工作流suggestionId。同一上下文最多成功生成三次。用户未看过生成结果时，确认选项需用$input等用户明确选择，不能默认选第一个。" );
        skills.put("制作蓝图", "先读取已确认发布方案和当前蓝图。按blueprint.create的真实参数询问受众、核心承诺、时长、已有素材和工具；不能编造用户条件。生成后回查当前蓝图；步骤状态更新会保留原服务的状态和重做规则。" );
        skills.put("成片试映", "已有成片的草稿任务可在用户明确选择后tasks.skipToPreflight。media.uploadVideo会等待用户选择本地MP4并复用浏览器分片上传。随后探测成片、估算处理费用、处理媒体、成片试映；处理和试映会自动等待真实后台任务完成。试映完成后用户实际发布视频；不得跳过试映进入发布后分析。不能让模型获取私有签名地址或假装已经发布。" );
        skills.put("发布后复盘", "先检查bilibili.getPostPublishReadiness；绑定BV前需要成片试映就绪。按用户提供的账号与BV绑定，采集或导入反馈，分析反馈，再生成总体复盘。竞品可选。只用发布后的正式资料与反馈，不把发布前设想当实际表现。重新分析会按原服务规则失效旧复盘，展示影响。" );
        skills.put("知识库", "references提供浏览、案例证据、主题检索、导入、公开数据刷新与索引维护。collection用于查看、启动或停止自动采集；maintenance用于回补和清理。先查询已有状态，按用户指定分区与范围提出操作；清理必须展示范围和影响。只读检索可直接调用，有写入的采集和重建必须放进确认计划。" );
        skills.put("记忆与设置", "memory支持浏览、保存、删除、恢复；context管理创作语境；settings管理运行期开关和配置值；models管理模型配置和连通性测试；prompts管理提示词。修改前查当前值并展示变化，删除要列出具体对象。API密钥使用{$input:密钥说明,secret:true}，仅通过专用密码框提交，不放入聊天、计划或模型上下文；不支持的启动期配置照实解释。" );
    }
    public String getName() { return "platform_skill"; }
    public boolean isAvailable() { return AssistantInvocationContext.current()!=null; }
    public String getDescription() { return "按需读取平台业务流程。输入JSON：{\"name\":\"\"}列出流程名称，name填创作任务、发布方案、制作蓝图、成片试映、发布后复盘、知识库、记忆与设置之一读取说明。复杂操作前先读对应流程，再查真实操作目录和任务状态。"; }
    public String execute(String input) {
        try { String name=json.readTree(input).path("name").asText(); return json.writeValueAsString(name.isBlank()?skills.keySet():Map.of("name",name,"instructions",skills.getOrDefault(name,"未提供此流程，请从已有名称中选择"))); }
        catch(com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalArgumentException("流程查询必须是JSON",error); }
    }
}
