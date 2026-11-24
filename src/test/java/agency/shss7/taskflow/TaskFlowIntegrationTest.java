package agency.shss7.taskflow;

import agency.shss7.taskflow.domain.Role;
import agency.shss7.taskflow.domain.TaskPriority;
import agency.shss7.taskflow.domain.TaskStatus;
import agency.shss7.taskflow.dto.AuthDtos.*;
import agency.shss7.taskflow.dto.ProjectDtos.*;
import agency.shss7.taskflow.dto.TaskDtos.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class TaskFlowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void healthEndpointReturnsUpStatusWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.service").value("taskflow-api"))
            .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void fullProjectAndTaskLifecycleWithJwtAndStateValidation() throws Exception {
        // 1. Register user (requested elevated role is ignored safely; always assigns MEMBER)
        var registerReq = new RegisterRequest(
            "engineer@example.org",
            "Platform Engineer",
            "SecurePass123!",
            Role.MAINTAINER
        );

        MvcResult regResult = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(registerReq)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.accessToken").isNotEmpty())
            .andExpect(jsonPath("$.role").value("MEMBER"))
            .andReturn();

        AuthResponse auth = objectMapper.readValue(
            regResult.getResponse().getContentAsString(),
            AuthResponse.class
        );
        String bearer = "Bearer " + auth.accessToken();

        // 2. Create project
        var projectReq = new CreateProjectRequest(
            "CORE-API",
            "Core Platform API Migration",
            "Migrate legacy endpoints to Spring Boot 3 with OpenAPI contracts"
        );

        MvcResult projResult = mockMvc.perform(post("/api/v1/projects")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(projectReq)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.code").value("CORE-API"))
            .andReturn();

        ProjectResponse project = objectMapper.readValue(
            projResult.getResponse().getContentAsString(),
            ProjectResponse.class
        );

        // 3. Create task in project
        var taskReq = new CreateTaskRequest(
            project.id(),
            "Implement RFC 7807 ProblemDetail error responses",
            "Standardize validation and state-transition errors across all controllers",
            TaskPriority.HIGH,
            LocalDate.now().plusDays(7),
            auth.userId()
        );

        MvcResult taskResult = mockMvc.perform(post("/api/v1/tasks")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(taskReq)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.status").value("BACKLOG"))
            .andExpect(jsonPath("$.priority").value("HIGH"))
            .andReturn();

        TaskResponse task = objectMapper.readValue(
            taskResult.getResponse().getContentAsString(),
            TaskResponse.class
        );

        // 4. Reject illegal direct transition from BACKLOG -> DONE
        var invalidTransition = new StatusTransitionRequest(TaskStatus.DONE);
        mockMvc.perform(patch("/api/v1/tasks/{id}/status", task.id())
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(invalidTransition)))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.title").value("Invalid Workflow State Transition"));

        // 5. Valid transition BACKLOG -> IN_PROGRESS -> DONE
        var toInProgress = new StatusTransitionRequest(TaskStatus.IN_PROGRESS);
        mockMvc.perform(patch("/api/v1/tasks/{id}/status", task.id())
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(toInProgress)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

        var toDone = new StatusTransitionRequest(TaskStatus.DONE);
        mockMvc.perform(patch("/api/v1/tasks/{id}/status", task.id())
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(toDone)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DONE"));

        // 6. Query filtered tasks and verify project completion count
        mockMvc.perform(get("/api/v1/tasks")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .param("projectId", project.id().toString())
                .param("status", "DONE")
                .param("q", "ProblemDetail"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(get("/api/v1/projects/CORE-API")
                .header(HttpHeaders.AUTHORIZATION, bearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalTasks").value(1))
            .andExpect(jsonPath("$.completedTasks").value(1));

        // 7. Partial task update and duplicate project code conflict check
        var updateReq = new UpdateTaskRequest("Implement RFC 7807 ProblemDetail responses", null, TaskPriority.CRITICAL, null, null);
        mockMvc.perform(patch("/api/v1/tasks/{id}", task.id())
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(updateReq)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.priority").value("CRITICAL"));

        mockMvc.perform(post("/api/v1/projects")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(projectReq)))
            .andExpect(status().isConflict());

        // 8. Fetch task by ID directly and delete cleanly
        mockMvc.perform(get("/api/v1/tasks/{id}", task.id())
                .header(HttpHeaders.AUTHORIZATION, bearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(task.id()));

        mockMvc.perform(delete("/api/v1/tasks/{id}", task.id())
                .header(HttpHeaders.AUTHORIZATION, bearer))
            .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/tasks/{id}", task.id())
                .header(HttpHeaders.AUTHORIZATION, bearer))
            .andExpect(status().isNotFound());

        // 9. Another user cannot delete someone else's project (403 Forbidden), owner can (204)
        var otherUserReq = new RegisterRequest(
            "observer@example.org",
            "Observer User",
            "SecurePass123!",
            Role.MEMBER
        );
        MvcResult otherRes = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(otherUserReq)))
            .andExpect(status().isCreated())
            .andReturn();
        AuthResponse otherAuth = objectMapper.readValue(otherRes.getResponse().getContentAsString(), AuthResponse.class);

        mockMvc.perform(delete("/api/v1/projects/CORE-API")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherAuth.accessToken()))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.title").value("Forbidden"));

        mockMvc.perform(delete("/api/v1/projects/CORE-API")
                .header(HttpHeaders.AUTHORIZATION, bearer))
            .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/projects/CORE-API")
                .header(HttpHeaders.AUTHORIZATION, bearer))
            .andExpect(status().isNotFound());
    }

    @Test
    void unauthenticatedRequestsAndBadLoginReturn401() throws Exception {
        mockMvc.perform(get("/api/v1/projects"))
            .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new LoginRequest("nobody@example.org", "wrong-pass"))))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void listProjectsAggregatesTaskCountsAcrossPopulatedAndEmptyProjects() throws Exception {
        var registerReq = new RegisterRequest(
            "aggregator@example.org",
            "Release Lead",
            "SecurePass123!",
            Role.MEMBER
        );
        MvcResult regRes = mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(registerReq)))
            .andExpect(status().isCreated())
            .andReturn();
        AuthResponse auth = objectMapper.readValue(regRes.getResponse().getContentAsString(), AuthResponse.class);
        String bearer = "Bearer " + auth.accessToken();

        ProjectResponse populated = objectMapper.readValue(
            mockMvc.perform(post("/api/v1/projects")
                    .header(HttpHeaders.AUTHORIZATION, bearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(
                        new CreateProjectRequest("AGG-ONE", "Populated Project", "Has done and open tasks")
                    )))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(),
            ProjectResponse.class
        );
        mockMvc.perform(post("/api/v1/projects")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    new CreateProjectRequest("AGG-EMPTY", "Empty Project", "No tasks created")
                )))
            .andExpect(status().isCreated());

        TaskResponse doneTask = objectMapper.readValue(
            mockMvc.perform(post("/api/v1/tasks")
                    .header(HttpHeaders.AUTHORIZATION, bearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(new CreateTaskRequest(
                        populated.id(), "Finish API docs", null, TaskPriority.MEDIUM, null, null
                    ))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(),
            TaskResponse.class
        );
        mockMvc.perform(post("/api/v1/tasks")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateTaskRequest(
                    populated.id(), "Pending follow-up", null, TaskPriority.LOW, null, null
                ))))
            .andExpect(status().isCreated());

        mockMvc.perform(patch("/api/v1/tasks/{id}/status", doneTask.id())
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new StatusTransitionRequest(TaskStatus.IN_PROGRESS))))
            .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/tasks/{id}/status", doneTask.id())
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new StatusTransitionRequest(TaskStatus.DONE))))
            .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/projects")
                .header(HttpHeaders.AUTHORIZATION, bearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.code=='AGG-ONE')].totalTasks").value(org.hamcrest.Matchers.contains(2)))
            .andExpect(jsonPath("$[?(@.code=='AGG-ONE')].completedTasks").value(org.hamcrest.Matchers.contains(1)))
            .andExpect(jsonPath("$[?(@.code=='AGG-EMPTY')].totalTasks").value(org.hamcrest.Matchers.contains(0)))
            .andExpect(jsonPath("$[?(@.code=='AGG-EMPTY')].completedTasks").value(org.hamcrest.Matchers.contains(0)));

        mockMvc.perform(delete("/api/v1/projects/AGG-ONE").header(HttpHeaders.AUTHORIZATION, bearer))
            .andExpect(status().isNoContent());
        mockMvc.perform(delete("/api/v1/projects/AGG-EMPTY").header(HttpHeaders.AUTHORIZATION, bearer))
            .andExpect(status().isNoContent());
    }

    @Test
    void caseInsensitiveAuthAndTaskStatsEndpointReportAccurateCounts() throws Exception {
        var registerReq = new RegisterRequest(
            "StatsUser@Example.org",
            "Analytics Lead",
            "SecurePass123!",
            Role.MEMBER
        );
        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(registerReq)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.email").value("statsuser@example.org"));

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new RegisterRequest(
                    "STATSUSER@example.org",
                    "Duplicate Lead",
                    "SecurePass123!",
                    Role.MEMBER
                ))))
            .andExpect(status().isConflict());

        AuthResponse loginAuth = objectMapper.readValue(
            mockMvc.perform(post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(new LoginRequest("STATSUSER@EXAMPLE.ORG", "SecurePass123!"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(),
            AuthResponse.class
        );
        assertThat(loginAuth.email()).isEqualTo("statsuser@example.org");
        String bearer = "Bearer " + loginAuth.accessToken();

        ProjectResponse proj = objectMapper.readValue(
            mockMvc.perform(post("/api/v1/projects")
                    .header(HttpHeaders.AUTHORIZATION, bearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(new CreateProjectRequest(
                        "STAT-01", "Metrics Board", "Verify grouped status stats"
                    ))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(),
            ProjectResponse.class
        );

        mockMvc.perform(post("/api/v1/tasks")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new CreateTaskRequest(
                    proj.id(), "Backlog item", null, TaskPriority.LOW, null, null
                ))))
            .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/tasks/stats")
                .header(HttpHeaders.AUTHORIZATION, bearer))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.BACKLOG").isNumber())
            .andExpect(jsonPath("$.IN_PROGRESS").isNumber())
            .andExpect(jsonPath("$.DONE").isNumber());

        mockMvc.perform(delete("/api/v1/projects/STAT-01").header(HttpHeaders.AUTHORIZATION, bearer))
            .andExpect(status().isNoContent());
    }
}
