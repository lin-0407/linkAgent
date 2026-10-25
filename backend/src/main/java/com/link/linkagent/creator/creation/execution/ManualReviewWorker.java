package com.link.linkagent.creator.creation.execution;

import com.link.linkagent.creator.media.preflight.service.PreflightReviewService;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(prefix="creator.media",name="enabled",havingValue="true")
public class ManualReviewWorker {
    private static final Logger log=LoggerFactory.getLogger(ManualReviewWorker.class);
    private final ManualReviewMapper mapper;private final ManualReviewLedger ledger;private final PreflightReviewService preflight;
    public ManualReviewWorker(ManualReviewMapper mapper,ManualReviewLedger ledger,PreflightReviewService preflight){this.mapper=mapper;this.ledger=ledger;this.preflight=preflight;}
    @Scheduled(fixedDelayString="${creator.creation.worker.poll-interval-ms:2000}")
    public void poll(){for(var entry:mapper.candidates())try{
        if(ledger.cancelled(entry))preflight.cancel(entry.ownerId(),entry.taskId(),entry.reviewId());
        ledger.advance(entry);
    }catch(RuntimeException error){log.info("独立试映费用收尾等待恢复，试映={}，类型={}",entry.reviewId(),error.getClass().getSimpleName());}}
}
