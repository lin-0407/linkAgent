package com.link.linkagent.creator.production.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.preference.mapper.CreatorPreferenceMapper;
import com.link.linkagent.creator.production.model.CreateProductionPlanRequest;
import com.link.linkagent.creator.production.model.ProductionBlueprintOutput;
import com.link.linkagent.creator.production.model.ProductionBlueprintStepOutput;
import com.link.linkagent.creator.production.model.ProductionMethod;
import com.link.linkagent.creator.production.model.ProductionVideoCategory;
import com.link.linkagent.creator.suggestion.mapper.CreatorSuggestionMapper;
import com.link.linkagent.creator.task.mapper.CreatorTaskMapper;
import com.link.linkagent.llm.LLMService;
import com.link.linkagent.llm.usage.LlmUsageContext;
import com.link.linkagent.prompt.service.PromptService;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProductionBlueprintGeneratorTest {
    @Test
    void shouldRejectObservedCircularScreeningAndPublishingSteps() {
        for (String name : List.of("本平台AI观众试映与定向修订", "打包交付与作者发布")) {
            assertThatThrownBy(() -> generator(List.of(step(name))).generate("task", request(), List.of()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("后续阶段动作");
        }
    }

    @Test
    void shouldAllowDetailedProductionWithMoreThanTwelveSteps() {
        var steps = IntStream.range(0, 14).mapToObj(i -> step("制作镜头" + i)).toList();
        var result = generator(steps).generate("task", request(), List.of());
        assertThat(result.output().steps()).hasSize(14);
        assertThat(LlmUsageContext.current()).isNull();
    }

    private ProductionBlueprintGenerator generator(List<ProductionBlueprintStepOutput> steps) {
        var llm = mock(LLMService.class);
        when(llm.chatStructured(anyString(), anyString(), eq(ProductionBlueprintOutput.class)))
                .thenAnswer(call -> {
                    assertThat(LlmUsageContext.current().taskId()).isEqualTo("task");
                    assertThat(LlmUsageContext.current().scene()).isEqualTo("制作蓝图");
                    return new ProductionBlueprintOutput("真实成片制作", "制作到可上传", steps);
                });
        var prompts = mock(PromptService.class);
        when(prompts.get(anyString())).thenReturn("制作计划");
        var tasks = mock(CreatorTaskMapper.class);
        when(tasks.listMaterialsByTaskId("task")).thenReturn(List.of());
        var suggestions = mock(CreatorSuggestionMapper.class);
        when(suggestions.findByTaskId("task")).thenReturn(Optional.empty());
        var preferences = mock(CreatorPreferenceMapper.class);
        when(preferences.listByUserId("default", 20)).thenReturn(List.of());
        return new ProductionBlueprintGenerator(llm, prompts, tasks, suggestions, preferences, new ObjectMapper());
    }

    private ProductionBlueprintStepOutput step(String name) {
        return new ProductionBlueprintStepOutput("制作", name, "生成真实文件", List.of(), List.of("执行制作"),
                List.of(), List.of("成片"), List.of("实际可播放"), "简单", true);
    }

    private CreateProductionPlanRequest request() {
        return new CreateProductionPlanRequest(ProductionVideoCategory.AI_GENERATED, ProductionMethod.AI_GENERATION,
                "轻松看原创故事的观众", "月亮维修员", 75, List.of("真实成片V1"), "20元内", List.of());
    }
}
