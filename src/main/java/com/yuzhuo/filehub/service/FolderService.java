package com.yuzhuo.filehub.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.yuzhuo.filehub.auth.UserPrincipal;
import com.yuzhuo.filehub.common.BizException;
import com.yuzhuo.filehub.common.LockRegistry;
import com.yuzhuo.filehub.dto.BatchOpResponse;
import com.yuzhuo.filehub.dto.FileVO;
import com.yuzhuo.filehub.dto.FolderTreeResponse;
import com.yuzhuo.filehub.dto.FolderTreeResponse.Breadcrumb;
import com.yuzhuo.filehub.dto.FolderTreeResponse.FolderItem;
import com.yuzhuo.filehub.dto.ZipTicketRequest;
import com.yuzhuo.filehub.dto.ZipTicketResponse;
import com.yuzhuo.filehub.entity.FileFolder;
import com.yuzhuo.filehub.entity.FileInfo;
import com.yuzhuo.filehub.mapper.FileFolderMapper;
import com.yuzhuo.filehub.mapper.FileInfoMapper;
import com.yuzhuo.filehub.storage.LocalDiskStorage;
import com.yuzhuo.filehub.util.FileMediaTypeResolver;
import com.yuzhuo.filehub.util.PathUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 文件夹层级浏览、显式目录管理（新建/重命名/移动/删目录）与批量打包清单解析。
 *
 * <p>目录树同时融合「显式建出的空目录（{@code file_folder}）」与「文件夹上传带来的隐式目录
 * （{@code file_info.relative_path}）」，并始终在 Java 层按大小写精确匹配，避免 MySQL
 * 默认排序规则将 {@code Folder/} 和 {@code folder/} 混成同一个目录。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FolderService {

    private static final long MAX_ZIP_TOTAL_BYTES = 2L * 1024 * 1024 * 1024;

    private final FileInfoMapper fileInfoMapper;
    private final FileFolderMapper fileFolderMapper;
    private final FileStorageService fileStorageService;
    private final ZipTicketService zipTicketService;
    private final LockRegistry locks;

    public FolderTreeResponse tree(String rawDir, String keyword, String sort,
                                   UserPrincipal user, String scope) {
        return tree(rawDir, keyword, null, sort, user, scope);
    }

    public FolderTreeResponse tree(String rawDir, String keyword, String category, String sort,
                                   UserPrincipal user, String scope) {
        FileMediaTypeResolver.extensionsForCategory(category);
        String dir = PathUtil.normalizeDir(rawDir);
        String prefix = dir.isEmpty() ? "" : dir + "/";
        boolean allScope = user.isAdmin() && "all".equalsIgnoreCase(scope);

        List<FileInfo> allDone = allScope
                ? fileInfoMapper.selectList(new LambdaQueryWrapper<FileInfo>()
                        .eq(FileInfo::getStatus, FileInfo.STATUS_DONE))
                : fileInfoMapper.listDoneByOwner(user.id(), user.isAdmin());

        List<FileFolder> explicitFolders = allScope
                ? fileFolderMapper.selectList(null)
                : fileFolderMapper.listByOwner(user.id());

        Map<String, FolderAccumulator> folderMap = new LinkedHashMap<>();
        for (FileFolder folder : explicitFolders) {
            String fp = folder.getFolderPath();
            if (fp == null || fp.isEmpty() || fp.equals(dir) || !fp.startsWith(prefix)) {
                continue;
            }
            String rest = fp.substring(prefix.length());
            int slash = rest.indexOf('/');
            String childName = slash >= 0 ? rest.substring(0, slash) : rest;
            if (!childName.isEmpty()) {
                String childPath = prefix + childName;
                folderMap.computeIfAbsent(childPath, k -> new FolderAccumulator(childName, childPath))
                        .touch(folder.getUpdateTime());
            }
        }

        List<FileInfo> directFiles = new ArrayList<>();
        for (FileInfo info : allDone) {
            String rel = info.getRelativePath() == null ? "" : info.getRelativePath();
            if (dir.isEmpty()) {
                int slash = rel.indexOf('/');
                if (rel.isEmpty() || slash < 0) {
                    directFiles.add(info);
                } else {
                    String childName = rel.substring(0, slash);
                    String childPath = childName;
                    folderMap.computeIfAbsent(childPath, k -> new FolderAccumulator(childName, childPath))
                            .addFile(info.getFileSize(), info.getUpdateTime());
                }
            } else if (rel.startsWith(prefix)) {
                String rest = rel.substring(prefix.length());
                int slash = rest.indexOf('/');
                if (slash < 0) {
                    directFiles.add(info);
                } else {
                    String childName = rest.substring(0, slash);
                    String childPath = prefix + childName;
                    folderMap.computeIfAbsent(childPath, k -> new FolderAccumulator(childName, childPath))
                            .addFile(info.getFileSize(), info.getUpdateTime());
                }
            }
        }

        String kw = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        List<FolderItem> folders = folderMap.values().stream()
                .map(FolderAccumulator::toItem)
                .filter(f -> kw.isEmpty() || f.name().toLowerCase(Locale.ROOT).contains(kw))
                .sorted(folderComparator(sort))
                .toList();

        List<FileVO> files = directFiles.stream()
                .filter(f -> FileMediaTypeResolver.matchesCategory(f.getFileName(), category))
                .filter(f -> kw.isEmpty()
                        || f.getFileName().toLowerCase(Locale.ROOT).contains(kw)
                        || (f.getFileHash() != null && f.getFileHash().toLowerCase(Locale.ROOT).contains(kw))
                        || f.getFileId().toLowerCase(Locale.ROOT).contains(kw))
                .sorted(fileComparator(sort))
                .map(FileVO::from)
                .toList();

        return new FolderTreeResponse(dir, buildBreadcrumbs(dir), folders, files);
    }

    public FolderItem createFolder(String rawPath, UserPrincipal user) {
        String clean = PathUtil.requireNonEmptyDir(rawPath);
        ensureFoldersExist(clean, user.id());
        String name = clean.contains("/") ? clean.substring(clean.lastIndexOf('/') + 1) : clean;
        return new FolderItem(name, clean, 0L, 0L, LocalDateTime.now());
    }

    public FileVO moveFile(String fileId, String targetDir, String newName, UserPrincipal user) {
        LocalDiskStorage.requireSafeId(fileId);
        String cleanDir = PathUtil.normalizeDir(targetDir);
        return locks.write(fileId, () -> {
            FileInfo info = fileStorageService.requireInfo(fileId, user);
            if (info.getStatus() != FileInfo.STATUS_DONE) {
                throw new BizException(10006, "只能移动或重命名已完成的文件");
            }
            String effectiveName = (newName != null && !newName.isBlank())
                    ? PathUtil.requireSafeName(newName)
                    : info.getFileName();
            String newRelPath = PathUtil.toFileRelativePath(cleanDir, effectiveName);
            info.setFileName(effectiveName);
            info.setRelativePath(newRelPath);
            info.setUpdateTime(LocalDateTime.now());
            fileInfoMapper.updateById(info);

            if (!cleanDir.isEmpty()) {
                Long owner = info.getOwnerId() != null ? info.getOwnerId() : user.id();
                ensureFoldersExist(cleanDir, owner);
            }
            return FileVO.from(info);
        });
    }

    public BatchOpResponse batchMove(List<String> fileIds, String targetDir, UserPrincipal user) {
        String cleanDir = PathUtil.normalizeDir(targetDir);
        if (!cleanDir.isEmpty()) {
            ensureFoldersExist(cleanDir, user.id());
        }
        int ok = 0;
        int fail = 0;
        for (String id : new LinkedHashSet<>(fileIds)) {
            try {
                moveFile(id, cleanDir, null, user);
                ok++;
            } catch (RuntimeException e) {
                log.warn("批量移动文件失败 fileId={}", id, e);
                fail++;
            }
        }
        return new BatchOpResponse(ok, fail);
    }

    public int renameOrMoveFolder(String sourceRaw, String targetRaw, UserPrincipal user) {
        String src = PathUtil.requireNonEmptyDir(sourceRaw);
        String dst = PathUtil.requireNonEmptyDir(targetRaw);
        if (src.equals(dst)) {
            return 0;
        }
        if (dst.startsWith(src + "/")) {
            throw new BizException(10001, "不能将文件夹移动到其自身子目录下");
        }

        ensureFoldersExist(dst, user.id());
        LocalDateTime now = LocalDateTime.now();
        int changed = 0;

        // 1. 更新显式文件夹记录
        String srcPrefix = src + "/";
        for (FileFolder folder : fileFolderMapper.listByOwner(user.id())) {
            String fp = folder.getFolderPath();
            String updatedPath = null;
            if (fp.equals(src)) {
                updatedPath = dst;
            } else if (fp.startsWith(srcPrefix)) {
                updatedPath = dst + "/" + fp.substring(srcPrefix.length());
            }
            if (updatedPath != null) {
                String hash = PathUtil.sha256Hex(updatedPath);
                FileFolder existing = fileFolderMapper.findByOwnerAndHash(user.id(), hash);
                if (existing != null && !existing.getId().equals(folder.getId())) {
                    // 目标目录已存在，合并目录时删除旧的重复文件夹行
                    fileFolderMapper.deleteById(folder.getId());
                } else {
                    folder.setFolderPath(updatedPath);
                    folder.setFolderPathHash(hash);
                    folder.setUpdateTime(now);
                    fileFolderMapper.updateById(folder);
                }
                changed++;
            }
        }

        // 2. 更新该目录下全部文件的 relative_path
        List<FileInfo> owned = fileInfoMapper.selectList(new LambdaQueryWrapper<FileInfo>()
                .eq(FileInfo::getOwnerId, user.id())
                .in(FileInfo::getStatus, FileInfo.STATUS_UPLOADING, FileInfo.STATUS_DONE, FileInfo.STATUS_TRASH));
        for (FileInfo info : owned) {
            String rel = info.getRelativePath();
            if (rel != null && rel.startsWith(srcPrefix)) {
                String newRel = dst + "/" + rel.substring(srcPrefix.length());
                locks.writeVoid(info.getFileId(), () -> {
                    FileInfo fresh = fileInfoMapper.selectById(info.getId());
                    if (fresh != null && fresh.getRelativePath() != null
                            && fresh.getRelativePath().startsWith(srcPrefix)) {
                        fresh.setRelativePath(dst + "/" + fresh.getRelativePath().substring(srcPrefix.length()));
                        fresh.setUpdateTime(now);
                        fileInfoMapper.updateById(fresh);
                    }
                });
                changed++;
            }
        }
        return changed;
    }

    public int trashFolder(String rawFolderPath, UserPrincipal user) {
        String dir = PathUtil.requireNonEmptyDir(rawFolderPath);
        String prefix = dir + "/";
        int count = 0;

        List<FileInfo> files = fileInfoMapper.listDoneByOwner(user.id(), user.isAdmin());
        for (FileInfo info : files) {
            String rel = info.getRelativePath();
            if (rel != null && rel.startsWith(prefix)) {
                fileStorageService.moveToTrash(info.getFileId(), user);
                count++;
            }
        }

        for (FileFolder folder : fileFolderMapper.listByOwner(user.id())) {
            String fp = folder.getFolderPath();
            if (fp.equals(dir) || fp.startsWith(prefix)) {
                fileFolderMapper.deleteById(folder.getId());
            }
        }
        return count;
    }

    public ZipTicketResponse issueZipTicket(ZipTicketRequest req, UserPrincipal user) {
        List<ZipTicketService.ZipItem> items = new ArrayList<>();
        long totalBytes = 0L;
        String zipName;

        if (req.fileIds() != null && !req.fileIds().isEmpty()) {
            Set<String> seen = new LinkedHashSet<>(req.fileIds());
            if (seen.size() > 1000) {
                throw new BizException(10001, "单次打包最多选择 1000 个文件");
            }
            for (String fileId : seen) {
                FileInfo info = fileStorageService.requireReadableInfo(fileId, user);
                String entryPath = PathUtil.effectivePath(info.getFileName(), info.getRelativePath());
                items.add(new ZipTicketService.ZipItem(info.getFileId(), entryPath));
                totalBytes += info.getFileSize();
                if (totalBytes > MAX_ZIP_TOTAL_BYTES) {
                    throw new BizException(10001, "单次打包总大小不能超过 2 GB，请分批下载");
                }
            }
            zipName = items.size() == 1
                    ? stripExt(items.get(0).entryPath()) + ".zip"
                    : "filehub-batch-" + items.size() + ".zip";
        } else if (req.folderPath() != null) {
            String dir = PathUtil.normalizeDir(req.folderPath());
            String prefix = dir.isEmpty() ? "" : dir + "/";
            List<FileInfo> allDone = fileInfoMapper.listDoneByOwner(user.id(), user.isAdmin());
            for (FileInfo info : allDone) {
                String rel = info.getRelativePath() == null ? "" : info.getRelativePath();
                if (dir.isEmpty() || rel.startsWith(prefix)) {
                    if (!fileStorageService.isStorageIntact(info)) {
                        throw new BizException(20006, "文件物理内容缺失，无法打包：" + info.getFileName());
                    }
                    String entryPath = dir.isEmpty()
                            ? PathUtil.effectivePath(info.getFileName(), rel)
                            : rel.substring(prefix.length());
                    items.add(new ZipTicketService.ZipItem(info.getFileId(), entryPath));
                    totalBytes += info.getFileSize();
                    if (totalBytes > MAX_ZIP_TOTAL_BYTES) {
                        throw new BizException(10001, "单次打包总大小不能超过 2 GB，请分批或按子目录下载");
                    }
                    if (items.size() > 1000) {
                        throw new BizException(10001, "文件夹内文件超过 1000 个，请分批或进入子目录打包下载");
                    }
                }
            }
            if (items.isEmpty()) {
                throw new BizException(10001, "该目录下没有可下载的文件");
            }
            String folderName = dir.isEmpty()
                    ? "filehub-files"
                    : (dir.contains("/") ? dir.substring(dir.lastIndexOf('/') + 1) : dir);
            zipName = folderName + ".zip";
        } else {
            throw new BizException(10001, "请提供要打包的文件或目录");
        }

        return zipTicketService.issue(user, zipName, items, totalBytes);
    }

    public void ensureFoldersExist(String dir, Long ownerId) {
        if (dir == null || dir.isEmpty() || ownerId == null) {
            return;
        }
        String[] parts = dir.split("/");
        StringBuilder sb = new StringBuilder();
        LocalDateTime now = LocalDateTime.now();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(parts[i]);
            String path = sb.toString();
            String hash = PathUtil.sha256Hex(path);
            if (fileFolderMapper.findByOwnerAndHash(ownerId, hash) == null) {
                FileFolder row = new FileFolder();
                row.setOwnerId(ownerId);
                row.setFolderPath(path);
                row.setFolderPathHash(hash);
                row.setCreateTime(now);
                row.setUpdateTime(now);
                try {
                    fileFolderMapper.insert(row);
                } catch (DuplicateKeyException ignored) {
                    // 并发建同一目录时忽略
                }
            }
        }
    }

    private static List<Breadcrumb> buildBreadcrumbs(String dir) {
        List<Breadcrumb> list = new ArrayList<>();
        list.add(new Breadcrumb("全部文件", ""));
        if (dir == null || dir.isEmpty()) {
            return list;
        }
        String[] parts = dir.split("/");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(parts[i]);
            list.add(new Breadcrumb(parts[i], sb.toString()));
        }
        return list;
    }

    private static Comparator<FolderItem> folderComparator(String sort) {
        return switch (sort == null ? "" : sort) {
            case "time_desc" -> Comparator.comparing(FolderItem::updateTime,
                    Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(FolderItem::name);
            case "time_asc" -> Comparator.comparing(FolderItem::updateTime,
                    Comparator.nullsFirst(Comparator.naturalOrder())).thenComparing(FolderItem::name);
            case "size_desc" -> Comparator.comparingLong(FolderItem::totalBytes).reversed()
                    .thenComparing(FolderItem::name);
            case "size_asc" -> Comparator.comparingLong(FolderItem::totalBytes)
                    .thenComparing(FolderItem::name);
            default -> Comparator.comparing(FolderItem::name, String.CASE_INSENSITIVE_ORDER);
        };
    }

    private static Comparator<FileInfo> fileComparator(String sort) {
        return switch (sort == null ? "" : sort) {
            case "time_asc" -> Comparator.comparingLong(FileInfo::getId);
            case "size_desc" -> Comparator.comparingLong(FileInfo::getFileSize).reversed()
                    .thenComparing(Comparator.comparingLong(FileInfo::getId).reversed());
            case "size_asc" -> Comparator.comparingLong(FileInfo::getFileSize)
                    .thenComparing(Comparator.comparingLong(FileInfo::getId).reversed());
            case "name_asc" -> Comparator.comparing(FileInfo::getFileName, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(Comparator.comparingLong(FileInfo::getId).reversed());
            default -> Comparator.comparingLong(FileInfo::getId).reversed();
        };
    }

    private static String stripExt(String path) {
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static final class FolderAccumulator {
        final String name;
        final String path;
        long fileCount = 0L;
        long totalBytes = 0L;
        LocalDateTime updateTime;

        FolderAccumulator(String name, String path) {
            this.name = name;
            this.path = path;
        }

        void touch(LocalDateTime time) {
            if (time != null && (updateTime == null || time.isAfter(updateTime))) {
                updateTime = time;
            }
        }

        void addFile(long size, LocalDateTime time) {
            fileCount++;
            totalBytes += size;
            touch(time);
        }

        FolderItem toItem() {
            return new FolderItem(name, path, fileCount, totalBytes, updateTime);
        }
    }
}
