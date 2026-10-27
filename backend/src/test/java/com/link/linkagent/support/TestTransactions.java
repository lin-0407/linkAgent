package com.link.linkagent.support;

import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 单测用的事务模板工具。
 * <p>
 * 为什么需要它：业务方法现在把模型调用排除在事务外，落库改用显式 {@link TransactionTemplate}，
 * 单测构造服务时必须给它一份模板。这里返回的模板只负责"立即执行回调"，不做真实的提交和回滚，
 * 因为单测用 Mockito 模拟 Mapper，没有数据库可提交。
 * <p>
 * 故意用 Mockito mock 而不是自己写一个事务管理器：仓库里既有的测试会做
 * {@code verifyNoInteractions(transactionTemplate)}，用来证明某些校验失败时根本没有进入事务，
 * 只有 mock 才能保留这类断言。真实事务语义（模型调用点没有活动事务、落库阶段有活动事务、
 * 落库失败整体回滚）由 {@code CreatorTransactionBoundaryTest} 用真实
 * {@code DataSourceTransactionManager} 单独验证，不能用这里的假模板代替。
 */
public final class TestTransactions {

    private TestTransactions() {
    }

    public static TransactionTemplate directTemplate() {
        TransactionTemplate template = mock(TransactionTemplate.class);
        when(template.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction((TransactionStatus) null);
        });
        doAnswer(invocation -> {
            Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(null);
            return null;
        }).when(template).executeWithoutResult(any());
        return template;
    }
}
