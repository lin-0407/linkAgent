package com.link.linkagent.assistant;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.*;
import jakarta.validation.constraints.*;
import org.springframework.context.ApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import java.lang.reflect.*;
import java.util.*;

/** 目录由已核对的页面操作生成，只允许固定方法；复用控制器以保留业务门禁和原有校验。 */
@Service
public class PlatformOperationCatalog {
    public record Entry(String id, String group, String label, String controller, String method, String access) { }
    public record Operation(String id, String group, String label, String access, String interaction, JsonNode parameters, String impact) { }
    private final Map<String,Entry> entries = new LinkedHashMap<>();
    private final ObjectMapper json;
    private final Validator validator;
    private final ApplicationContext application;

    public PlatformOperationCatalog(ObjectMapper json, Validator validator, ApplicationContext application) throws Exception {
        this.json=json; this.validator=validator; this.application=application;
        try (var input=new ClassPathResource("assistant/operations.json").getInputStream()) {
            for (Entry entry:json.readValue(input,Entry[].class)) {
                if (entries.putIfAbsent(entry.id(),entry)!=null) throw new IllegalStateException("平台操作重名："+entry.id());
                if (!entry.controller().isEmpty()) method(entry);
            }
        }
    }

    public List<Map<String,String>> index() {
        return entries.values().stream().map(e -> Map.of("id",e.id(),"group",e.group(),"label",e.label(),"access",e.access())).toList();
    }

    public List<Operation> discover(String query) {
        String text=Objects.requireNonNullElse(query,"").trim().toLowerCase(Locale.ROOT);
        if (entries.containsKey(query)) return List.of(describe(query));
        return entries.values().stream().filter(e -> text.isEmpty() || (e.id()+e.group()+e.label()).toLowerCase(Locale.ROOT).contains(text))
                .limit(8).map(e -> describe(e.id())).toList();
    }

    public Entry entry(String id) {
        Entry entry=entries.get(id);
        if (entry==null) throw new IllegalArgumentException("未提供此平台操作，请先查询操作目录："+id);
        return entry;
    }

    public Operation describe(String id) {
        Entry e=entry(id); ObjectNode schema=json.createObjectNode().put("type","object");
        ObjectNode properties=schema.putObject("properties"); ArrayNode required=schema.putArray("required");
        String interaction="NONE";
        if (e.controller().isEmpty()) {
            properties.putObject("taskId").put("type","string"); required.add("taskId");
            properties.putObject("versionName").put("type","string");
            interaction="VIDEO";
        } else {
            for (Parameter p:method(e).getParameters()) {
                if (p.getType()==HttpServletRequest.class || p.isAnnotationPresent(RequestHeader.class)) continue;
                if (isFile(p)) { interaction="FILES"; schema.put("x-multiple-files",p.getType()!=MultipartFile.class); continue; }
                properties.set(p.getName(),schema(json.constructType(p.getParameterizedType()),0));
                RequestParam query=p.getAnnotation(RequestParam.class);
                RequestBody body=p.getAnnotation(RequestBody.class);
                boolean optional=(query!=null && (!query.required() || !ValueConstants.DEFAULT_NONE.equals(query.defaultValue())))
                        || (body!=null && !body.required());
                if (!optional) required.add(p.getName());
            }
        }
        schema.put("additionalProperties",false);
        return new Operation(e.id(),e.group(),e.label(),e.access(),interaction,schema,impact(e));
    }

    public void validate(String id, JsonNode arguments, boolean allowDeferred) {
        if (arguments==null || !arguments.isObject()) throw new IllegalArgumentException("操作参数必须是对象");
        if (arguments.toString().length()>100000) throw new IllegalArgumentException("单步参数过长");
        Operation operation=describe(id);
        arguments.fieldNames().forEachRemaining(key -> {
            if (!operation.parameters().path("properties").has(key)) throw new IllegalArgumentException("操作不接受参数："+key);
        });
        rejectSecrets(arguments);
        if (allowDeferred && deferred(arguments)) return;
        if (operation.interaction().equals("VIDEO")) {
            if (arguments.path("taskId").asText().isBlank()) throw new IllegalArgumentException("请选择上传成片的任务");
            return;
        }
        if (!operation.interaction().equals("FILES")) bind(entry(id),arguments,"校验",List.of(),true);
    }

