package com.link.linkagent.creator.bilibili.mapper;

import com.link.linkagent.creator.bilibili.model.BilibiliAccountRecord;
import com.link.linkagent.creator.bilibili.model.BilibiliVideoRecord;
import com.link.linkagent.creator.bilibili.model.TaskVideoBindingRecord;
import com.link.linkagent.creator.bilibili.model.VideoAnalysisReportRecord;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B站 Mapper 的 JavaBean 映射约束测试。
 * <p>
 * MyBatis 的 @Results 需要调用 setter 回填属性，持久化模型不能使用 Java record。
 * 该测试不连接数据库，只锁定这条项目约定，防止同类异常在运行期再次出现。
 */
class CreatorBilibiliMapperMappingTest {

    @Test
    void shouldExposeJavaBeanConstructionAndSetterForPersistentModels() throws NoSuchMethodException {
        assertJavaBean(BilibiliAccountRecord.class);
        assertJavaBean(BilibiliVideoRecord.class);
        assertJavaBean(TaskVideoBindingRecord.class);
        assertJavaBean(VideoAnalysisReportRecord.class);
    }

    @Test
    void shouldUseNamedSetterResultMapsForAllRecordQueries() throws NoSuchMethodException {
        assertUsesResults("findAccountByUserId", String.class);
        assertUsesResults("findVideoByBvidAndUid", String.class, String.class);
        assertUsesResultMap("listVideosByUid", String.class);
        assertUsesResults("findBindingByTaskId", String.class);
        assertUsesResultMap("findBindingsByBvid", String.class);
        assertUsesResultMap("listBindingsByUid", String.class);
        assertUsesResultMap("listBindingsByUserId", String.class);
        assertUsesResults("findAnalysisReportByTaskId", String.class);
    }

    /**
     * 最小 JavaBean 合约：不是 record，有无参构造，且至少能让 MyBatis 设置主键属性。
     * 其它字段也按同一模式声明 setter，由 @Results 中的属性名逐一对应。
     */
    private void assertJavaBean(Class<?> modelType) throws NoSuchMethodException {
        assertThat(modelType.isRecord()).isFalse();
        assertThat(modelType.getDeclaredConstructor()).isNotNull();
        assertThat(modelType.getMethod("setId", Long.class)).isNotNull();
    }

    private void assertUsesResults(String methodName, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Method method = CreatorBilibiliMapper.class.getMethod(methodName, parameterTypes);
        assertThat(method.getAnnotation(Results.class)).isNotNull();
    }

    private void assertUsesResultMap(String methodName, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        Method method = CreatorBilibiliMapper.class.getMethod(methodName, parameterTypes);
        assertThat(method.getAnnotation(ResultMap.class)).isNotNull();
    }
}
