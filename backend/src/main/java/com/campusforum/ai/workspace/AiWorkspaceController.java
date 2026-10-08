package com.campusforum.ai.workspace;

import com.campusforum.common.R;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
public class AiWorkspaceController {

    private final AiWorkspaceService workspaceService;

    // 智能体（agents）与插件市场（plugins）端点已于 2026-07-12 删除：
    // 后端 48 端点从未被前端接入，按产品决策整体裁撤。

    @GetMapping("/knowledge-bases")
    public R<Map<String, Object>> knowledgeBases(@RequestParam(required = false) String keyword,
                                                 @RequestParam(required = false) String category,
                                                 @RequestParam(defaultValue = "all") String tab,
                                                 @RequestParam(required = false) String type,
                                                 @RequestParam(defaultValue = "recent") String sort,
                                                 @RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "10") int pageSize) {
        return R.ok(workspaceService.listKnowledgeBases(keyword, category, tab, type, sort, page, pageSize));
    }

    @GetMapping("/knowledge-bases/stats")
    public R<Map<String, Object>> knowledgeStats() {
        return R.ok(workspaceService.knowledgeStats());
    }

    @PostMapping("/knowledge-bases")
    public R<Map<String, Object>> createKnowledgeBase(@RequestBody Map<String, Object> body) {
        return R.ok(workspaceService.createKnowledgeBase(body));
    }

    @PatchMapping("/knowledge-bases/{knowledgeBaseId}")
    public R<Map<String, Object>> updateKnowledgeBase(@PathVariable String knowledgeBaseId,
                                                      @RequestBody Map<String, Object> body) {
        return R.ok(workspaceService.updateKnowledgeBase(knowledgeBaseId, body));
    }

    @DeleteMapping("/knowledge-bases/{knowledgeBaseId}")
    public R<Map<String, Object>> deleteKnowledgeBase(@PathVariable String knowledgeBaseId) {
        return R.ok(workspaceService.deleteKnowledgeBase(knowledgeBaseId));
    }

    @PostMapping("/knowledge-bases/{knowledgeBaseId}/favorite")
    public R<Map<String, Object>> favoriteKnowledgeBase(@PathVariable String knowledgeBaseId) {
        return R.ok(workspaceService.favoriteKnowledgeBase(knowledgeBaseId, true));
    }

    @DeleteMapping("/knowledge-bases/{knowledgeBaseId}/favorite")
    public R<Map<String, Object>> unfavoriteKnowledgeBase(@PathVariable String knowledgeBaseId) {
        return R.ok(workspaceService.favoriteKnowledgeBase(knowledgeBaseId, false));
    }

    @PostMapping("/knowledge-bases/{knowledgeBaseId}/share")
    public R<Map<String, Object>> shareKnowledgeBase(@PathVariable String knowledgeBaseId,
                                                     @RequestBody Map<String, Object> body) {
        return R.ok(workspaceService.shareKnowledgeBase(knowledgeBaseId, body));
    }

    @PostMapping("/knowledge-bases/{knowledgeBaseId}/documents")
    public R<Map<String, Object>> uploadDocuments(@PathVariable String knowledgeBaseId,
                                                  @RequestParam("files") MultipartFile[] files,
                                                  @RequestParam(required = false) String tags,
                                                  @RequestParam(required = false) String parseMode) {
        return R.ok(workspaceService.uploadDocuments(knowledgeBaseId, files, tags, parseMode));
    }

    @GetMapping("/knowledge-bases/{knowledgeBaseId}/documents")
    public R<List<Map<String, Object>>> documents(@PathVariable String knowledgeBaseId) {
        return R.ok(workspaceService.listDocuments(knowledgeBaseId));
    }

    @DeleteMapping("/knowledge-bases/{knowledgeBaseId}/documents/{documentId}")
    public R<Map<String, Object>> deleteDocument(@PathVariable String knowledgeBaseId,
                                                 @PathVariable String documentId) {
        return R.ok(workspaceService.deleteDocument(knowledgeBaseId, documentId));
    }

    @GetMapping("/knowledge-ingest-tasks/{taskId}")
    public R<Map<String, Object>> ingestTask(@PathVariable String taskId) {
        return R.ok(workspaceService.ingestTask(taskId));
    }

    @PostMapping("/knowledge-bases/{knowledgeBaseId}/qa-pairs")
    public R<Map<String, Object>> createQaPair(@PathVariable String knowledgeBaseId,
                                               @RequestBody Map<String, Object> body) {
        return R.ok(workspaceService.createQaPair(knowledgeBaseId, body));
    }

    @GetMapping("/knowledge-bases/{knowledgeBaseId}/usage")
    public R<Map<String, Object>> knowledgeUsage(@PathVariable String knowledgeBaseId) {
        return R.ok(workspaceService.knowledgeUsage(knowledgeBaseId));
    }

    @GetMapping("/knowledge-bases/{knowledgeBaseId}")
    public R<Map<String, Object>> knowledgeBase(@PathVariable String knowledgeBaseId) {
        return R.ok(workspaceService.getKnowledgeBase(knowledgeBaseId));
    }

    @GetMapping("/knowledge-bases/{knowledgeBaseId}/qa-pairs")
    public R<List<Map<String, Object>>> qaPairs(@PathVariable String knowledgeBaseId) {
        return R.ok(workspaceService.listQaPairs(knowledgeBaseId));
    }

    @PatchMapping("/knowledge-bases/{knowledgeBaseId}/qa-pairs/{qaPairId}")
    public R<Map<String, Object>> updateQaPair(@PathVariable String knowledgeBaseId,
                                                @PathVariable String qaPairId,
                                                @RequestBody Map<String, Object> body) {
        return R.ok(workspaceService.updateQaPair(knowledgeBaseId, qaPairId, body));
    }

    @DeleteMapping("/knowledge-bases/{knowledgeBaseId}/qa-pairs/{qaPairId}")
    public R<Map<String, Object>> deleteQaPair(@PathVariable String knowledgeBaseId,
                                                @PathVariable String qaPairId) {
        return R.ok(workspaceService.deleteQaPair(knowledgeBaseId, qaPairId));
    }

    @GetMapping("/knowledge-bases/{knowledgeBaseId}/documents/{documentId}")
    public R<Map<String, Object>> documentDetail(@PathVariable String knowledgeBaseId,
                                                  @PathVariable String documentId) {
        return R.ok(workspaceService.getDocumentDetail(knowledgeBaseId, documentId));
    }

    @GetMapping("/knowledge-bases/{knowledgeBaseId}/documents/{documentId}/download")
    public ResponseEntity<org.springframework.core.io.InputStreamResource> downloadDocument(
            @PathVariable String knowledgeBaseId,
            @PathVariable String documentId) {
        return workspaceService.downloadDocument(knowledgeBaseId, documentId);
    }

    // ======================== Notes ========================

    @GetMapping("/notes")
    public R<Map<String, Object>> notes(@RequestParam(required = false) String keyword,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "20") int pageSize) {
        return R.ok(workspaceService.listNotes(keyword, status, page, pageSize));
    }

    @GetMapping("/notes/{noteId}")
    public R<Map<String, Object>> note(@PathVariable String noteId) {
        return R.ok(workspaceService.getNote(noteId));
    }

    @PostMapping("/notes")
    public R<Map<String, Object>> createNote(@RequestBody Map<String, Object> body) {
        return R.ok(workspaceService.createNote(body));
    }

    @RequestMapping(value = "/notes/{noteId}", method = {RequestMethod.PUT, RequestMethod.PATCH})
    public R<Map<String, Object>> updateNote(@PathVariable String noteId,
                                              @RequestBody Map<String, Object> body) {
        return R.ok(workspaceService.updateNote(noteId, body));
    }

    @DeleteMapping("/notes/{noteId}")
    public R<Map<String, Object>> deleteNote(@PathVariable String noteId) {
        return R.ok(workspaceService.deleteNote(noteId));
    }

    // ---- Public notes (no login required) ----

    @GetMapping("/notes/public")
    public R<Map<String, Object>> publicNotes(@RequestParam(required = false) String keyword,
                                               @RequestParam(required = false) String tag,
                                               @RequestParam(defaultValue = "false") boolean mine,
                                               @RequestParam(defaultValue = "1") int page,
                                               @RequestParam(defaultValue = "12") int pageSize) {
        return R.ok(workspaceService.listPublicNotes(keyword, tag, mine, page, pageSize));
    }

    @GetMapping("/notes/tags")
    public R<List<String>> noteTags() {
        return R.ok(workspaceService.getNoteTags());
    }

    @GetMapping("/notes/public/{noteId}")
    public R<Map<String, Object>> publicNote(@PathVariable String noteId) {
        return R.ok(workspaceService.getPublicNote(noteId));
    }

    @PostMapping("/conversations")
    public R<Map<String, Object>> createConversation(@RequestBody Map<String, Object> body) {
        return R.ok(workspaceService.createConversation(body));
    }

    @GetMapping("/conversations")
    public R<Map<String, Object>> conversations(@RequestParam(defaultValue = "1") int page,
                                                @RequestParam(defaultValue = "10") int pageSize) {
        return R.ok(workspaceService.listConversations(page, pageSize));
    }

    @GetMapping("/conversations/{conversationId}/messages")
    public R<List<Map<String, Object>>> conversationMessages(@PathVariable String conversationId) {
        return R.ok(workspaceService.conversationMessages(conversationId));
    }

    @PostMapping("/conversations/{conversationId}/messages")
    public R<Map<String, Object>> sendMessage(@PathVariable String conversationId,
                                              @RequestBody Map<String, Object> body) {
        return R.ok(workspaceService.sendMessage(conversationId, body));
    }

    @PostMapping("/messages/{messageId}/feedback")
    public R<Map<String, Object>> feedback(@PathVariable String messageId,
                                           @RequestBody Map<String, Object> body) {
        return R.ok(workspaceService.feedback(messageId, body));
    }
}
