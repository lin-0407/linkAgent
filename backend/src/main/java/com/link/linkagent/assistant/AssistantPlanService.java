package com.link.linkagent.assistant;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.link.linkagent.assistant.AssistantPlanModels.*;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;

/** 用户确认后执行固定步骤；检查点避免重做已完成写入，未知结果必须先人工核对。 */
@Service
public class AssistantPlanService {
    private final AssistantPlanMapper mapper;
    private final PlatformOperationCatalog catalog;
    private final PlatformAssistantContextService context;
    private final ObjectMapper json;
    private final ExecutorService executor=Executors.newFixedThreadPool(2);
    @org.springframework.beans.factory.annotation.Value("${assistant.plan.worker-enabled:true}")
    private boolean workerEnabled=true;
    private static final Set<String> DONE=Set.of("SUCCEEDED","COMPLETED","READY");
    private static final Set<String> BAD=Set.of("FAILED","CANCELLED","CANCELED","ABORTED","SUPERSEDED");
    private static final Map<String,String> VERIFY=Map.ofEntries(
        Map.entry("tasks.createTask","tasks.getTask"),Map.entry("tasks.updateTask","tasks.getTask"),
        Map.entry("creative.createInteractiveTask","creative.getInteractiveTask"),
        Map.entry("creative.triggerUnderstanding","creative.getInteractiveTask"),
        Map.entry("creative.generateCreativeOptions","creative.getInteractiveTask"),
        Map.entry("creative.regenerateOptions","creative.getInteractiveTask"),Map.entry("creative.confirmOption","creative.getInteractiveTask"),
        Map.entry("blueprint.create","blueprint.getCurrent"),Map.entry("blueprint.updateStep","blueprint.getCurrent"),
        Map.entry("taskSettings.saveSettings","taskSettings.getSettings"),
        Map.entry("feedback.analyze","feedback.getReport"),Map.entry("feedback.saveFeedback","feedback.getFeedback"),
        Map.entry("feedback.fetchFeedback","feedback.getFeedback"),Map.entry("feedback.importFeedback","feedback.getFeedback"),
        Map.entry("review.analyze","review.getReport"),Map.entry("competitor.analyze","competitor.getReport"),
        Map.entry("competitor.analyzeByReferenceVideo","competitor.getReport"),Map.entry("bilibili.bindBvToTask","bilibili.getTaskBinding"));

    public AssistantPlanService(AssistantPlanMapper mapper,PlatformOperationCatalog catalog,
                                PlatformAssistantContextService context,ObjectMapper json) {
        this.mapper=mapper; this.catalog=catalog; this.context=context; this.json=json;
    }

    public View prepare(Prepare request) {
        if (request.steps()==null || request.steps().isEmpty() || request.steps().size()>20) throw bad("计划需要1到20个步骤");
        State state=new State(); Set<String> previous=new HashSet<>();
        for (Step step:request.steps()) {
            if (step.id()==null || !step.id().matches("[a-zA-Z][a-zA-Z0-9_-]{0,39}") || previous.contains(step.id())) throw bad("步骤标识不合法或重复");
            validateReferences(step.arguments(),previous);
            JsonNode selected=step.operation().equals("creative.confirmOption")?step.arguments().path("optionId")
                    :step.operation().equals("workflow.confirmPrePublishSuggestion")?step.arguments().path("request").path("suggestionId"):null;
            if (selected!=null && selected.has("$ref")) throw bad("新生成的方案必须等待用户选择，请使用待补充输入，不能自动引用第一项方案确认");
            catalog.validate(step.operation(),step.arguments(),true);
            state.steps.add(new Progress(step.id())); previous.add(step.id());
        }
        state.guards=guards(request);
        state.guards.keySet().forEach(id -> state.targets.put(id,context.inspect("/creator",id).taskName()));
        String definition=write(request),id=UUID.randomUUID().toString();
        Row row=new Row(id,request.taskId(),request.title(),"PENDING",definition,write(state),0,
                AssistantActionService.digest(definition),LocalDateTime.now());
        mapper.insert(row); return view(row);
    }