    public JsonNode query(String id, JsonNode arguments) {
        if (!entry(id).access().equals("READ")) throw new IllegalArgumentException("此操作会修改数据，请先提交待确认计划");
        validate(id,arguments,false);
        return invoke(id,arguments,"查询",List.of());
    }

    public JsonNode invoke(String id, JsonNode arguments, String idempotencyKey, List<MultipartFile> files) {
        Entry e=entry(id);
        if (e.controller().isEmpty()) throw new IllegalArgumentException("请在助手的文件卡片中选择成片");
        Method method=method(e);
        Object[] values=bind(e,arguments,idempotencyKey,files,false);
        try {
            Object result=method.invoke(application.getBean(method.getDeclaringClass()),values);
            if (result instanceof ResponseEntity<?> entity) result=entity.getBody();
            JsonNode tree=result==null ? json.createObjectNode().put("completed",true) : json.valueToTree(result);
            if (tree.has("success") && !tree.path("success").asBoolean()) throw new IllegalStateException("业务没有完成请求，请查看原业务结果");
            if (id.equals("creative.triggerUnderstanding") && !tree.path("understandingStatus").asText().equals("READY"))
                throw new IllegalStateException("创作想法尚未理解完成，不能继续生成方案");
            if (tree.has("parseStatus") && !tree.path("parseStatus").asText().equals("PARSED")
                    && Set.of("feedback.analyze","review.analyze","workflow.analyzePrePublishWorkflow","competitor.analyze","competitor.analyzeByReferenceVideo").contains(id))
                throw new IllegalStateException("业务返回的报告没有通过结构校验，请查看原业务结果");
            return sanitize(tree);
        } catch (InvocationTargetException error) {
            Throwable cause=error.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("平台操作失败",cause);
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("平台操作绑定失败",error); }
    }

    private Object[] bind(Entry e,JsonNode arguments,String key,List<MultipartFile> files,boolean validateOnly) {
        if (e.id().equals("tasks.updateTask")) arguments=preserveTaskMaterials(arguments);
        Method m=method(e); Parameter[] parameters=m.getParameters(); Object[] values=new Object[parameters.length];
        for (int i=0;i<parameters.length;i++) {
            Parameter p=parameters[i]; JsonNode node=arguments.get(p.getName());
            if (p.isAnnotationPresent(RequestHeader.class)) values[i]=key;
            else if (p.getType()==HttpServletRequest.class) values[i]=servletRequest();
            else if (isFile(p)) {
                if (files.isEmpty()) throw new IllegalArgumentException("请先选择文件");
                if (p.getType()==MultipartFile.class && files.size()!=1) throw new IllegalArgumentException("此操作每次只接受一个文件");
                values[i]=p.getType()==MultipartFile.class ? files.getFirst() : files;
            }
            else {
                RequestParam query=p.getAnnotation(RequestParam.class);
                RequestBody body=p.getAnnotation(RequestBody.class);
                if ((node==null || node.isNull()) && body!=null && body.required()) throw new IllegalArgumentException("缺少请求参数："+p.getName());
                if ((node==null || node.isNull()) && p.isAnnotationPresent(PathVariable.class)) throw new IllegalArgumentException("缺少操作对象："+p.getName());
                if (node==null && query!=null && !ValueConstants.DEFAULT_NONE.equals(query.defaultValue())) node=new TextNode(query.defaultValue());
                if (node==null && p.getType().isPrimitive()) throw new IllegalArgumentException("缺少参数："+p.getName());
                values[i]=node==null || node.isNull() ? null : json.convertValue(node,json.constructType(p.getParameterizedType()));
            }
        }
        Object bean=application.getBean(m.getDeclaringClass());
        var failures=validator.forExecutables().validateParameters(bean,m,values);
        if (!failures.isEmpty()) throw new ConstraintViolationException(failures);
        return values;
    }

