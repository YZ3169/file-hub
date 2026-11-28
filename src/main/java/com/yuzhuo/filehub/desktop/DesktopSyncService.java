package com.yuzhuo.filehub.desktop;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuzhuo.filehub.auth.UserPrincipal;
import com.yuzhuo.filehub.common.BizException;
import com.yuzhuo.filehub.config.StorageProperties;
import com.yuzhuo.filehub.entity.FileInfo;
import com.yuzhuo.filehub.entity.UserAccount;
import com.yuzhuo.filehub.mapper.FileInfoMapper;
import com.yuzhuo.filehub.mapper.UserAccountMapper;
import com.yuzhuo.filehub.service.AuditService;
import com.yuzhuo.filehub.service.FileStorageService;
import com.yuzhuo.filehub.service.FolderService;
import com.yuzhuo.filehub.util.HashUtil;
import com.yuzhuo.filehub.util.PathUtil;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 桌面端本地目录自动单向备份同步服务（仅在 {@code file-hub.desktop=true} 时启用）。
 *
 * <p>基于 Java NIO {@link WatchService} + 周期性增量核对：发现新增或修改的文件后先核对内容
 * SHA-256，内容完全一致的跳过（零 IO），否则走 {@link FileStorageService#storeStream}（自动命中秒传去重）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "file-hub.desktop", havingValue = "true")
public class DesktopSyncService {

    private static final Set<String> IGNORED_NAMES = Set.of(".DS_Store", "Thumbs.db", "desktop.ini");
    private static final long MAX_SYNC_FILE_BYTES = 2L * 1024 * 1024 * 1024;

    private final FileStorageService fileStorageService;
    private final FolderService folderService;
    private final FileInfoMapper fileInfoMapper;
    private final UserAccountMapper userMapper;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final StorageProperties storageProperties;

    @Value("${filehub.data.dir:${user.home}/FileHubData}")
    private String dataDir;

    private final List<SyncRule> rules = new CopyOnWriteArrayList<>();
    private final Map<WatchKey, WatchEntry> watchKeys = new ConcurrentHashMap<>();
    private WatchService watchService;
    private ExecutorService watcherExecutor;
    private ScheduledExecutorService syncExecutor;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SyncRule {
        private String id;
        private Long ownerId;
        private String localDir;
        private String targetDir;
        private boolean enabled;
        private int syncedFiles;
        private String lastSyncAt;
        private String lastError;
    }

    private record WatchEntry(String ruleId, Path dir) {
    }

    @PostConstruct
    public void start() {
        loadRules();
        try {
            watchService = FileSystems.getDefault().newWatchService();
            watcherExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "filehub-desktop-watcher");
                t.setDaemon(true);
                return t;
            });
            syncExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "filehub-desktop-sync");
                t.setDaemon(true);
                return t;
            });
            watcherExecutor.submit(this::watchLoop);
            syncExecutor.scheduleWithFixedDelay(this::reconcileAll, 30, 60, TimeUnit.SECONDS);

            for (SyncRule rule : rules) {
                if (rule.isEnabled()) {
                    registerRuleWatches(rule);
                }
            }
        } catch (IOException e) {
            log.warn("启动桌面目录监听失败", e);
        }
    }

    @PreDestroy
    public void stop() {
        if (watcherExecutor != null) {
            watcherExecutor.shutdownNow();
        }
        if (syncExecutor != null) {
            syncExecutor.shutdownNow();
        }
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException ignored) {
            }
        }
    }

    public List<SyncRule> listRules(UserPrincipal user) {
        return rules.stream()
                .filter(r -> user.id().equals(r.getOwnerId()))
                .map(DesktopSyncService::copyRule)
                .toList();
    }

    public SyncRule addRule(String localDirRaw, String targetDirRaw, UserPrincipal user) {
        if (localDirRaw == null || localDirRaw.isBlank()) {
            throw new BizException(10001, "请选择本地同步目录");
        }
        Path localPath = Paths.get(localDirRaw.trim()).toAbsolutePath().normalize();
        if (!Files.isDirectory(localPath) || Files.isSymbolicLink(localPath)) {
            throw new BizException(10001, "本地目录不存在或不可作为同步根目录：" + localPath);
        }
        Path realLocal;
        try {
            realLocal = localPath.toRealPath();
        } catch (IOException e) {
            throw new BizException(10001, "本地目录不可访问：" + e.getMessage());
        }
        if (realLocal.getParent() == null || realLocal.getFileName() == null) {
            throw new BizException(10001, "不能同步系统磁盘根目录");
        }
        if (isOverlappingPath(realLocal, dataDir)
                || isOverlappingPath(realLocal, storageProperties.getRoot())) {
            throw new BizException(10001, "不能同步 FileHub 自身的数据或存储目录，请另选业务目录");
        }

        String targetDir = PathUtil.normalizeDir(targetDirRaw);
        if (targetDir.isEmpty()) {
            targetDir = PathUtil.requireSafeName(realLocal.getFileName().toString());
        }

        for (SyncRule existing : rules) {
            if (user.id().equals(existing.getOwnerId())
                    && Paths.get(existing.getLocalDir()).equals(realLocal)) {
                throw new BizException(10001, "该本地目录已经在同步列表中");
            }
        }

        SyncRule rule = new SyncRule(
                UUID.randomUUID().toString(),
                user.id(),
                realLocal.toString(),
                targetDir,
                true,
                0,
                null,
                null);
        rules.add(rule);
        saveRules();
        registerRuleWatches(rule);
        if (syncExecutor != null) {
            syncExecutor.execute(() -> syncRule(rule.getId()));
        }
        return copyRule(rule);
    }

    private static boolean isOverlappingPath(Path candidate, String forbiddenRaw) {
        if (forbiddenRaw == null || forbiddenRaw.isBlank()) {
            return false;
        }
        Path forbidden = Paths.get(forbiddenRaw).toAbsolutePath().normalize();
        try {
            if (Files.exists(forbidden)) {
                forbidden = forbidden.toRealPath();
            }
        } catch (IOException ignored) {
            // 尚未创建时用规范化路径兜底比较
        }
        return candidate.equals(forbidden)
                || candidate.startsWith(forbidden)
                || forbidden.startsWith(candidate);
    }

    public SyncRule toggleRule(String ruleId, boolean enabled, UserPrincipal user) {
        SyncRule rule = requireOwnedRule(ruleId, user);
        rule.setEnabled(enabled);
        saveRules();
        if (enabled) {
            registerRuleWatches(rule);
            if (syncExecutor != null) {
                syncExecutor.execute(() -> syncRule(rule.getId()));
            }
        } else {
            unregisterRuleWatches(ruleId);
        }
        return copyRule(rule);
    }

    public SyncRule runNow(String ruleId, UserPrincipal user) {
        SyncRule rule = requireOwnedRule(ruleId, user);
        syncRule(rule.getId());
        return copyRule(rule);
    }

    public void removeRule(String ruleId, UserPrincipal user) {
        SyncRule rule = requireOwnedRule(ruleId, user);
        rules.remove(rule);
        unregisterRuleWatches(ruleId);
        saveRules();
    }

    private void reconcileAll() {
        for (SyncRule rule : rules) {
            if (rule.isEnabled()) {
                syncRule(rule.getId());
            }
        }
    }

    public void syncRule(String ruleId) {
        SyncRule rule = rules.stream().filter(r -> r.getId().equals(ruleId)).findFirst().orElse(null);
        if (rule == null || !rule.isEnabled()) {
            return;
        }
        Path root = Paths.get(rule.getLocalDir());
        if (!Files.isDirectory(root)) {
            rule.setLastError("本地目录不存在：" + root);
            saveRules();
            return;
        }
        UserAccount owner = userMapper.selectById(rule.getOwnerId());
        if (owner == null || !owner.isActive()) {
            return;
        }
        UserPrincipal principal = UserPrincipal.from(owner);

        int[] uploaded = {0};
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    if (Files.isSymbolicLink(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    if (!dir.equals(root) && isIgnoredName(name)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (Files.isSymbolicLink(file)
                            || !attrs.isRegularFile()
                            || isIgnoredName(file.getFileName().toString())) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (attrs.size() > MAX_SYNC_FILE_BYTES) {
                        return FileVisitResult.CONTINUE;
                    }
                    try {
                        if (syncSingleFile(root, file, rule.getTargetDir(), principal)) {
                            uploaded[0]++;
                        }
                    } catch (Exception e) {
                        log.warn("同步单文件失败 file={}", file, e);
                        rule.setLastError(e.getMessage());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            rule.setSyncedFiles(rule.getSyncedFiles() + uploaded[0]);
            rule.setLastSyncAt(LocalDateTime.now().toString());
            if (uploaded[0] > 0) {
                rule.setLastError(null);
                auditService.record(principal, "DESKTOP_SYNC", "FOLDER", rule.getTargetDir(),
                        "uploaded=" + uploaded[0] + " from=" + rule.getLocalDir(), "127.0.0.1");
            }
            saveRules();
        } catch (IOException e) {
            rule.setLastError(e.getMessage());
            saveRules();
        }
    }

    private boolean syncSingleFile(Path root, Path file, String targetDir, UserPrincipal user)
            throws IOException {
        if (Files.isSymbolicLink(file) || !file.toRealPath().startsWith(root.toRealPath())) {
            return false;
        }
        Path rel = root.relativize(file);
        String relUnix = rel.toString().replace('\\', '/');
        String fullRel = targetDir.isEmpty() ? relUnix : targetDir + "/" + relUnix;
        String fileName = file.getFileName().toString();
        String dir = PathUtil.parentDir(fullRel);
        String storedRel = PathUtil.toFileRelativePath(dir, fileName);

        long size = Files.size(file);
        String hash = sha256OfFile(file);

        List<FileInfo> existing = fileInfoMapper.selectList(new LambdaQueryWrapper<FileInfo>()
                .eq(FileInfo::getOwnerId, user.id())
                .eq(FileInfo::getFileName, fileName)
                .eq(FileInfo::getRelativePath, storedRel)
                .eq(FileInfo::getFileHash, hash)
                .eq(FileInfo::getFileSize, size)
                .eq(FileInfo::getStatus, FileInfo.STATUS_DONE));
        boolean exactHit = existing.stream()
                .anyMatch(f -> fileName.equals(f.getFileName())
                        && storedRel.equals(f.getRelativePath())
                        && fileStorageService.isStorageIntact(f));
        if (exactHit) {
            return false;
        }

        if (!dir.isEmpty()) {
            folderService.ensureFoldersExist(dir, user.id());
        }
        try (InputStream in = Files.newInputStream(file)) {
            fileStorageService.storeStream(fileName, storedRel, in, user);
        }
        log.info("桌面同步已上传：{} -> {}", file, fullRel);
        return true;
    }

    private void watchLoop() {
        while (watchService != null) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException | java.nio.file.ClosedWatchServiceException e) {
                return;
            }
            WatchEntry entry = watchKeys.get(key);
            if (entry != null) {
                boolean hasRelevant = false;
                for (WatchEvent<?> event : key.pollEvents()) {
                    if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                        hasRelevant = true;
                        continue;
                    }
                    Path name = (Path) event.context();
                    if (name != null && !isIgnoredName(name.toString())) {
                        Path child = entry.dir().resolve(name);
                        if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(child)) {
                            registerDirTree(entry.ruleId(), child);
                        }
                        hasRelevant = true;
                    }
                }
                if (hasRelevant && syncExecutor != null) {
                    syncExecutor.schedule(() -> syncRule(entry.ruleId()), 800, TimeUnit.MILLISECONDS);
                }
            }
            if (!key.reset()) {
                watchKeys.remove(key);
            }
        }
    }

    private void registerRuleWatches(SyncRule rule) {
        if (watchService == null) {
            return;
        }
        unregisterRuleWatches(rule.getId());
        Path root = Paths.get(rule.getLocalDir());
        if (Files.isDirectory(root)) {
            registerDirTree(rule.getId(), root);
        }
    }

    private void registerDirTree(String ruleId, Path root) {
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    if (Files.isSymbolicLink(dir)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    String name = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    if (!dir.equals(root) && isIgnoredName(name)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    WatchKey key = dir.register(watchService,
                            StandardWatchEventKinds.ENTRY_CREATE,
                            StandardWatchEventKinds.ENTRY_MODIFY);
                    watchKeys.put(key, new WatchEntry(ruleId, dir));
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("注册目录监听失败：{}", root, e);
        }
    }

    private void unregisterRuleWatches(String ruleId) {
        watchKeys.entrySet().removeIf(e -> {
            if (ruleId.equals(e.getValue().ruleId())) {
                e.getKey().cancel();
                return true;
            }
            return false;
        });
    }

    private SyncRule requireOwnedRule(String ruleId, UserPrincipal user) {
        return rules.stream()
                .filter(r -> r.getId().equals(ruleId) && user.id().equals(r.getOwnerId()))
                .findFirst()
                .orElseThrow(() -> new BizException(10005, "同步规则不存在"));
    }

    private Path rulesFile() {
        return Paths.get(dataDir).toAbsolutePath().normalize().resolve("sync-rules.json");
    }

    private void loadRules() {
        Path file = rulesFile();
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            List<SyncRule> loaded = objectMapper.readValue(file.toFile(), new TypeReference<>() {
            });
            rules.clear();
            rules.addAll(loaded);
        } catch (Exception e) {
            log.warn("读取桌面同步配置失败：{}", file, e);
        }
    }

    private synchronized void saveRules() {
        Path file = rulesFile();
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling("sync-rules.json.tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), new ArrayList<>(rules));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.warn("保存桌面同步配置失败：{}", file, e);
        }
    }

    private static boolean isIgnoredName(String name) {
        if (name == null || name.isEmpty() || IGNORED_NAMES.contains(name)) {
            return true;
        }
        return name.startsWith("~$") || name.startsWith(".")
                || name.endsWith(".tmp") || name.endsWith(".crdownload") || name.endsWith(".part");
    }

    private static String sha256OfFile(Path file) throws IOException {
        MessageDigest digest = HashUtil.sha256();
        byte[] buf = new byte[64 * 1024];
        try (InputStream in = Files.newInputStream(file);
             DigestInputStream din = new DigestInputStream(in, digest)) {
            while (din.read(buf) >= 0) {
                // read through
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static SyncRule copyRule(SyncRule r) {
        return new SyncRule(r.getId(), r.getOwnerId(), r.getLocalDir(), r.getTargetDir(),
                r.isEnabled(), r.getSyncedFiles(), r.getLastSyncAt(), r.getLastError());
    }
}
