package com.yuzhuo.filehub.auth;

import com.yuzhuo.filehub.dto.DownloadTicketResponse;
import com.yuzhuo.filehub.entity.DownloadTicket;
import com.yuzhuo.filehub.entity.UserAccount;
import com.yuzhuo.filehub.mapper.DownloadTicketMapper;
import com.yuzhuo.filehub.mapper.UserAccountMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DownloadTicketServiceTest {

    static final String FILE_ID = "1f0c2a3b-4d5e-6f70-8192-a3b4c5d6e7f8";

    @Mock
    DownloadTicketMapper ticketMapper;

    @Mock
    UserAccountMapper userMapper;

    DownloadTicketService service;

    @BeforeEach
    void setUp() {
        AuthProperties properties = new AuthProperties();
        properties.setTicketTtlMinutes(5);
        service = new DownloadTicketService(ticketMapper, userMapper, properties);
    }

    @Test
    @DisplayName("签发票据：绑定 fileId + userId，库里只存哈希")
    void issueStoresHashAndBindsFile() {
        DownloadTicketResponse response = service.issue(
                FILE_ID, new UserPrincipal(7L, "alice", "USER"));

        ArgumentCaptor<DownloadTicket> captor = ArgumentCaptor.forClass(DownloadTicket.class);
        verify(ticketMapper).insert(captor.capture());
        DownloadTicket stored = captor.getValue();
        assertEquals(FILE_ID, stored.getFileId());
        assertEquals(7L, stored.getUserId());
        assertEquals(OpaqueToken.hash(response.ticket()), stored.getTicketHash());
        assertNotEquals(response.ticket(), stored.getTicketHash());
        assertEquals("/api/file/" + FILE_ID + "/download?ticket=" + response.ticket(), response.url());
        assertTrue(stored.getExpiresAt().isAfter(LocalDateTime.now().plusMinutes(4)));
    }

    @Test
    @DisplayName("兑换票据：有效且用户可用时返回 principal")
    void resolveValidTicket() {
        String raw = "ticket".repeat(8);
        DownloadTicket ticket = new DownloadTicket();
        ticket.setFileId(FILE_ID);
        ticket.setUserId(7L);
        when(ticketMapper.findValid(eq(OpaqueToken.hash(raw)), eq(FILE_ID), any(LocalDateTime.class)))
                .thenReturn(ticket);
        when(userMapper.selectById(7L)).thenReturn(account(7L, 1));

        UserPrincipal principal = service.resolve(FILE_ID, raw);

        assertEquals(7L, principal.id());
        assertEquals("alice", principal.username());
    }

    @Test
    @DisplayName("兑换票据：无票据、查不到、用户被禁用都返回 null")
    void resolveRejectsInvalidTickets() {
        assertNull(service.resolve(FILE_ID, null));
        assertNull(service.resolve(FILE_ID, " "));
        verify(ticketMapper, never()).findValid(any(), any(), any());

        when(ticketMapper.findValid(eq(OpaqueToken.hash("unknown")), eq(FILE_ID), any(LocalDateTime.class)))
                .thenReturn(null);
        assertNull(service.resolve(FILE_ID, "unknown"));

        String raw = "disabled-ticket";
        DownloadTicket ticket = new DownloadTicket();
        ticket.setUserId(7L);
        when(ticketMapper.findValid(eq(OpaqueToken.hash(raw)), eq(FILE_ID), any(LocalDateTime.class)))
                .thenReturn(ticket);
        when(userMapper.selectById(7L)).thenReturn(account(7L, 0));
        assertNull(service.resolve(FILE_ID, raw));
    }

    @Test
    @DisplayName("签发分享票据并按 shareId 立即吊销")
    void issueWithShareIdAndRevoke() {
        DownloadTicketResponse response = service.issue(
                FILE_ID, new UserPrincipal(7L, "alice", "USER"), 42L);
        ArgumentCaptor<DownloadTicket> captor = ArgumentCaptor.forClass(DownloadTicket.class);
        verify(ticketMapper).insert(captor.capture());
        assertEquals(42L, captor.getValue().getShareId());
        assertTrue(response.url().contains("ticket="));

        when(ticketMapper.deleteByShareId(42L)).thenReturn(2);
        assertEquals(2, service.revokeByShareId(42L));
        assertEquals(0, service.revokeByShareId(null));
    }

    @Test
    @DisplayName("跨分享票据隔离：普通下载票据或不同 shareId 不能用于 resolveForShare")
    void resolveForShareRequiresMatchingShareId() {
        String raw = "share-tk".repeat(4);
        DownloadTicket regularTicket = new DownloadTicket();
        regularTicket.setFileId(FILE_ID);
        regularTicket.setUserId(7L);
        regularTicket.setShareId(null);

        when(ticketMapper.findValid(eq(OpaqueToken.hash(raw)), eq(FILE_ID), any(LocalDateTime.class)))
                .thenReturn(regularTicket);
        assertNull(service.resolveForShare(FILE_ID, 10L, raw));

        regularTicket.setShareId(99L);
        assertNull(service.resolveForShare(FILE_ID, 10L, raw));

        regularTicket.setShareId(10L);
        when(userMapper.selectById(7L)).thenReturn(account(7L, 1));
        assertEquals(7L, service.resolveForShare(FILE_ID, 10L, raw).id());
    }

    private UserAccount account(long id, int enabled) {
        UserAccount account = new UserAccount();
        account.setId(id);
        account.setUsername("alice");
        account.setRole(UserAccount.ROLE_USER);
        account.setEnabled(enabled);
        return account;
    }
}
