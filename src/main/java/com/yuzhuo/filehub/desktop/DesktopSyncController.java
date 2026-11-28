package com.yuzhuo.filehub.desktop;

import com.yuzhuo.filehub.auth.UserPrincipal;
import com.yuzhuo.filehub.common.Result;
import com.yuzhuo.filehub.desktop.DesktopSyncService.SyncRule;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/desktop/sync")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "file-hub.desktop", havingValue = "true")
public class DesktopSyncController {

    private final DesktopSyncService syncService;

    public record CreateSyncRequest(@NotBlank String localDir, String targetDir) {
    }

    public record ToggleSyncRequest(@NotNull Boolean enabled) {
    }

    @GetMapping
    public Result<List<SyncRule>> list(@AuthenticationPrincipal UserPrincipal user) {
        return Result.ok(syncService.listRules(user));
    }

    @PostMapping
    public Result<SyncRule> create(@Valid @RequestBody CreateSyncRequest req,
                                   @AuthenticationPrincipal UserPrincipal user) {
        return Result.ok(syncService.addRule(req.localDir(), req.targetDir(), user));
    }

    @PutMapping("/{id}/toggle")
    public Result<SyncRule> toggle(@PathVariable String id,
                                   @Valid @RequestBody ToggleSyncRequest req,
                                   @AuthenticationPrincipal UserPrincipal user) {
        return Result.ok(syncService.toggleRule(id, req.enabled(), user));
    }

    @PostMapping("/{id}/run")
    public Result<SyncRule> runNow(@PathVariable String id,
                                   @AuthenticationPrincipal UserPrincipal user) {
        return Result.ok(syncService.runNow(id, user));
    }

    @DeleteMapping("/{id}")
    public Result<Void> remove(@PathVariable String id,
                               @AuthenticationPrincipal UserPrincipal user) {
        syncService.removeRule(id, user);
        return Result.ok();
    }
}