    public List<View> recent() { return mapper.recent().stream().map(this::view).toList(); }
    public View get(String id) { return view(row(id)); }

    public View confirm(String id,Approval approval) {
        Row row=row(id); checkApproval(row,approval.confirmed(),approval.digest());
        if (!row.status().equals("PENDING")) return view(row);
        if (row.createTime().plusMinutes(30).isBefore(LocalDateTime.now())) throw conflict("计划确认已过期，请重新准备");
        State state=state(row);
        if (!state.guards.equals(guards(definition(row)))) throw conflict("任务资料已变化，请重新查看并确认新计划");
        state.message="正在按已确认的计划执行";
        if (mapper.update(id,row.revision(),"RUNNING",write(state))==1) schedule(id,null,List.of());
        return get(id);
    }

    public View inputs(String id,Inputs input,List<MultipartFile> files) {
        Row row=row(id); checkApproval(row,input.confirmed(),input.digest());
        if (!row.status().equals("WAIT_INPUT")) throw conflict("计划当前没有等待输入，请刷新状态");
        if (input.values()==null || !input.values().isObject()) throw bad("补充输入必须是对象");
        if (input.values().toString().length()>100000) throw bad("补充输入过长");
        State state=state(row); Progress progress=state.steps.stream().filter(p -> p.status.equals("WAIT_INPUT")).findFirst().orElseThrow();
        Set<String> allowed=new HashSet<>(); progress.inputs.forEach(field -> allowed.add(field.path()));
        Step step=definition(row).steps().stream().filter(s -> s.id().equals(progress.id)).findFirst().orElseThrow();
        if (step.operation().equals("media.uploadVideo")) { allowed.add("versionId"); allowed.add("uploadSessionId"); }
        input.values().fieldNames().forEachRemaining(key -> { if (!allowed.contains(key)) throw bad("不能修改已确认参数："+key); });
        for (InputField field:progress.inputs) if (!input.values().has(field.path())) throw bad("请填写："+field.label());
        if (catalog.describe(step.operation()).interaction().equals("FILES") && files.isEmpty()) throw bad("请先选择文件");
        // 文件在当前请求生命周期内消费，避免异步线程读取已经被容器删除的上传临时文件。
        if (mapper.update(id,row.revision(),"RUNNING",write(state))!=1) throw conflict("输入已被提交，请刷新状态");
        if (files.isEmpty()) schedule(id,input.values(),files); else run(id,input.values(),files);
        return get(id);
    }

    public View resume(String id) {
        Row row=row(id); State state=state(row);
        if (!Set.of("INTERRUPTED","WAITING").contains(row.status())) return view(row);
        if (state.steps.stream().anyMatch(p -> p.status.equals("RUNNING")))
            throw conflict("中断时有操作尚未确认结果，请先核对原业务页面；系统不会自动重发写入或收费调用");
        if (mapper.update(id,row.revision(),"RUNNING",write(state))==1) schedule(id,null,List.of());
        return get(id);
    }

    public View cancel(String id) {
        Row row=row(id); State state=state(row);
        if (Set.of("SUCCEEDED","FAILED","CANCELLED").contains(row.status())) return view(row);
        state.message=row.status().equals("RUNNING") ? "已请求停止，当前业务调用结束后不会执行后续步骤" : "已停止后续步骤，已完成的业务结果保留";
        mapper.update(id,row.revision(),row.status().equals("RUNNING")?"CANCEL_REQUESTED":"CANCELLED",write(state));
        return get(id);
    }

