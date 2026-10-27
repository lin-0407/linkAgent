package com.link.linkagent.creator.creation.execution;

import com.link.linkagent.creator.creation.mapper.CreationMediaMapper;
import com.link.linkagent.creator.creation.service.*;
import com.link.linkagent.creator.creation.billing.BillingExchangeRate;
import com.link.linkagent.creator.creation.storyboard.service.StoryboardCodec;
import com.link.linkagent.creator.media.config.CreatorMediaProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.*;
import java.util.*;
import static com.link.linkagent.creator.creation.storyboard.service.StoryboardCodec.conflict;

/** 既有成片的独立试映沿用原入口；任务设置过预算时，同样占用该任务预算且保留独立用户发起事实。 */
@Service @Transactional
@ConditionalOnProperty(prefix="creator.media",name="enabled",havingValue="true")
public class ManualReviewLedger {
    private final ManualReviewMapper mapper;
    private final CreationRuntimeService runtime;
    private final StoryboardCodec codec;
    private final CreationBillingProperties prices;
    private final CreatorMediaProperties media;
    private final BillingExchangeRate rates;
    private final String worker="manual-review-"+UUID.randomUUID();
    public ManualReviewLedger(ManualReviewMapper mapper,CreationRuntimeService runtime,StoryboardCodec codec,
            CreationBillingProperties prices,CreatorMediaProperties media,BillingExchangeRate rates) {
        this.mapper=mapper;this.runtime=runtime;this.codec=codec;this.prices=prices;this.media=media;this.rates=rates;
    }
    public void accept(String task,String owner,String review,String version,long durationMs) {
        var budget=runtime.snapshot(task,owner);
        if(!budget.initialized())return;
        if(mapper.byReview(review).stream().anyMatch(e->"ACTIVE".equals(e.status())))return;
        int segments=media.getPreflight().getSegmentReviewMaxCount();
        BigDecimal usd=prices.getVideoUsdUpperBound().add(prices.getTextScreeningUsdUpperBound())
                .add(prices.getSegmentReviewUsdUpperBound().multiply(BigDecimal.valueOf(segments)))
                .add(media.getProcessing().getAsrUsdPerSecond().multiply(BigDecimal.valueOf((durationMs+999)/1000)));
        BigDecimal maximum=usd.multiply(rates.current().usdToCny()).setScale(6,RoundingMode.CEILING);
        if(budget.budget().paidCallsBlocked() || maximum.compareTo(budget.budget().remainingCny())>0)
            throw conflict("独立试映估算超过当前制作预算，或有待核实费用，请先处理预算后再发起");
        var request=codec.read("{}").deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)request)
                .put("purpose","MANUAL_REVIEW").put("reviewId",review).put("versionId",version);
        var run=runtime.startPreparation(task,owner,"PREPARE","manual-"+UUID.randomUUID(),budget.workspaceRevision(),codec.write(request),maximum);
        var lease=runtime.claim(task,owner,run.runId(),worker,30).orElseThrow();
        mapper.insert(new ManualReviewMapper.Entry(review,run.runId(),task,owner,version,"ACTIVE"));
        runtime.waitForDependency(task,owner,run.runId(),worker,lease.leaseGeneration(),review);
    }
    public boolean cancelled(ManualReviewMapper.Entry entry){return "CANCEL_REQUESTED".equals(runtime.getRun(entry.taskId(),entry.ownerId(),entry.runId()).status());}
    public void beforeRetry(String task,String owner,String review){for(var entry:mapper.byReview(review))if(entry.taskId().equals(task) && entry.ownerId().equals(owner) && "ACTIVE".equals(entry.status()))advance(entry);}
    public void cancel(String task,String owner,String review){for(var entry:mapper.byReview(review))if(entry.taskId().equals(task) && entry.ownerId().equals(owner) && "ACTIVE".equals(entry.status()))runtime.cancel(task,owner,entry.runId());}
    public void advance(ManualReviewMapper.Entry entry) {
        String task=entry.taskId(),owner=entry.ownerId();var run=runtime.getRun(task,owner,entry.runId());
        String review=mapper.reviewStatus(entry.reviewId(),task,owner).orElse("FAILED");
        if(Set.of("COMPLETED","CANCELLED","FAILED").contains(run.status())){mapper.finish(new ManualReviewMapper.Entry(entry.reviewId(),entry.runId(),task,owner,entry.versionId(),run.status()));return;}
        if(!"CANCEL_REQUESTED".equals(run.status()) && !Set.of("COMPLETED","FAILED","CANCELLED").contains(review))return;
        if("CANCELLED".equals(review) && !"CANCEL_REQUESTED".equals(run.status())){runtime.cancel(task,owner,run.runId());run=runtime.getRun(task,owner,run.runId());}
        if("WAITING_DEPENDENCY".equals(run.status())) {runtime.wake(task,owner,run.runId(),run.runRevision());run=runtime.getRun(task,owner,run.runId());}
        var claim=runtime.claim(task,owner,run.runId(),worker,30);if(claim.isEmpty())return;var lease=claim.get();
        if("CANCEL_REQUESTED".equals(lease.status()))runtime.acknowledgeCancellation(task,owner,run.runId(),worker,lease.leaseGeneration());
        else if("COMPLETED".equals(review))runtime.complete(task,owner,run.runId(),worker,lease.leaseGeneration(),codec.write(Map.of("reviewId",entry.reviewId(),"versionId",entry.versionId())));
        else runtime.fail(task,owner,run.runId(),worker,lease.leaseGeneration(),"MANUAL_REVIEW_FAILED");
        mapper.finish(new ManualReviewMapper.Entry(entry.reviewId(),entry.runId(),task,owner,entry.versionId(),runtime.getRun(task,owner,run.runId()).status()));
    }
}
