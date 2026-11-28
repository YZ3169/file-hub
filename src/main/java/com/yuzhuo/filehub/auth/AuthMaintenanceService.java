package com.yuzhuo.filehub.auth;

import com.yuzhuo.filehub.mapper.DownloadTicketMapper;
import com.yuzhuo.filehub.mapper.UserSessionMapper;
import com.yuzhuo.filehub.mapper.ZipTicketMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 过期会话/票据清理。登录和下载的写入路径不应该被清理任务阻塞。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AuthMaintenanceService {

    private final UserSessionMapper sessionMapper;

    private final DownloadTicketMapper ticketMapper;

    private final ZipTicketMapper zipTicketMapper;

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    public void cleanupExpired() {
        LocalDateTime now = LocalDateTime.now();
        int sessions = sessionMapper.deleteExpired(now);
        int tickets = ticketMapper.deleteExpired(now);
        int zipTickets = zipTicketMapper.deleteExpired(now);
        if (sessions > 0 || tickets > 0 || zipTickets > 0) {
            log.info("清理过期认证数据 sessions={} tickets={} zipTickets={}", sessions, tickets, zipTickets);
        }
    }
}
