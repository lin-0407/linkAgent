package com.link.linkagent.creator.creation.storyboard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.link.linkagent.creator.creation.storyboard.model.StoryboardModels.*;
import java.util.*;
import static com.link.linkagent.creator.creation.storyboard.service.StoryboardCodec.conflict;

/** 样片保留完整场景，避免为凑十几秒截断旁白；生成服务按同一场景身份限制付费范围。 */
public final class StoryboardSelection {
    private StoryboardSelection() { }

    public static List<Scene> select(List<Scene> scenes, List<String> ids) {
        if (ids == null) return scenes;
        var selected = new HashSet<>(ids);
        if (ids.isEmpty() || ids.size() > 48 || selected.size() != ids.size()
                || selected.contains(null) || !scenes.stream().map(Scene::id).toList().containsAll(ids))
            throw conflict("选择的场景不存在或身份重复，请刷新故事板");
        return scenes.stream().filter(s -> selected.contains(s.id())).toList();
    }

    public static List<String> sample(Candidate candidate) {
        var ids = new ArrayList<String>();
        long frames = 0;
        for (Scene scene : candidate.scenes()) {
            long next = Math.max(1, Math.round(scene.durationMs() * 30.0 / 1000));
            if (!ids.isEmpty() && frames + next > 450) break;
            ids.add(scene.id()); frames += next;
            if (frames >= 450) break;
        }
        return List.copyOf(ids);
    }

    public record Scope(ObjectNode brief, Candidate candidate, List<String> sceneIds, long durationMs) { }

    public static boolean prepared(Check before, Check after, Set<String> selected, Set<String> ownPrefixes) {
        Set<String> existing = new HashSet<>();
        for (Problem problem : before.problems()) existing.add(problem.sceneId() + ":" + problem.code());
        return after.problems().stream().noneMatch(p ->
                !existing.contains(p.sceneId() + ":" + p.code())
                || selected.contains(p.sceneId()) && ownPrefixes.stream().anyMatch(p.code()::startsWith));
    }

    public static Scope scope(JsonNode brief, Candidate source, List<String> ids) {
        var selected = new Candidate(select(source.scenes(), ids));
        long duration = Math.round(selected.scenes().stream()
                .mapToLong(s -> Math.max(1, Math.round(s.durationMs() * 30.0 / 1000))).sum() * 1000.0 / 30);
        ObjectNode copy = brief.deepCopy();
        if (ids != null) copy.put("targetDurationMs", duration);
        if (ids != null) for (String field : List.of("backgroundMusic", "ambience")) {
            if (copy.path(field).isObject()) {
                ObjectNode bed = (ObjectNode) copy.path(field);
                bed.put("sourceOutMs", Math.min(bed.path("sourceOutMs").asLong(), bed.path("sourceInMs").asLong() + duration));
                long available = bed.path("sourceOutMs").asLong() - bed.path("sourceInMs").asLong();
                int fadeIn = (int) Math.min(bed.path("fadeInMs").asInt(), Math.max(0, available / 2));
                bed.put("fadeInMs", fadeIn).put("fadeOutMs", Math.min(bed.path("fadeOutMs").asInt(), Math.max(0, available - fadeIn)));
            }
        }
        return new Scope(copy, selected, selected.scenes().stream().map(Scene::id).toList(), duration);
    }
}
