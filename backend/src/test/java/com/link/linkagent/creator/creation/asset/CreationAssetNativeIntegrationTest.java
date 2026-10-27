package com.link.linkagent.creator.creation.asset;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.link.linkagent.creator.creation.asset.controller.CreationAssetController;
import com.link.linkagent.creator.creation.asset.mapper.CreationAssetMapper;
import com.link.linkagent.creator.creation.asset.model.AssetRequests.*;
import com.link.linkagent.creator.creation.asset.service.*;
import com.link.linkagent.creator.creation.render.FixedProjectRendererProperties;
import com.link.linkagent.creator.media.config.*;
import com.link.linkagent.creator.media.storage.*;
import com.link.linkagent.creator.media.upload.model.*;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.mybatis.spring.*;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.MountableFile;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;

/** 本地真实 MySQL、S3 分片与 FFmpeg 闭环；不访问云端或收费供应商。 */
@Testcontainers
@EnabledIfEnvironmentVariable(named="LINKAGENT_ASSET_NATIVE_TESTS",matches="true")
class CreationAssetNativeIntegrationTest {
    @Container static final GenericContainer<?> MYSQL=new GenericContainer<>("mysql:8.4")
        .withEnv("MYSQL_ROOT_PASSWORD","asset-native-test").withEnv("MYSQL_ROOT_HOST","%")
        .withEnv("MYSQL_DATABASE","link_agent").withExposedPorts(3306)
        .withCopyFileToContainer(MountableFile.forClasspathResource("sql/init.sql"),"/docker-entrypoint-initdb.d/01-init.sql")
        .withLabels(Map.of("linkagent.scope","creation-s4-assets-test"))
        .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));
    @Container static final GenericContainer<?> STORE=new GenericContainer<>("rustfs/rustfs:latest")
        .withEnv("RUSTFS_ACCESS_KEY","asset-native").withEnv("RUSTFS_SECRET_KEY","asset-native-secret")
        .withCommand("rustfs","/data").withCreateContainerCmdModifier(cmd->cmd.withUser("0"))
        .withExposedPorts(9000).withLabels(Map.of("linkagent.scope","creation-s4-assets-test"))
        .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofMinutes(2)));
    static JdbcTemplate jdbc; static CreationAssetMapper mapper; static CreationAssetPersistence persistence;
    static ObjectStorageProperties storageProps; static S3Client client; static S3Presigner presigner;
    static ObjectStorageService storage; static ObjectMapper json=new ObjectMapper().findAndRegisterModules();
    @TempDir Path temp;

    @BeforeAll static void setup() throws Exception {
        var source=new DriverManagerDataSource("jdbc:mysql://"+MYSQL.getHost()+":"+MYSQL.getMappedPort(3306)+"/link_agent?useSSL=false&allowPublicKeyRetrieval=true","root","asset-native-test");
        jdbc=new JdbcTemplate(source);var conf=new Configuration();conf.setMapUnderscoreToCamelCase(true);conf.addMapper(CreationAssetMapper.class);
        var factory=new SqlSessionFactoryBean();factory.setDataSource(source);factory.setConfiguration(conf);factory.afterPropertiesSet();
        mapper=new SqlSessionTemplate(factory.getObject()).getMapper(CreationAssetMapper.class);
        var proxy=new ProxyFactory(new CreationAssetPersistence(mapper));
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),new AnnotationTransactionAttributeSource()));
        persistence=(CreationAssetPersistence)proxy.getProxy();
        storageProps=new ObjectStorageProperties();String endpoint="http://"+STORE.getHost()+":"+STORE.getMappedPort(9000);
        storageProps.setInternalEndpoint(endpoint);storageProps.setBrowserEndpoint(endpoint);storageProps.setProviderEndpoint(endpoint);
        storageProps.setRegion("us-east-1");storageProps.setAccessKey("asset-native");storageProps.setSecretKey("asset-native-secret");
        storageProps.setBucket("asset-native-test");storageProps.setPathStyleAccess(true);
        var config=new ObjectStorageConfiguration();var s3=config.mediaS3Configuration(storageProps);var credentials=config.mediaS3CredentialsProvider(storageProps);
        client=config.mediaS3Client(storageProps,s3,credentials);presigner=config.mediaS3Presigner(storageProps,s3,credentials);
        client.createBucket(b->b.bucket(storageProps.getBucket()));storage=new S3ObjectStorageService(client,presigner,presigner,storageProps);
    }
    @AfterAll static void close() {if(presigner!=null)presigner.close();if(client!=null)client.close();}

    @Test void realHttpMultipartDecodePrivateReadAndRefreshForSixFormats() throws Exception {
        String task=task();
        Path image=temp.resolve("photo.png");ImageIO.write(new BufferedImage(160,90,BufferedImage.TYPE_INT_RGB),"png",image.toFile());
        Path wave=temp.resolve("voice.wav");ffmpeg("-f","lavfi","-i","sine=frequency=440:sample_rate=48000:duration=1.5","-c:a","pcm_s16le",wave.toString());
        Path mp3=temp.resolve("music.mp3");ffmpeg("-i",wave.toString(),"-c:a","libmp3lame",mp3.toString());
        Path m4a=temp.resolve("voice.m4a");ffmpeg("-i",wave.toString(),"-c:a","aac",m4a.toString());
        Path mp4=temp.resolve("clip.mp4");ffmpeg("-f","lavfi","-i","testsrc2=size=160x90:rate=24:duration=1.5","-i",wave.toString(),"-c:v","libx264","-pix_fmt","yuv420p","-c:a","aac","-shortest",mp4.toString());
        Path mov=temp.resolve("clip.mov");ffmpeg("-i",mp4.toString(),"-c","copy",mov.toString());
        var sourceFiles=List.of(image,wave,mp3,m4a,mp4,mov);var kinds=List.of("IMAGE","NARRATION","MUSIC","NARRATION","VIDEO_CLIP","VIDEO_CLIP");
        for(int i=0;i<sourceFiles.size();i++) {
            Path source=sourceFiles.get(i);String kind=kinds.get(i);var service=service(storage);
            var mvc=mvc(service);String base="/api/creator/tasks/"+task+"/assets";
            String body=json.writeValueAsString(new Create(kind,source.getFileName().toString(),"application/octet-stream",Files.size(source),CreationAssetWorker.sha256(source,()->false)));
            String response=mvc.perform(post(base).header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            String id=json.readTree(response).path("assetId").asText();
            try(var worker=worker(service,storage)) {worker.process(mapper.find(task,"default",id).orElseThrow());}
            String signed=mvc.perform(post(base+"/"+id+"/parts:sign").contentType("application/json").content("{\"partNumbers\":[1]}"))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString();
            var put=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(json.readTree(signed).get(0).path("url").asText())).PUT(HttpRequest.BodyPublishers.ofFile(source)).build(),HttpResponse.BodyHandlers.discarding());
            assertThat(put.statusCode()).isBetween(200,299);String etag=put.headers().firstValue("etag").orElseThrow();
            mvc.perform(post(base+"/"+id+"/parts:complete").contentType("application/json").content(json.writeValueAsString(new MediaUploadPartsCompleteRequest(List.of(new CompletedMediaUploadPartRequest(1,etag,Files.size(source))))))).andExpect(status().isOk());
            mvc.perform(post(base+"/"+id+":complete")).andExpect(status().isOk());
            // 新服务实例恢复同一会话与分片，并推进真实合并和解码。
            service=service(storage);try(var worker=worker(service,storage)) {
                worker.process(mapper.find(task,"default",id).orElseThrow());worker.process(mapper.find(task,"default",id).orElseThrow());
            }
            var ready=service.get(task,"default",id);assertThat(ready.status()).withFailMessage("%s failed: %s",source,ready.failureCode()).isEqualTo("READY");
            var read=service.read(task,"default",id,"NORMALIZED");
            var downloaded=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(read.url())).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
            assertThat(downloaded.statusCode()).isEqualTo(200);assertThat(downloaded.body()).isNotEmpty();
            assertThat(mapper.pendingObjects()).noneMatch(o->o.assetId().equals(id));
            assertThat(service.get(task,"default",id).assetId()).isEqualTo(id);
            assertThatThrownBy(()->persistence.cancel(task,"default",id)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
            System.out.println("S4_ASSET_NATIVE_READY format="+source.getFileName()+" bytes="+downloaded.body().length);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM creator_draft_video WHERE task_id=?",Integer.class,task)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM creator_creation_workspace WHERE task_id=?",Integer.class,task)).isZero();
        assertThat(service(storage).list(task,"default",0,30)).hasSize(6);
    }

    @Test void actualDifferentBytesNeverBecomeReadyAndPendingDeletesRecover() throws Exception {
        String task=task();Path image=temp.resolve("source.png");ImageIO.write(new BufferedImage(16,16,BufferedImage.TYPE_INT_RGB),"png",image.toFile());
        var real=spy(storage);var service=service(real);
        String id=upload(task,image,"IMAGE","0".repeat(64),service,real);
        try(var worker=worker(service,real)) {worker.process(mapper.find(task,"default",id).orElseThrow());}
        assertThat(service.get(task,"default",id).failureCode()).isEqualTo("SHA256_MISMATCH");
        String valid=upload(task,image,"IMAGE",CreationAssetWorker.sha256(image,()->false),service,real);
        doAnswer(call->{storage.putObject(call.getArgument(0),call.getArgument(1),call.getArgument(2),call.getArgument(3),call.getArgument(4));throw new MediaStorageException("模拟响应丢失",null);}).when(real).putObject(anyString(),anyString(),any(Path.class),anyString(),any(Duration.class));
        doThrow(new MediaStorageException("模拟清理暂时不可用",null)).when(real).deleteObject(anyString(),anyString());
        try(var worker=worker(service,real)) {worker.process(mapper.find(task,"default",valid).orElseThrow());}
        assertThat(service.get(task,"default",valid).status()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM creator_creation_asset_object WHERE asset_id=? AND cleanup_done=0",Integer.class,valid)).isPositive();
        assertThat(mapper.pendingObjects()).noneMatch(o->o.assetId().equals(valid));
        jdbc.update("UPDATE creator_creation_asset_object SET cleanup_after=TIMESTAMPADD(SECOND,-1,NOW(6)) WHERE asset_id=?",valid);
        reset(real);
        try(var worker=worker(service,real)) {worker.sweepObjects();}
        assertThat(mapper.pendingObjects()).noneMatch(o->o.assetId().equals(valid));
        persistence.retry(task,"default",valid);
        try(var worker=worker(service,real)) {worker.process(mapper.find(task,"default",valid).orElseThrow());}
        assertThat(service.get(task,"default",valid).status()).isEqualTo("READY");
        System.out.println("S4_ASSET_SHA_AND_PERSISTENT_CLEANUP_OK");
    }

    @Test void twoPartUploadResumesFromPersistedPartAndCancellationAbortsRealStore() throws Exception {
        String task=task();Path source=temp.resolve("large.wav");
        try(var out=new java.io.RandomAccessFile(source.toFile(),"rw")){out.setLength(CreationAssetPersistence.PART_BYTES+31);}
        var service=service(storage);String hash=CreationAssetWorker.sha256(source,()->false);
        var a=service.create(task,"default",UUID.randomUUID().toString(),new Create("NARRATION","large.wav","audio/wav",Files.size(source),hash));
        try(var worker=worker(service,storage)){worker.process(mapper.find(task,"default",a.assetId()).orElseThrow());}
        var signed=service.sign(task,"default",a.assetId(),List.of(1));
        byte[] bytes=new byte[(int)CreationAssetPersistence.PART_BYTES];
        var put=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(signed.getFirst().url())).PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),HttpResponse.BodyHandlers.discarding());
        assertThat(put.statusCode()).isBetween(200,299);
        service.register(task,"default",a.assetId(),new MediaUploadPartsCompleteRequest(List.of(new CompletedMediaUploadPartRequest(1,put.headers().firstValue("etag").orElseThrow(),bytes.length))));
        var refreshed=service(storage);assertThat(refreshed.get(task,"default",a.assetId()).parts()).hasSize(1);
        assertThatThrownBy(()->refreshed.complete(task,"default",a.assetId())).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        refreshed.cancel(task,"default",a.assetId());try(var worker=worker(refreshed,storage)){worker.process(mapper.find(task,"default",a.assetId()).orElseThrow());}
        assertThat(refreshed.get(task,"default",a.assetId()).cleanupPending()).isTrue();
        jdbc.update("UPDATE creator_creation_asset SET lease_expires_at=TIMESTAMPADD(SECOND,-1,NOW(6)) WHERE asset_id=?",a.assetId());
        try(var worker=worker(refreshed,storage)){worker.process(mapper.find(task,"default",a.assetId()).orElseThrow());}
        assertThat(refreshed.get(task,"default",a.assetId()).cleanupPending()).isFalse();
        assertThat(client.listMultipartUploads(b->b.bucket(storageProps.getBucket())).uploads()).noneMatch(u->u.key().contains(a.assetId()));
    }

    @Test void malformedHttpRequestsAreRejectedBeforeCreatingUpload() throws Exception {
        String task=task();var mvc=mvc(service(storage));String base="/api/creator/tasks/"+task+"/assets";
        mvc.perform(post(base).header("Idempotency-Key","bad").contentType("application/json").content("{\"kind\":\"IMAGE\",\"fileName\":\"x.png\",\"fileSize\":1}")).andExpect(status().isBadRequest());
        for(String name:List.of("png","voice.aac","../x.png")) {
            mvc.perform(post(base).header("Idempotency-Key",UUID.randomUUID().toString()).contentType("application/json").content(json.writeValueAsString(new Create("IMAGE",name,"image/png",1,"a".repeat(64))))).andExpect(status().isBadRequest());
        }
        mvc.perform(post(base+"/missing/parts:complete").contentType("application/json").content("{\"parts\":[null]}" )).andExpect(status().isBadRequest());
    }

    @Test void lostCreationResponseRecoversExactRemoteUploadInsteadOfCreatingAnother() throws Exception {
        String task=task();var real=spy(storage);var service=service(real);
        var a=service.create(task,"default","lost-create",new Create("IMAGE","small.png","image/png",12,"a".repeat(64)));
        doAnswer(call->{storage.createMultipartUpload(call.getArgument(0),call.getArgument(1),call.getArgument(2));throw new MediaStorageException("模拟创建响应丢失",null);})
                .when(real).createMultipartUpload(anyString(),anyString(),any(Duration.class));
        try(var worker=worker(service,real)){worker.process(mapper.find(task,"default",a.assetId()).orElseThrow());}
        assertThat(service.get(task,"default",a.assetId()).status()).isEqualTo("FAILED");
        var before=mapper.find(task,"default",a.assetId()).orElseThrow();
        var existing=storage.listMultipartUploads(before.getBucketName(),before.getSourceKey());assertThat(existing).hasSize(1);
        reset(real);service.retry(task,"default",a.assetId());
        try(var worker=worker(service,real)){worker.process(mapper.find(task,"default",a.assetId()).orElseThrow());}
        var restored=mapper.find(task,"default",a.assetId()).orElseThrow();
        assertThat(restored.getStatus()).isEqualTo("UPLOADING");assertThat(restored.getStorageUploadId()).isEqualTo(existing.getFirst().uploadId());
        assertThat(storage.listMultipartUploads(before.getBucketName(),before.getSourceKey())).hasSize(1);
        service.cancel(task,"default",a.assetId());try(var worker=worker(service,real)){worker.process(mapper.find(task,"default",a.assetId()).orElseThrow());}
        assertThat(storage.listMultipartUploads(before.getBucketName(),before.getSourceKey())).isEmpty();
    }

    @Test void deferredCancellationsDoNotFillTheCandidateQueue() throws Exception {
        String task=task();var service=service(storage);
        for(int i=0;i<10;i++) {
            var a=service.create(task,"default","cancel-"+i,new Create("IMAGE","small.png","image/png",12,"a".repeat(64)));
            service.cancel(task,"default",a.assetId());
            try(var worker=worker(service,storage)){worker.process(mapper.find(task,"default",a.assetId()).orElseThrow());}
            assertThat(service.get(task,"default",a.assetId()).cleanupPending()).isTrue();
        }
        var next=service.create(task,"default","next",new Create("IMAGE","next.png","image/png",12,"a".repeat(64)));
        assertThat(mapper.candidates()).anyMatch(a->a.getAssetId().equals(next.assetId()));
    }

    @Test void committedReadyResponseLossAndTaskSoftDeletePreserveHistoricalFiles() throws Exception {
        String task=task();Path image=temp.resolve("history.png");ImageIO.write(new BufferedImage(16,16,BufferedImage.TYPE_INT_RGB),"png",image.toFile());
        var service=service(storage);String id=upload(task,image,"IMAGE",CreationAssetWorker.sha256(image,()->false),service,storage);
        var fault=spy(mapper);
        doAnswer(call->{
            int result=mapper.finish(call.getArgument(0),call.getArgument(1),call.getArgument(2),call.getArgument(3),call.getArgument(4),call.getArgument(5),call.getArgument(6));
            if(result==1 && "READY".equals(call.getArgument(2))) {jdbc.update("UPDATE creator_task SET is_deleted=1 WHERE task_id=?",task);throw new IllegalStateException("模拟提交成功后响应丢失");}
            return result;
        }).when(fault).finish(anyString(),anyString(),anyString(),nullable(String.class),nullable(String.class),nullable(String.class),nullable(String.class));
        var props=new FixedProjectRendererProperties();props.setWorkRoot(temp.toString());props.setTimeout(Duration.ofSeconds(45));
        try(var worker=new CreationAssetWorker(fault,service,storage,new AssetMediaPreparer(json,props),props,json)) {
            worker.process(mapper.find(task,"default",id).orElseThrow());worker.sweepObjects();
        }
        var retained=mapper.lock(id);assertThat(retained.getStatus()).isEqualTo("READY");
        assertThat(mapper.find(task,"default",id)).isEmpty();
        for(var object:service.objects(retained)) assertThat(storage.headObject(retained.getBucketName(),object.objectKey()).contentLength()).isEqualTo(object.bytes());
        assertThat(mapper.pendingObjects()).noneMatch(o->o.assetId().equals(id));
    }

    @Test void inFlightPutCancellationAndTemporaryDeleteFailureRemainDurablyRecoverable() throws Exception {
        String task=task();Path image=temp.resolve("cancel.png");ImageIO.write(new BufferedImage(16,16,BufferedImage.TYPE_INT_RGB),"png",image.toFile());
        var real=spy(storage);var service=service(real);String id=upload(task,image,"IMAGE",CreationAssetWorker.sha256(image,()->false),service,real);
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        doAnswer(call->{entered.countDown();assertThat(release.await(20,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            storage.putObject(call.getArgument(0),call.getArgument(1),call.getArgument(2),call.getArgument(3),call.getArgument(4));throw new MediaStorageException("模拟在途写入响应丢失",null);})
                .when(real).putObject(anyString(),anyString(),any(Path.class),anyString(),any(Duration.class));
        doThrow(new MediaStorageException("模拟删除暂不可用",null)).when(real).deleteObject(anyString(),anyString());
        try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor();var running=worker(service,real)) {
            var future=executor.submit(()->running.process(mapper.find(task,"default",id).orElseThrow()));
            try {
                assertThat(entered.await(20,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                service.cancel(task,"default",id);
                try(var cleanup=worker(service,real)){cleanup.sweepObjects();cleanup.process(mapper.find(task,"default",id).orElseThrow());}
                assertThat(mapper.pendingObjects()).noneMatch(o->o.assetId().equals(id));
                assertThat(service.get(task,"default",id).cleanupPending()).isTrue();
            } finally {release.countDown();}
            future.get(30,java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM creator_creation_asset_object WHERE asset_id=? AND cleanup_done=0",Integer.class,id)).isEqualTo(1);
        reset(real);
        jdbc.update("UPDATE creator_creation_asset_object SET cleanup_after=TIMESTAMPADD(SECOND,-1,NOW(6)) WHERE asset_id=?",id);
        jdbc.update("UPDATE creator_creation_asset SET lease_expires_at=TIMESTAMPADD(SECOND,-1,NOW(6)) WHERE asset_id=?",id);
        try(var cleanup=worker(service,real)){cleanup.sweepObjects();cleanup.process(mapper.find(task,"default",id).orElseThrow());}
        assertThat(service.get(task,"default",id).status()).isEqualTo("CANCELLED");assertThat(service.get(task,"default",id).cleanupPending()).isFalse();
        assertThat(client.listObjectsV2(b->b.bucket(storageProps.getBucket())).contents()).noneMatch(o->o.key().contains(id));
        System.out.println("S4_ASSET_IN_FLIGHT_CANCEL_CLEANUP_OK");
    }

    @Test void cancellationDuringUploadLookupDoesNotStartALaterCreate() throws Exception {
        String task=task();var real=spy(storage);var service=service(real);
        var a=service.create(task,"default","blocked-list",new Create("IMAGE","small.png","image/png",12,"a".repeat(64)));
        var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        doAnswer(call->{entered.countDown();assertThat(release.await(20,java.util.concurrent.TimeUnit.SECONDS)).isTrue();return List.of();})
                .when(real).listMultipartUploads(anyString(),anyString());
        try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor();var running=worker(service,real)) {
            var future=executor.submit(()->running.process(mapper.find(task,"default",a.assetId()).orElseThrow()));
            try {
                assertThat(entered.await(20,java.util.concurrent.TimeUnit.SECONDS)).isTrue();service.cancel(task,"default",a.assetId());
                jdbc.update("UPDATE creator_creation_asset SET lease_expires_at=TIMESTAMPADD(SECOND,-1,NOW(6)) WHERE asset_id=?",a.assetId());
                try(var cleanup=worker(service,storage)){cleanup.process(mapper.find(task,"default",a.assetId()).orElseThrow());}
            } finally {release.countDown();}
            future.get(30,java.util.concurrent.TimeUnit.SECONDS);
        }
        verify(real,never()).createMultipartUpload(anyString(),anyString(),any(Duration.class));
        assertThat(service.get(task,"default",a.assetId()).status()).isEqualTo("CANCELLED");
    }

    @Test void staleCreatorDoesNotAbortTheHandleBeingAdoptedByANewerLease() throws Exception {
        String task=task();var service=service(storage);
        var a=service.create(task,"default","adopt-handle",new Create("IMAGE","small.png","image/png",3,"a".repeat(64)));
        var firstEntered=new java.util.concurrent.CountDownLatch(1);var secondEntered=new java.util.concurrent.CountDownLatch(1);
        var firstRelease=new java.util.concurrent.CountDownLatch(1);var secondRelease=new java.util.concurrent.CountDownLatch(1);
        var first=spy(mapper);var second=spy(mapper);
        doAnswer(call->{firstEntered.countDown();assertThat(firstRelease.await(20,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            return mapper.finish(call.getArgument(0),call.getArgument(1),call.getArgument(2),call.getArgument(3),call.getArgument(4),call.getArgument(5),call.getArgument(6));})
                .when(first).finish(anyString(),anyString(),anyString(),nullable(String.class),nullable(String.class),nullable(String.class),nullable(String.class));
        doAnswer(call->{secondEntered.countDown();assertThat(secondRelease.await(20,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            return mapper.finish(call.getArgument(0),call.getArgument(1),call.getArgument(2),call.getArgument(3),call.getArgument(4),call.getArgument(5),call.getArgument(6));})
                .when(second).finish(anyString(),anyString(),anyString(),nullable(String.class),nullable(String.class),nullable(String.class),nullable(String.class));
        var props=new FixedProjectRendererProperties();props.setWorkRoot(temp.toString());
        try(var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
            var oldWorker=new CreationAssetWorker(first,service,storage,new AssetMediaPreparer(json,props),props,json);
            var newWorker=new CreationAssetWorker(second,service,storage,new AssetMediaPreparer(json,props),props,json)) {
            var old=executor.submit(()->oldWorker.process(mapper.find(task,"default",a.assetId()).orElseThrow()));
            java.util.concurrent.Future<?> newer=null;
            try {
                assertThat(firstEntered.await(20,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                jdbc.update("UPDATE creator_creation_asset SET lease_expires_at=TIMESTAMPADD(SECOND,-1,NOW(6)) WHERE asset_id=?",a.assetId());
                newer=executor.submit(()->newWorker.process(mapper.find(task,"default",a.assetId()).orElseThrow()));
                assertThat(secondEntered.await(20,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                firstRelease.countDown();old.get(20,java.util.concurrent.TimeUnit.SECONDS);
                var current=mapper.lock(a.assetId());assertThat(storage.listMultipartUploads(current.getBucketName(),current.getSourceKey())).hasSize(1);
            } finally {firstRelease.countDown();secondRelease.countDown();}
            if(newer!=null)newer.get(20,java.util.concurrent.TimeUnit.SECONDS);
        }
        var adopted=mapper.lock(a.assetId());assertThat(adopted.getStatus()).isEqualTo("UPLOADING");
        assertThat(client.uploadPart(b->b.bucket(adopted.getBucketName()).key(adopted.getSourceKey()).uploadId(adopted.getStorageUploadId()).partNumber(1),software.amazon.awssdk.core.sync.RequestBody.fromBytes(new byte[]{1,2,3})).eTag()).isNotBlank();
        service.cancel(task,"default",a.assetId());try(var cleanup=worker(service,storage)){cleanup.process(mapper.find(task,"default",a.assetId()).orElseThrow());}
    }

    private String upload(String task,Path source,String kind,String sha,CreationAssetService service,ObjectStorageService store) throws Exception {
        var a=service.create(task,"default",UUID.randomUUID().toString(),new Create(kind,source.getFileName().toString(),"application/octet-stream",Files.size(source),sha));
        try(var worker=worker(service,store)){worker.process(mapper.find(task,"default",a.assetId()).orElseThrow());}
        var record=mapper.find(task,"default",a.assetId()).orElseThrow();
        var part=client.uploadPart(b->b.bucket(record.getBucketName()).key(record.getSourceKey()).uploadId(record.getStorageUploadId()).partNumber(1),software.amazon.awssdk.core.sync.RequestBody.fromFile(source));
        service.register(task,"default",a.assetId(),new MediaUploadPartsCompleteRequest(List.of(new CompletedMediaUploadPartRequest(1,part.eTag(),Files.size(source)))));
        service.complete(task,"default",a.assetId());try(var worker=worker(service,store)){worker.process(mapper.find(task,"default",a.assetId()).orElseThrow());}
        return a.assetId();
    }
    private CreationAssetService service(ObjectStorageService store){return new CreationAssetService(mapper,persistence,storageProps,store,json);}
    private CreationAssetWorker worker(CreationAssetService service,ObjectStorageService store) {
        var props=new FixedProjectRendererProperties();props.setWorkRoot(temp.toString());props.setTimeout(Duration.ofSeconds(45));
        return new CreationAssetWorker(mapper,service,store,new AssetMediaPreparer(json,props),props,json);
    }
    private MockMvc mvc(CreationAssetService service){var validator=new LocalValidatorFactoryBean();validator.afterPropertiesSet();return MockMvcBuilders.standaloneSetup(new CreationAssetController(service)).setValidator(validator).build();}
    private String task(){String id=UUID.randomUUID().toString();jdbc.update("INSERT INTO creator_task(task_id,user_id,task_name,video_type,status) VALUES(?,'default','真实素材测试','测试','DRAFT')",id);return id;}
    private void ffmpeg(String...args) throws Exception {var cmd=new ArrayList<String>(List.of("ffmpeg","-hide_banner","-loglevel","error","-y","-threads","1"));cmd.addAll(List.of(args));var p=new ProcessBuilder(cmd).redirectErrorStream(true).start();var output=new String(p.getInputStream().readAllBytes());assertThat(p.waitFor()).withFailMessage(output).isZero();}
}
