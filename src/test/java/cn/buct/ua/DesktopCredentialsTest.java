package cn.buct.ua;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

class DesktopCredentialsTest {
    private Path directory;
    private final List<char[]> supplied = new ArrayList<>();
    private final AtomicInteger prompts = new AtomicInteger();

    @BeforeEach
    void prepare() throws Exception {
        directory =
                Files.createTempDirectory(
                        Files.createDirectories(Path.of(".cache/tmp").toAbsolutePath()),
                        "credentials-");
        Files.createDirectories(directory.resolve("pki"));
        // 缓存只对身份文件求指纹，实际 PKCS12 验证仍由 LocalPki 执行。
        Files.writeString(directory.resolve("pki/client.p12"), "first-identity");
    }

    @AfterEach
    void clean() throws Exception {
        Path root = Path.of(".cache/tmp").toAbsolutePath().normalize();
        if (directory == null) return;
        if (!directory.normalize().startsWith(root)) throw new IllegalStateException("测试路径越界");
        try (var files = Files.walk(directory)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
        }
    }

    private ClientSettings settings(String host, String username, String extra) throws Exception {
        Path config = directory.resolve("client.properties");
        Files.writeString(
                config,
                """
                endpoint.url=opc.tcp://%s:4840/ua
                security.policy=Basic256Sha256
                security.mode=SignAndEncrypt
                identity.type=username
                identity.username=%s
                application.uri=urn:test:credentials
                application.name=测试客户端
                pki.directory=pki
                nodes.file=nodes.properties
                """
                                .formatted(host, username)
                        + extra);
        return ClientSettings.load(config);
    }

    private char[] source(String environment, String prompt) {
        prompts.incrementAndGet();
        char[] value =
                (prompt.equals("输入本机证书库密码") ? "keystore-fixture" : "user-fixture").toCharArray();
        supplied.add(value);
        return value;
    }

    private void authenticate(DesktopCredentials cache, ClientSettings settings) throws Exception {
        try (var attempt = cache.begin(settings)) {
            char[] key = attempt.apply(settings.pkiPasswordEnv(), "输入本机证书库密码");
            char[] user =
                    attempt.apply(
                            settings.passwordEnv(), "输入 OPC UA 用户 " + settings.username() + " 的密码");
            assertArrayEquals("keystore-fixture".toCharArray(), key);
            assertArrayEquals("user-fixture".toCharArray(), user);
            Arrays.fill(key, '\0');
            Arrays.fill(user, '\0');
            attempt.succeeded();
        }
    }

    @Test
    void successfulReconnectUsesClonesAndDoesNotPromptAgain() throws Exception {
        var settings = settings("one.example", "user-one", "");
        try (var cache = new DesktopCredentials(this::source)) {
            authenticate(cache, settings);
            assertEquals(2, prompts.get());
            authenticate(cache, settings);
            assertEquals(2, prompts.get());
            supplied.forEach(value -> assertArrayEquals(new char[value.length], value));
        }
    }

    @Test
    void sameEnvironmentNameDoesNotMixKeystoreAndUserPasswords() throws Exception {
        var settings =
                settings(
                        "one.example",
                        "user-one",
                        "identity.passwordEnv=SHARED\npki.passwordEnv=SHARED\n");
        try (var cache = new DesktopCredentials(this::source)) {
            authenticate(cache, settings);
            authenticate(cache, settings);
            assertEquals(2, prompts.get());
        }
    }

    @Test
    void failedInitialAuthenticationDoesNotCommitEitherSecret() throws Exception {
        var settings = settings("one.example", "user-one", "");
        try (var cache = new DesktopCredentials(this::source)) {
            try (var attempt = cache.begin(settings)) {
                attempt.apply(settings.pkiPasswordEnv(), "输入本机证书库密码");
                attempt.apply(settings.passwordEnv(), "输入 OPC UA 用户 user-one 的密码");
                // 模拟 UA 认证失败：未调用 succeeded。
            }
            authenticate(cache, settings);
            assertEquals(4, prompts.get());
        }
    }

    @Test
    void failedReconnectInvalidatesUserPasswordButKeepsVerifiedKeystore() throws Exception {
        var settings = settings("one.example", "user-one", "");
        try (var cache = new DesktopCredentials(this::source)) {
            authenticate(cache, settings);
            try (var attempt = cache.begin(settings)) {
                attempt.apply(settings.pkiPasswordEnv(), "输入本机证书库密码");
                attempt.apply(settings.passwordEnv(), "输入 OPC UA 用户 user-one 的密码");
            }
            authenticate(cache, settings);
            assertEquals(3, prompts.get());
        }
    }

