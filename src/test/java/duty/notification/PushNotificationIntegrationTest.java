package duty.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import duty.identity.domain.AppUser;
import duty.identity.infrastructure.AppUserRepository;
import duty.notification.domain.PushSubscription;
import duty.notification.infrastructure.PushSubscriptionRepository;
import duty.notification.infrastructure.WebPushClient;
import duty.support.AbstractIntegrationTest;
import duty.support.Fixtures;
import duty.workflow.application.ManualAssignmentService;
import duty.workflow.application.PriorityService;
import duty.workflow.infrastructure.ComplaintRepository;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web Push (section 9.6): registering a phone, what an assignment and an escalation send, and how a
 * device that stops answering is revoked. The push service itself is mocked.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PushNotificationIntegrationTest extends AbstractIntegrationTest {

    private static final String P256DH = "BNcRdreALRFXTkOOUHK1EtK2wtaz5Ry4YfYCA_0QTpQ";
    private static final String AUTH = "tBHItJI5svbpez7KI4CCXg";

    @MockBean private WebPushClient client;

    @Autowired private MockMvc mockMvc;
    @Autowired private ManualAssignmentService assignment;
    @Autowired private PriorityService priorities;
    @Autowired private PushSubscriptionRepository subscriptions;
    @Autowired private ComplaintRepository complaints;
    @Autowired private AppUserRepository users;
    @Autowired private JdbcTemplate jdbc;

    private AppUser manager;
    private AppUser ravi;
    private TestingAuthenticationToken asRavi;
    private String endpoint;

    @BeforeEach
    void pushIsConfigured() throws Exception {
        manager = users.saveAndFlush(Fixtures.manager());
        ravi = users.saveAndFlush(Fixtures.attender());
        asRavi = Fixtures.signIn(ravi);
        SecurityContextHolder.clearContext();
        endpoint = "https://push.example/" + Fixtures.unique("ravi");
        when(client.enabled()).thenReturn(true);
        when(client.publicKey()).thenReturn("BPublicVapidKey");
        when(client.send(any(), any(), any(), any())).thenReturn(201);
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    private void subscribe(String p256dh) throws Exception {
        String body =
                """
                {"endpoint":"%s","expirationTime":null,"keys":{"p256dh":"%s","auth":"%s"}}
                """
                        .formatted(endpoint, p256dh, AUTH);
        mockMvc.perform(
                        post("/api/attender/push/subscribe")
                                .with(authentication(asRavi))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isNoContent());
    }

    /** A complaint in Room 214, assigned to Ravi by the manager; returns its id. */
    private long assignAComplaintToRavi() {
        long complaintId = complaints.saveAndFlush(Fixtures.complaintInTriage()).getId();
        jdbc.update("UPDATE complaint SET reporter_room = '214' WHERE id = ?", complaintId);
        Fixtures.signIn(manager);
        assignment.assign(complaintId, ravi.getId());
        SecurityContextHolder.clearContext();
        return complaintId;
    }

    private PushSubscription device() {
        return subscriptions.findByEndpoint(endpoint).orElseThrow();
    }

    private String sentPayload() throws Exception {
        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(client).send(eq(endpoint), eq(P256DH), eq(AUTH), payload.capture());
        return new String(payload.getValue(), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("the page gets the VAPID key, and the same phone subscribing again updates its row (E27)")
    void aPhoneIsRegisteredOnce() throws Exception {
        mockMvc.perform(get("/api/attender/push/key").with(authentication(asRavi)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.publicKey").value("BPublicVapidKey"));

        subscribe("BOldKey");
        subscribe(P256DH);

        assertThat(subscriptions.findByUserIdAndRevokedAtIsNull(ravi.getId())).hasSize(1);
        assertThat(device().getP256dhKey()).isEqualTo(P256DH);
    }

    @Test
    @DisplayName("an assignment is pushed after commit with the reference and room, and none of the resident's text")
    void anAssignmentIsPushed() throws Exception {
        subscribe(P256DH);
        long complaintId = assignAComplaintToRavi();

        String payload = sentPayload();
        assertThat(payload)
                .contains("New task: Room 214")
                .contains(complaints.findById(complaintId).orElseThrow().getPublicRef())
                .doesNotContain("No water");
        assertThat(device().getLastOkAt()).isNotNull();
    }

    @Test
    @DisplayName("work that ages to P1 is pushed again to whoever holds it")
    void anEscalationToP1IsPushed() throws Exception {
        subscribe(P256DH);
        long complaintId = assignAComplaintToRavi();
        jdbc.update(
                "UPDATE complaint SET sla_due_at = now() - interval '30 days' WHERE id = ?", complaintId);

        priorities.ageOpenComplaints();

        ArgumentCaptor<byte[]> payloads = ArgumentCaptor.forClass(byte[].class);
        verify(client, times(2)).send(eq(endpoint), eq(P256DH), eq(AUTH), payloads.capture());
        assertThat(new String(payloads.getAllValues().get(1), StandardCharsets.UTF_8))
                .contains("Now urgent: Room 214");
    }

    @Test
    @DisplayName("a phone the push service says is gone (410) is revoked at once and not tried again")
    void aGoneDeviceIsRevoked() throws Exception {
        subscribe(P256DH);
        when(client.send(any(), any(), any(), any())).thenReturn(410);

        assignAComplaintToRavi();
        assertThat(device().getRevokedAt()).isNotNull();

        assignAComplaintToRavi();
        verify(client, times(1)).send(eq(endpoint), any(), any(), any());
    }

    @Test
    @DisplayName("three failures in a row revoke the phone")
    void threeFailuresRevoke() throws Exception {
        subscribe(P256DH);
        when(client.send(any(), any(), any(), any())).thenReturn(503);

        assignAComplaintToRavi();
        assignAComplaintToRavi();
        assertThat(device().getRevokedAt()).isNull();
        assertThat(device().getFailCount()).isEqualTo(2);

        assignAComplaintToRavi();
        assertThat(device().getRevokedAt()).isNotNull();
    }

    @Test
    @DisplayName("an attender who turns alerts off gets nothing more")
    void unsubscribedGetsNothing() throws Exception {
        subscribe(P256DH);
        mockMvc.perform(
                        post("/api/attender/push/unsubscribe")
                                .with(authentication(asRavi))
                                .with(csrf())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"endpoint":"%s","keys":{"p256dh":"%s","auth":"%s"}}
                                        """
                                                .formatted(endpoint, P256DH, AUTH)))
                .andExpect(status().isNoContent());

        assignAComplaintToRavi();

        verify(client, never()).send(eq(endpoint), any(), any(), any());
    }
}
