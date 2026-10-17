package com.link.linkagent.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.util.*;

/** 计划参数和逐步结果分开保存，确认后的操作定义不允许被执行器悄悄改写。 */
public final class AssistantPlanModels {
    private AssistantPlanModels() { }
    public record Step(@NotBlank @Pattern(regexp="[a-zA-Z][a-zA-Z0-9_-]{0,39}") String id,
                       @NotBlank @Size(max=100) String operation, @NotNull JsonNode arguments,
                       @Size(max=500) String note) { }
    public record Prepare(@Size(max=64) String taskId, @NotBlank @Size(max=160) String title,
                          @NotEmpty @Size(max=20) List<@Valid Step> steps) { }
    public record Approval(@NotNull @AssertTrue Boolean confirmed, @NotBlank @Size(max=64) String digest) { }
    public record Inputs(@NotNull @AssertTrue Boolean confirmed, @NotBlank @Size(max=64) String digest,
                         @NotNull JsonNode values) { }
    public record Row(String planId,String taskId,String title,String status,String definitionJson,
                      String stateJson,int revision,String digest,LocalDateTime createTime) { }
    public record View(String planId,String taskId,String title,String status,String digest,int revision,
                       Prepare definition,State state,List<PlatformOperationCatalog.Operation> operations,LocalDateTime createTime) { }
    public static class State {
        public List<Progress> steps=new ArrayList<>();
        public Map<String,String> guards=new LinkedHashMap<>();
        public Map<String,String> targets=new LinkedHashMap<>();
        public String message="计划尚未执行，确认后将按顺序运行，可能调用模型并产生费用";
    }
    public static class Progress {
        public String id;
        public String status="PENDING";
        public JsonNode arguments;
        public JsonNode result;
        public JsonNode verification;
        public String error;
        public String waitStartedAt;
        public List<InputField> inputs=new ArrayList<>();
        public Progress() { }
        public Progress(String id) { this.id=id; }
    }
    public record InputField(String path,String label,boolean secret,JsonNode schema) { }
}