    @Test
    void changingServerOrUsernameNeverReusesOtherUserCredential() throws Exception {
        try (var cache = new DesktopCredentials(this::source)) {
            authenticate(cache, settings("one.example", "user-one", ""));
            authenticate(cache, settings("two.example", "user-one", ""));
            authenticate(cache, settings("two.example", "user-two", ""));
            assertEquals(4, prompts.get());
        }
    }

    @Test
    void changingPrivateIdentityContentsForcesBothPrompts() throws Exception {
        var settings = settings("one.example", "user-one", "");
        try (var cache = new DesktopCredentials(this::source)) {
            authenticate(cache, settings);
            Files.writeString(directory.resolve("pki/client.p12"), "replacement-identity");
            authenticate(cache, settings);
            assertEquals(4, prompts.get());
        }
    }

    @Test
    void changingIdentityPathAliasOrApplicationUriIsIsolated() throws Exception {
        Files.copy(directory.resolve("pki/client.p12"), directory.resolve("pki/other.p12"));
        try (var cache = new DesktopCredentials(this::source)) {
            authenticate(cache, settings("one.example", "user-one", ""));
            authenticate(cache, settings("one.example", "user-one", "pki.file=other.p12\n"));
            authenticate(cache, settings("one.example", "user-one", "pki.alias=other-alias\n"));
            authenticate(
                    cache, settings("one.example", "user-one", "application.uri=urn:test:other\n"));
            assertEquals(8, prompts.get());
        }
    }

    @Test
    void changingSecurityPolicyRequiresUserCredentialForNewScope() throws Exception {
        try (var cache = new DesktopCredentials(this::source)) {
            authenticate(cache, settings("one.example", "user-one", ""));
            authenticate(cache, settings("one.example", "user-one", "security.policy=Basic256\n"));
            assertEquals(3, prompts.get());
        }
    }

    @Test
    void anonymousConnectionOnlyRequestsKeystoreAndCanBeReused() throws Exception {
        var settings = settings("one.example", "unused", "identity.type=anonymous\n");
        try (var cache = new DesktopCredentials(this::source)) {
            for (int run = 0; run < 2; run++) {
                try (var attempt = cache.begin(settings)) {
                    char[] value = attempt.apply(settings.pkiPasswordEnv(), "输入本机证书库密码");
                    assertArrayEquals("keystore-fixture".toCharArray(), value);
                    Arrays.fill(value, '\0');
                    attempt.succeeded();
                }
            }
            assertEquals(1, prompts.get());
        }
    }

    @Test
    void clearInvalidatesPendingAttemptAndNextConnectionPromptsAgain() throws Exception {
        var settings = settings("one.example", "user-one", "");
        try (var cache = new DesktopCredentials(this::source)) {
            authenticate(cache, settings);
            try (var pending = cache.begin(settings)) {
                pending.apply(settings.pkiPasswordEnv(), "输入本机证书库密码");
                cache.clear();
                assertThrows(IllegalStateException.class, () -> pending.succeeded());
            }
            authenticate(cache, settings);
            assertEquals(4, prompts.get());
        }
    }

    @Test
    void clearDuringSourceInputWipesReturnedArrayAndCannotReviveAttempt() throws Exception {
        var settings = settings("one.example", "user-one", "");
        var cacheRef = new DesktopCredentials[1];
        cacheRef[0] =
                new DesktopCredentials(
                        (environment, prompt) -> {
                            char[] result = source(environment, prompt);
                            cacheRef[0].clear();
                            return result;
                        });
        try (var cache = cacheRef[0];
                var attempt = cache.begin(settings)) {
            assertThrows(
                    IllegalStateException.class,
                    () -> attempt.apply(settings.pkiPasswordEnv(), "输入本机证书库密码"));
            supplied.forEach(value -> assertArrayEquals(new char[value.length], value));
        }
    }

    @Test
    void cancelledInputCannotCommitAndUnknownPurposeFailsClosed() throws Exception {
        var settings = settings("one.example", "user-one", "");
        try (var cache = new DesktopCredentials((environment, prompt) -> null);
                var attempt = cache.begin(settings)) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> attempt.apply(settings.pkiPasswordEnv(), "输入本机证书库密码"));
            assertThrows(IllegalStateException.class, attempt::succeeded);
            assertThrows(
                    IllegalArgumentException.class, () -> attempt.apply("UA_PASSWORD", "其他用途"));
        }
    }

    @Test
    void closedCacheCannotBeReopenedOrProvideCredentials() throws Exception {
        var settings = settings("one.example", "user-one", "");
        var cache = new DesktopCredentials(this::source);
        var attempt = cache.begin(settings);
        cache.close();
        assertThrows(IllegalStateException.class, () -> cache.begin(settings));
        assertThrows(
                IllegalStateException.class,
                () -> attempt.apply(settings.pkiPasswordEnv(), "输入本机证书库密码"));
        attempt.close();
    }
}