    private JsonNode preserveTaskMaterials(JsonNode arguments) {
        if (!arguments.path("request").isObject()) throw new IllegalArgumentException("请提供要修改的任务字段");
        JsonNode original=query("tasks.getTask",json.createObjectNode().set("taskId",arguments.path("taskId")));
        ObjectNode request=json.createObjectNode();
        request.set("taskName",original.path("taskName")); request.set("videoType",original.path("videoType"));
        Map<String,String> materialFields=Map.of("TITLE_DRAFT","titleDraft","DESCRIPTION_DRAFT","descriptionDraft","MANUSCRIPT","manuscript","SUBTITLE","subtitle");
        materialFields.values().forEach(request::putNull);
        for(JsonNode material:original.path("materials")) {
            String field=materialFields.get(material.path("materialType").asText());
            if(field!=null) request.set(field,material.path("content"));
        }
        // 页面使用完整覆盖接口，助手常接到“只改名称”；补齐未修改原值，避免顺手清空用户稿件。
        request.setAll((ObjectNode)arguments.path("request"));
        ObjectNode copy=arguments.deepCopy(); copy.set("request",request); return copy;
    }

    private Method method(Entry entry) {
        try {
            return Arrays.stream(Class.forName(entry.controller()).getMethods()).filter(m -> m.getName().equals(entry.method()))
                    .findFirst().orElseThrow(() -> new IllegalStateException("平台目录与业务接口不一致："+entry.id()));
        } catch (ClassNotFoundException error) { throw new IllegalStateException("平台目录引用了不存在的业务",error); }
    }