    @Scheduled(fixedDelay=3000)
    public void pollWaiting() {
        if (!workerEnabled) return;
        for (Row row:mapper.waiting()) if (mapper.update(row.planId(),row.revision(),"RUNNING",row.stateJson())==1)
            schedule(row.planId(),null,List.of());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover() { if (workerEnabled) mapper.interruptPreviousRuns(); }
    @PreDestroy public void close() { executor.shutdownNow(); }
    private void schedule(String id,JsonNode input,List<MultipartFile> files) { executor.submit(() -> run(id,input,files)); }

    void run(String id,JsonNode supplied,List<MultipartFile> files) {
        while (true) {
            Row current=row(id); State state=state(current); Prepare definition=definition(current);
            if (current.status().equals("CANCEL_REQUESTED")) { save(current,state,"CANCELLED"); return; }
            if (!current.status().equals("RUNNING")) return;
            int index=-1;
            for (int i=0;i<state.steps.size();i++) if (!state.steps.get(i).status.equals("SUCCEEDED")) { index=i; break; }
            if (index<0) { state.message="计划中的操作已完成；下方保留每步业务结果与回查依据"; save(current,state,"SUCCEEDED"); return; }
            Progress progress=state.steps.get(index); Step step=definition.steps().get(index);
            try {
                ObjectNode arguments=(ObjectNode)resolve(step.arguments(),state,supplied,"");
                progress.arguments=catalog.sanitize(arguments);
                boolean polling=progress.status.equals("WAIT_JOB");
                var interaction=catalog.describe(step.operation()).interaction();
                progress.inputs=new ArrayList<>(); collectInputs(step.arguments(),"",catalog.describe(step.operation()).parameters(),progress.inputs);
                if (!polling && supplied==null && (!progress.inputs.isEmpty() || !interaction.equals("NONE"))) {
                    if (interaction.equals("VIDEO")) progress.waitStartedAt=LocalDateTime.now().toString();
                    progress.status="WAIT_INPUT"; state.message=interaction.equals("FILES") || interaction.equals("VIDEO")
                            ? "请选择文件，确认后继续本计划" : "请补充下面的参数，确认这些输入后继续";
                    save(current,state,"WAIT_INPUT"); return;
                }
                progress.status=polling?"WAIT_JOB":"RUNNING";
                state.message=polling?"后台任务尚未完成，正在读取实际进度":"正在执行："+catalog.entry(step.operation()).label();
                if (!save(current,state,"RUNNING")) return;
                JsonNode result;
                if (polling) result=poll(step,progress);
                else if (interaction.equals("VIDEO")) result=verifyUploadedVideo(arguments,supplied,progress);
                else {
                    String taskId=arguments.path("taskId").asText(null);
                    try (var scope=AssistantInvocationContext.open(new AssistantInvocationContext.State(taskId,false))) {
                        result=catalog.invoke(step.operation(),arguments,id+":"+step.id(),files);
                    }
                }
                progress.result=result;
                if (async(step.operation()) && !DONE.contains(result.path("status").asText())) {
                    if (BAD.contains(result.path("status").asText())) throw new IllegalStateException("后台任务未完成："+result.path("status").asText());
                    progress.status="WAIT_JOB"; state.message="后台业务仍在执行，助手将继续读取进度，完成后接着下一步";
                    saveLatest(id,state,"WAITING"); return;
                }
                progress.verification=verify(step,arguments,result);
                progress.status="SUCCEEDED"; progress.error=null; progress.inputs=List.of();
                state.message="已完成："+catalog.entry(step.operation()).label();
                if (!saveLatest(id,state,"RUNNING")) return;
                if (!files.isEmpty()) { schedule(id,null,List.of()); return; }
                supplied=null; files=List.of();
            } catch (Exception error) {
                progress.status="FAILED";
                // 专用输入可能含密钥，错误不回显底层报文或用户提交的原值。
                progress.error=step.operation().equals("models.saveConfig") || progress.inputs.stream().anyMatch(InputField::secret)
                        ?"配置操作失败，请检查专用输入和原设置页面":safeError(error);
                state.message="停在“"+catalog.entry(step.operation()).label()+"”："+progress.error+"。已完成的步骤保留，不会自动重试本次操作";
                saveLatest(id,state,"FAILED"); return;
            }
        }
    }

    private JsonNode verify(Step step,ObjectNode args,JsonNode result) {
        String check=VERIFY.get(step.operation());
        if (check!=null) {
            String taskId=result.path("taskId").asText(args.path("taskId").asText());
            ObjectNode parameters=json.createObjectNode().put("taskId",taskId);
            JsonNode actual=catalog.query(check,parameters);
            if (actual.has("taskId") && !actual.path("taskId").asText().equals(taskId)) throw conflict("回查结果与操作任务不一致");
            for (String identity:List.of("reportId","taskId","selectedOptionId"))
                if (result.hasNonNull(identity) && actual.hasNonNull(identity) && !result.get(identity).equals(actual.get(identity)))
                    throw conflict("回查结果与本次操作返回的"+identity+"不一致");
            return json.createObjectNode().put("kind","READ_BACK").put("operation",check).set("result",actual);
        }
        if (step.operation().equals("tasks.deleteTask")) {
            try { catalog.query("tasks.getTask",args); throw conflict("删除后任务仍然存在"); }
            catch (ResponseStatusException error) { if (error.getStatusCode().value()!=404) throw error; }
            return json.createObjectNode().put("kind","READ_BACK").put("message","回查确认任务已删除");
        }
        return json.createObjectNode().put("kind","BUSINESS_RESPONSE").put("message","以下为原业务服务返回的结果");
    }

    private boolean async(String operation) { return Set.of("processing.createJob","processing.retryJob","screening.create","screening.retry","screening.completeScreening").contains(operation); }
    private JsonNode poll(Step step,Progress progress) {
        JsonNode result=progress.result;
        if (step.operation().startsWith("screening.")) return catalog.query("screening.get",json.createObjectNode()
                .put("taskId",result.path("taskId").asText()).put("reviewId",result.path("reviewId").asText()));
        JsonNode actual=catalog.query("processing.getCurrentJob",json.createObjectNode().put("taskId",result.path("taskId").asText())
                .put("versionId",result.path("versionId").asText()));
        if (!actual.path("jobId").equals(result.path("jobId"))) throw conflict("当前媒体任务已变化，请在原页面核对");
        return actual;
    }

    private JsonNode verifyUploadedVideo(JsonNode args,JsonNode supplied,Progress progress) {
        String version=supplied==null?"":supplied.path("versionId").asText();
        if(version.isBlank()) throw bad("尚未完成本次成片上传");
        JsonNode result=catalog.query("media.getDraftVideo",json.createObjectNode().setAll(Map.of("taskId",args.path("taskId"),"versionId",new TextNode(version))));
        String uploadId=supplied.path("uploadSessionId").asText();
        if (uploadId.isBlank()) throw bad("缺少本次上传会话，请完成上传后继续");
        JsonNode upload=catalog.query("uploads.getUpload",json.createObjectNode().setAll(Map.of("taskId",args.path("taskId"),"uploadSessionId",new TextNode(uploadId))));
        if (!upload.path("status").asText().equals("COMPLETED") || !upload.path("versionId").asText().equals(version)) throw conflict("服务端还没有确认成片上传完成");
        if (progress.waitStartedAt!=null && (upload.path("completedAt").isNull() || upload.path("completedAt").isMissingNode()
                || LocalDateTime.parse(upload.path("completedAt").asText()).isBefore(LocalDateTime.parse(progress.waitStartedAt).minusSeconds(1))))
            throw conflict("这个上传在本计划开始前已经完成，请选择本次文件并完成上传");
        return result;
    }

    private JsonNode resolve(JsonNode value,State state,JsonNode input,String path) {
        if (value.isObject() && value.has("$ref")) {
            String[] reference=value.path("$ref").asText().split(":",2);
            Progress previous=state.steps.stream().filter(p -> p.id.equals(reference[0]) && p.status.equals("SUCCEEDED")).findFirst().orElseThrow(() -> bad("引用的前序步骤尚未成功"));
            JsonNode resolved=previous.result.at(reference.length==1?"":reference[1]);
            if (resolved.isMissingNode() || resolved.isNull()) throw bad("前序结果没有所需字段："+value.path("$ref").asText());
            return resolved.deepCopy();
        }
        if(value.isObject() && value.has("$input")) return input!=null && input.has(path)?input.get(path):NullNode.instance;
        if(value.isObject()) { ObjectNode result=json.createObjectNode(); value.fields().forEachRemaining(f -> result.set(f.getKey(),resolve(f.getValue(),state,input,path+"/"+escape(f.getKey())))); return result; }
        if(value.isArray()) { ArrayNode result=json.createArrayNode(); for(int i=0;i<value.size();i++) result.add(resolve(value.get(i),state,input,path+"/"+i)); return result; }
        return value.deepCopy();
    }
    private void validateReferences(JsonNode value,Set<String> previous) {
        if(value==null) throw bad("缺少步骤参数");
        if(value.has("$ref")) {
            String[] parts=value.path("$ref").asText().split(":",2);
            if(value.size()!=1 || !previous.contains(parts[0]) || (parts.length>1 && !parts[1].isEmpty() && !parts[1].startsWith("/"))) throw bad("只能引用已排在前面的步骤及其JSON路径");
        } else if(value.has("$input")) {
            if(value.path("$input").asText().isBlank()) throw bad("待补充参数需要可读说明");
        } else value.forEach(child -> validateReferences(child,previous));
    }
    private void collectInputs(JsonNode value,String path,JsonNode schema,List<InputField> result) {
        if(value.has("$input")) { result.add(new InputField(path,value.path("$input").asText(),value.path("secret").asBoolean() || schema.path("writeOnly").asBoolean(),schema)); return; }
        if(value.isObject()) value.fields().forEachRemaining(f -> collectInputs(f.getValue(),path+"/"+escape(f.getKey()),schema.path("properties").path(f.getKey()),result));
        if(value.isArray()) for(int i=0;i<value.size();i++) collectInputs(value.get(i),path+"/"+i,schema.path("items"),result);
    }
    private String escape(String key) { return key.replace("~","~0").replace("/","~1"); }
    private Map<String,String> guards(Prepare definition) {
        Map<String,String> guards=new TreeMap<>();
        if(definition.taskId()!=null && !definition.taskId().isBlank()) guards.put(definition.taskId(),AssistantActionService.digest(context.actionSource(definition.taskId())));
        for(Step step:definition.steps()) {
            JsonNode id=step.arguments().get("taskId");
            if(id!=null && id.isTextual() && !id.asText().isBlank()) guards.put(id.asText(),AssistantActionService.digest(context.actionSource(id.asText())));
        }
        return guards;
    }
    private void checkApproval(Row row,Boolean confirmed,String digest) { if(!Boolean.TRUE.equals(confirmed) || !row.digest().equals(digest)) throw conflict("请确认页面展示的当前具体计划"); }
    private boolean save(Row row,State state,String status) { return mapper.update(row.planId(),row.revision(),status,write(state))==1; }
    private boolean saveLatest(String id,State state,String status) {
        Row current=row(id);
        if(current.status().equals("CANCEL_REQUESTED")) { state.message="已停止后续步骤，已经执行的业务结果保留"; return save(current,state,"CANCELLED") && false; }
        return current.status().equals("RUNNING") && save(current,state,status);
    }
    private Row row(String id) { return mapper.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,"助手计划不存在")); }
    private View view(Row row) { Prepare definition=definition(row); return new View(row.planId(),row.taskId(),row.title(),row.status(),row.digest(),row.revision(),definition,state(row),definition.steps().stream().map(s -> catalog.describe(s.operation())).toList(),row.createTime()); }
    private Prepare definition(Row row) { return read(row.definitionJson(),Prepare.class); }
    private State state(Row row) { return read(row.stateJson(),State.class); }
    private <T>T read(String value,Class<T> type) { try { return json.readValue(value,type); } catch(Exception error) { throw new IllegalStateException("计划检查点无法读取",error); } }
    private String write(Object value) { try { return json.writeValueAsString(value); } catch(Exception error) { throw new IllegalArgumentException("计划内容无法保存",error); } }
    private String safeError(Exception error) { String text=error instanceof ResponseStatusException response?response.getReason():error.getMessage(); return text==null?"操作未完成":text.substring(0,Math.min(text.length(),450)); }
    private ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT,message); }
}
