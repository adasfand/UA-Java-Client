package cn.buct.ua;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;

/** 仅存于当前桌面进程的凭据；一次完整认证成功后才提交缓存。 */
final class DesktopCredentials implements AutoCloseable {
    private enum Purpose {
        KEYSTORE,
        USER
    }

    private record KeyStoreScope(
            Path file, String digest, String alias, String applicationUri, String environment) {}

    private record UserScope(
            KeyStoreScope keyStore,
            String endpoint,
            String username,
            String policy,
            String mode,
            String environment) {}

    private final BiFunction<String, String, char[]> source;
    private final Map<KeyStoreScope, char[]> keyStores = new HashMap<>();
    private final Map<UserScope, char[]> users = new HashMap<>();
    private final Set<Attempt> attempts = new HashSet<>();
    private long generation;
    private boolean closed;

    DesktopCredentials(BiFunction<String, String, char[]> source) {
        this.source = Objects.requireNonNull(source);
    }

    /** 应在后台调用：库的实际路径与内容共同界定应用身份。 */
    synchronized Attempt begin(ClientSettings settings) throws Exception {
        if (closed) throw new IllegalStateException("凭据缓存已关闭");
        Path file = settings.pkiDirectory().resolve(settings.pki().fileName()).toRealPath();
        var digest = MessageDigest.getInstance("SHA-256");
        try (var in = new DigestInputStream(Files.newInputStream(file), digest)) {
            in.transferTo(OutputStream.nullOutputStream());
        }
        var keyStore =
                new KeyStoreScope(
                        file,
                        HexFormat.of().formatHex(digest.digest()),
                        settings.pki().alias(),
                        settings.applicationUri(),
                        settings.pkiPasswordEnv());
        var user =
                new UserScope(
                        keyStore,
                        settings.endpoint().normalize().toString(),
                        settings.username(),
                        settings.policy().getUri(),
                        settings.mode().name(),
                        settings.passwordEnv());
        var attempt = new Attempt(settings, keyStore, user, generation);
        attempts.add(attempt);
        return attempt;
    }

    /** 清除已经认证的缓存，也作废仍在密码窗口中等待的尝试。 */
    synchronized void clear() {
        generation++;
        keyStores.values().forEach(DesktopCredentials::wipe);
        users.values().forEach(DesktopCredentials::wipe);
        keyStores.clear();
        users.clear();
        for (Attempt attempt : attempts) attempt.discard();
        attempts.clear();
    }

    @Override
    public synchronized void close() {
        closed = true;
        clear();
    }

    private static void wipe(char[] value) {
        if (value != null) Arrays.fill(value, '\0');
    }

    /** 返回给 LocalPki 的数组均为副本，可由原连接流程独立清零。 */
    final class Attempt implements BiFunction<String, String, char[]>, AutoCloseable {
        private final ClientSettings settings;
        private final KeyStoreScope keyStore;
        private final UserScope user;
        private final long startedGeneration;
        private final Map<Purpose, char[]> pending = new EnumMap<>(Purpose.class);
        private boolean successful;
        private boolean finished;

        private Attempt(
                ClientSettings settings, KeyStoreScope keyStore, UserScope user, long generation) {
            this.settings = settings;
            this.keyStore = keyStore;
            this.user = user;
            startedGeneration = generation;
        }

        private Purpose purpose(String environment, String prompt) {
            // 两个配置允许引用同一个环境变量；不能仅按环境变量名称区分用途。
            if (settings.pkiPasswordEnv().equals(environment) && "输入本机证书库密码".equals(prompt))
                return Purpose.KEYSTORE;
            if (settings.identityType().equals("username")
                    && settings.passwordEnv().equals(environment)
                    && ("输入 OPC UA 用户 " + settings.username() + " 的密码").equals(prompt))
                return Purpose.USER;
            throw new IllegalArgumentException("当前连接请求了未知的凭据用途");
        }

        private void requireActive() {
            if (closed || finished || startedGeneration != generation)
                throw new IllegalStateException("本次凭据输入已结束或被清除，请重新连接");
        }

        @Override
        public char[] apply(String environment, String prompt) {
            Purpose purpose = purpose(environment, prompt);
            synchronized (DesktopCredentials.this) {
                requireActive();
                if (!pending.containsKey(purpose)) {
                    char[] cached =
                            purpose == Purpose.KEYSTORE ? keyStores.get(keyStore) : users.get(user);
                    if (cached != null) pending.put(purpose, cached.clone());
                }
                if (pending.containsKey(purpose)) return pending.get(purpose).clone();
            }
            // 隐藏输入窗口可能阻塞；不持有缓存锁，允许 UI 清除或退出。
            char[] provided = source.apply(environment, prompt);
            try {
                if (provided == null || provided.length == 0)
                    throw new IllegalArgumentException("凭据输入已取消或为空");
                synchronized (DesktopCredentials.this) {
                    requireActive();
                    if (!pending.containsKey(purpose)) pending.put(purpose, provided.clone());
                    return pending.get(purpose).clone();
                }
            } finally {
                wipe(provided);
            }
        }

        /** 仅在 UaConnection 已建立完整认证会话后调用。 */
        void succeeded() {
            synchronized (DesktopCredentials.this) {
                requireActive();
                if (!pending.containsKey(Purpose.KEYSTORE)
                        || (settings.identityType().equals("username")
                                && !pending.containsKey(Purpose.USER))) {
                    throw new IllegalStateException("认证所需凭据尚未完整提供");
                }
                wipe(keyStores.put(keyStore, pending.get(Purpose.KEYSTORE).clone()));
                if (settings.identityType().equals("username"))
                    wipe(users.put(user, pending.get(Purpose.USER).clone()));
                successful = true;
            }
        }

        private void discard() {
            pending.values().forEach(DesktopCredentials::wipe);
            pending.clear();
            finished = true;
        }

        @Override
        public void close() {
            synchronized (DesktopCredentials.this) {
                if (finished) return;
                // 已确认的私钥库口令在文件内容未变时仍有效。用户口令可能已在服务端变更。
                if (!successful
                        && startedGeneration == generation
                        && pending.containsKey(Purpose.USER)) wipe(users.remove(user));
                discard();
                attempts.remove(this);
            }
        }
    }
}
