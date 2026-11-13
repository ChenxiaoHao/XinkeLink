package cn.xinkelink.storage;

import cn.xinkelink.media.MediaContentReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class OssStorageConfigurationTest {
    @TempDir Path directory;
    private ApplicationContextRunner context() {
        return new ApplicationContextRunner().withInitializer(context -> context.getEnvironment().setActiveProfiles("auth"))
                .withUserConfiguration(LocalImageStorage.class, OssImageStorage.class, OssStorageConfiguration.class)
                .withPropertyValues("XINKELINK_IMAGE_DIRECTORY=" + directory);
    }
    private ApplicationContextRunner cloud() {
        return context().withPropertyValues("xinkelink.storage.provider=oss", "XINKELINK_OSS_BUCKET=example-dev-bucket",
                "XINKELINK_OSS_REGION=cn-beijing", "XINKELINK_OSS_ENDPOINT=https://oss-cn-beijing.aliyuncs.com",
                "XINKELINK_OSS_ACCESS_KEY_ID=unit-test-only", "XINKELINK_OSS_ACCESS_KEY_SECRET=unit-test-only");
    }
    @Test void defaultLocalModeDoesNotRequireCloudCredentials() {
        context().run(context -> {
            assertNull(context.getStartupFailure());
            assertInstanceOf(LocalImageStorage.class, context.getBean(ImageStorage.class));
            assertSame(context.getBean(ImageStorage.class), context.getBean(MediaContentReader.class));
        });
    }
    @Test void cloudModeSelectsOneAdapterForBothReadAndWrite() {
        cloud().run(context -> {
            assertNull(context.getStartupFailure());
            assertInstanceOf(OssImageStorage.class, context.getBean(ImageStorage.class));
            assertSame(context.getBean(ImageStorage.class), context.getBean(MediaContentReader.class));
            assertEquals(1, context.getBeansOfType(ImageStorage.class).size());
        });
    }
    @Test void cloudModeRefusesMissingCredentialsInsteadOfFallingBackToLocal() {
        context().withPropertyValues("xinkelink.storage.provider=oss", "XINKELINK_OSS_BUCKET=", "XINKELINK_OSS_REGION=",
                "XINKELINK_OSS_ENDPOINT=", "XINKELINK_OSS_ACCESS_KEY_ID=", "XINKELINK_OSS_ACCESS_KEY_SECRET=")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }
    @Test void refusesNonOfficialOrMismatchedEndpoint() {
        cloud().withPropertyValues("XINKELINK_OSS_ENDPOINT=https://example.invalid").run(context -> assertNotNull(context.getStartupFailure()));
    }
}