    private HttpServletRequest servletRequest() {
        // 采集服务需要本机实际监听端口，不能接受模型提供回调地址或命令。
        return (HttpServletRequest)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{HttpServletRequest.class},(proxy,m,args) -> switch(m.getName()) {
            case "getLocalPort" -> application instanceof ServletWebServerApplicationContext web ? web.getWebServer().getPort() : 0;
            case "getContextPath" -> application.getEnvironment().getProperty("server.servlet.context-path","");
            default -> throw new UnsupportedOperationException("操作不能读取此请求属性");
        });
    }

    private boolean isFile(Parameter p) {
        return p.getType()==MultipartFile.class || p.getParameterizedType().getTypeName().contains("MultipartFile");
    }

    private JsonNode schema(JavaType type,int depth) {
        ObjectNode node=json.createObjectNode(); Class<?> raw=type.getRawClass();
        if (depth>6) return node.put("type","object");
        if (raw.isEnum()) { node.put("type","string"); ArrayNode options=node.putArray("enum"); for(Object e:raw.getEnumConstants()) options.add(e.toString()); }
        else if (raw==String.class || raw.getName().startsWith("java.time")) node.put("type","string");
        else if (raw==boolean.class || raw==Boolean.class) node.put("type","boolean");
        else if (Number.class.isAssignableFrom(raw) || raw.isPrimitive()) node.put("type",raw==Double.class || raw==Float.class || raw==double.class || raw==float.class || raw==java.math.BigDecimal.class ? "number":"integer");
        else if (type.isCollectionLikeType() || type.isArrayType()) { node.put("type","array"); node.set("items",schema(type.getContentType(),depth+1)); }
        else if (type.isMapLikeType() || JsonNode.class.isAssignableFrom(raw)) node.put("type","object").put("additionalProperties",true);
        else {
            node.put("type","object"); ObjectNode properties=node.putObject("properties"); ArrayNode required=node.putArray("required");
            for (var property:json.getSerializationConfig().introspect(type).findProperties()) {
                if (property.getPrimaryMember()==null || !property.couldDeserialize()) continue;
                String name=property.getName(); JsonNode field=schema(property.getPrimaryType(),depth+1); properties.set(name,field);
                if (sensitive(name)) ((ObjectNode)field).put("writeOnly",true).put("description","通过专用输入框提供，禁止放进聊天或计划正文");
                var constraints=validator.getConstraintsForClass(raw).getConstraintsForProperty(name);
                if (constraints!=null) for (var constraint:constraints.getConstraintDescriptors()) {
                    var annotation=constraint.getAnnotation();
                    if (annotation instanceof NotNull || annotation instanceof NotBlank || annotation instanceof NotEmpty) required.add(name);
                    if (annotation instanceof Size size) {
                        boolean array=field.path("type").asText().equals("array");
                        ((ObjectNode)field).put(array?"maxItems":"maxLength",size.max()); ((ObjectNode)field).put(array?"minItems":"minLength",size.min());
                    }
                    if (annotation instanceof Min min) ((ObjectNode)field).put("minimum",min.value());
                    if (annotation instanceof Max max) ((ObjectNode)field).put("maximum",max.value());
                    if (annotation instanceof Pattern pattern) ((ObjectNode)field).put("pattern",pattern.regexp());
                }
            }
        }
        return node;
    }

    static boolean deferred(JsonNode node) {
        if (node.isObject() && (node.has("$ref") || node.has("$input"))) return true;
        for (JsonNode child:node) if (deferred(child)) return true;
        return false;
    }
    private String impact(Entry entry) {
        if (entry.access().equals("READ")) return "读取现有业务数据，不通过此入口修改记录";
        return switch(entry.id()) {
            case "feedback.analyze" -> "调用模型并保存反馈分析，重新分析会使原有总体复盘及相关偏好失效";
            case "review.analyze" -> "调用多Agent和模型生成总体复盘，保存新结果与复盘历史，并更新相关偏好";
            case "blueprint.create" -> "调用模型生成制作蓝图；重新定位会按原业务规则失效旧媒体及后续分析";
            case "mediaFiles.deleteMedia" -> "删除该成片的原片和派生媒体，保留已有报告；被删除文件无法通过聊天恢复";
            case "tasks.deleteTask" -> "按原业务规则删除指定任务，请先核对任务名称和已有结果";
            case "maintenance.purge" -> "按指定范围清理案例和相关索引，请核对清理条件";
            case "collection.start" -> "启动后台采集，访问B站公开数据并保存案例；可在后续操作中停止";
            case "models.saveConfig" -> "仅保存配置，不自动切换当前文本模型；在设置页显式选择后生效，Embedding继续使用部署配置；密钥仅通过专用输入框提交并加密保存";
            case "tasks.updateTask" -> "只修改列出的字段，未提供的稿件保留；显式空值会清除对应字段。材料变化会使旧发布方案需要重新生成";
            case "creative.confirmOption", "workflow.confirmPrePublishSuggestion" -> "确认指定发布方案并推进后续创作；请先阅读该方案内容";
            default -> "调用原业务服务并保存或修改数据；生成、分析、试映、连通性测试等操作可能产生模型或外部服务费用";
        };
    }
    static boolean sensitive(String key) { return key.toLowerCase(Locale.ROOT).matches(".*(apikey|secret|password|authorization|credential|objectkey|bucketname|presigned|signedurl).*"); }
    private void rejectSecrets(JsonNode node) {
        if (node.isObject() && node.has("$input")) {
            node.fieldNames().forEachRemaining(key -> {
                if (!Set.of("$input","secret").contains(key)) throw new IllegalArgumentException("待输入标记只能包含说明和是否为密钥，不能附带原值");
            });
            return;
        }
        if (node.isObject()) node.fields().forEachRemaining(field -> {
            if (sensitive(field.getKey()) && !field.getValue().isNull() && !field.getValue().asText().isEmpty() && !field.getValue().isObject())
                throw new IllegalArgumentException("密钥不能进入计划，请使用 {$input:字段说明,secret:true} 并在专用输入框提交");
            rejectSecrets(field.getValue());
        });
        else if (node.isArray()) node.forEach(this::rejectSecrets);
    }
    public JsonNode sanitize(JsonNode value) {
        if (value.isObject()) {
            ObjectNode copy=json.createObjectNode(); value.fields().forEachRemaining(f -> {
                if (!sensitive(f.getKey())) copy.set(f.getKey(),sanitize(f.getValue()));
            }); return copy;
        }
        if (value.isArray()) { ArrayNode copy=json.createArrayNode(); value.forEach(v -> copy.add(sanitize(v))); return copy; }
        if (value.isTextual() && value.asText().matches("(?is).*(X-Amz-|x-oss-signature|[?&]Signature=).*")) return new TextNode("私有媒体地址请在原业务页面查看");
        return value;
    }
}
