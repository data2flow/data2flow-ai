package net.java21.data2flow.ai.conversation;

import net.java21.data2flow.ai.common.AiErrorCode;
import net.java21.data2flow.ai.help.DocsAnswerService;
import net.java21.data2flow.ai.safety.PiiMasker;
import net.java21.data2flow.ai.settings.AiSettingsService;
import net.java21.data2flow.contracts.authz.Permission;
import net.java21.data2flow.contracts.authz.RoleChecker;
import net.java21.data2flow.contracts.error.BusinessException;
import net.java21.data2flow.contracts.error.CommonErrorCode;
import net.java21.data2flow.contracts.error.FieldErrorDetail;
import net.java21.data2flow.contracts.identity.CurrentUser;
import net.java21.data2flow.contracts.web.ListApiResponse;
import net.java21.data2flow.contracts.web.PageParams;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 대화(API-AIA-02·10). M6는 도움말 모드(HELP, AIA-09.01)를 제공한다. 데이터 질의 모드(DATA, 챗봇 AIA-03)는 읽기 도구에 알람·
 * 플로우가 들어가 M7에서 연다(그 전에는 400 {@code errors[mode]=MODE_NOT_AVAILABLE}).
 * 대화는 본인만 보고(남의 대화·없는 대화는 404 AI_CONVERSATION_NOT_FOUND), 지우면 메시지까지 물리 삭제하고 사용량은 남긴다(BR-AIA-14).
 */
public class ConversationService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final ConversationRepository repository;
    private final DocsAnswerService docs;
    private final AiSettingsService settings;
    private final RoleChecker roleChecker;
    private final Clock clock;

    public ConversationService(ConversationRepository repository, DocsAnswerService docs, AiSettingsService settings, RoleChecker roleChecker,
                               Clock clock) {
        this.repository = repository;
        this.docs = docs;
        this.settings = settings;
        this.roleChecker = roleChecker;
        this.clock = clock;
    }

    /** 새 대화(conversationId null) 또는 이어서 묻기 */
    public List<ConversationDtos.Event> ask(Long conversationId, ConversationDtos.AskRequest req) {
        CurrentUser user = begin();
        long org = user.organizationId();
        String mode;
        long id;
        List<ConversationDtos.Event> events = new ArrayList<>();
        PiiMasker.Session pii = PiiMasker.session();
        String masked = pii.maskText(req.content());
        if (conversationId == null) {
            mode = req.mode() == null ? "DATA" : req.mode();
            requireMode(mode);
            String title = masked.length() > 100 ? masked.substring(0, 100) : masked;
            id = repository.insertConversation(org, user.userId(), title, mode, clock.instant());
            events.add(new ConversationDtos.Event("conversation", Map.of("conversationId", Long.toString(id))));
        } else {
            Map<String, Object> conv = owned(conversationId, user);
            mode = (String) conv.get("mode");
            requireMode(mode);
            id = conversationId;
        }
        repository.insertMessage(org, id, "USER", masked, null, null, null, clock.instant());
        DocsAnswerService.Answer answer = docs.answer(org, user.userId(), req.content(), req.context());
        List<Map<String, Object>> citations = new ArrayList<>();
        for (DocsAnswerService.DocLink link : answer.docs()) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("tool", "help_search");
            c.put("target", link.title());
            c.put("period", null);
            c.put("link", link.url());
            citations.add(c);
        }
        String text = answer.text();
        for (int i = 0; i < text.length(); i += 80) {
            events.add(new ConversationDtos.Event("delta", Map.of("text", text.substring(i, Math.min(text.length(), i + 80)))));
        }
        citations.forEach(c -> events.add(new ConversationDtos.Event("citation", c)));
        repository.insertMessage(org, id, "ASSISTANT", text, citations.isEmpty() ? null : JSON.writeValueAsString(citations),
                answer.tokensIn(), answer.tokensOut(), clock.instant());
        Map<String, Object> done = new LinkedHashMap<>();
        done.put("conversationId", Long.toString(id));
        done.put("found", answer.found());
        done.put("tokensIn", answer.tokensIn());
        done.put("tokensOut", answer.tokensOut());
        events.add(new ConversationDtos.Event("done", done));
        return events;
    }

    public ListApiResponse<ConversationDtos.Summary> list(Integer page, Integer size) {
        CurrentUser user = begin();
        PageParams p = PageParams.of(page, size);
        List<ConversationDtos.Summary> items = repository.pageByOrganizationIdAndUserId(user.organizationId(), user.userId(), p.offset(), p.size())
                .stream().map(ConversationService::summary).toList();
        return ListApiResponse.of(p, items, repository.countByOrganizationIdAndUserId(user.organizationId(), user.userId()));
    }

    public ConversationDtos.Detail get(long conversationId) {
        CurrentUser user = begin();
        Map<String, Object> conv = owned(conversationId, user);
        List<ConversationDtos.MessageView> messages = repository.listMessagesByOrganizationId(user.organizationId(), conversationId).stream()
                .map(m -> new ConversationDtos.MessageView(m.get("id").toString(), (String) m.get("role"), (String) m.get("content"),
                        m.get("citations") == null ? List.of() : JSON.readTree((String) m.get("citations")),
                        m.get("verification") == null ? null : JSON.readTree((String) m.get("verification")),
                        ((Timestamp) m.get("created_at")).toInstant()))
                .toList();
        ConversationDtos.Summary s = summary(conv);
        return new ConversationDtos.Detail(s.conversationId(), s.title(), s.mode(), s.createdAt(), s.updatedAt(), messages);
    }

    public void delete(long conversationId) {
        CurrentUser user = begin();
        if (repository.deleteByIdAndOrganizationIdAndUserId(conversationId, user.organizationId(), user.userId()) == 0) {
            throw new BusinessException(AiErrorCode.AI_CONVERSATION_NOT_FOUND);
        }
    }

    public void deleteAll() {
        CurrentUser user = begin();
        repository.deleteAllByOrganizationIdAndUserId(user.organizationId(), user.userId());
    }

    private CurrentUser begin() {
        CurrentUser user = roleChecker.currentUser();
        settings.requireEnabled(user.organizationId());
        roleChecker.require(Permission.AI_USE);
        return user;
    }

    private Map<String, Object> owned(long conversationId, CurrentUser user) {
        return repository.findByIdAndOrganizationIdAndUserId(conversationId, user.organizationId(), user.userId())
                .orElseThrow(() -> new BusinessException(AiErrorCode.AI_CONVERSATION_NOT_FOUND));
    }

    private static void requireMode(String mode) {
        if (!"HELP".equals(mode)) {
            throw new BusinessException(CommonErrorCode.INVALID_REQUEST,
                    List.of(new FieldErrorDetail("mode", "MODE_NOT_AVAILABLE", "데이터 질의(DATA)는 아직 제공하지 않습니다. HELP를 쓰세요")));
        }
    }

    private static ConversationDtos.Summary summary(Map<String, Object> c) {
        return new ConversationDtos.Summary(c.get("id").toString(), (String) c.get("title"), (String) c.get("mode"),
                ((Timestamp) c.get("created_at")).toInstant(), ((Timestamp) c.get("updated_at")).toInstant());
    }
}
