package core.domain.chat.controller;


import core.domain.chat.dto.ChatMessageFirstResponse;
import core.domain.chat.dto.ChatMessageResponse;
import core.domain.chat.service.ChatMessageService;
import core.global.config.CustomUserDetails;
import core.global.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "채팅 메시지 API", description = "채팅 메시지 조회, 검색, 읽음 처리 등 관련 API")
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatMessageController {

    private final ChatMessageService chatService;
    private final Logger log = LoggerFactory.getLogger(ChatMessageController.class);

    @Operation(summary = "채팅방 메시지 조회 (무한 스크롤)", description = "위로 스크롤할 때 호출하는 API입니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 채팅방 또는 유저")
    })
    @GetMapping("/rooms/{roomId}/messages")
    public ResponseEntity<ApiResponse<List<ChatMessageResponse>>> getMessages(
            @PathVariable Long roomId,
            @RequestParam(required = false) Long lastMessageId
    ) {
        CustomUserDetails principal = (CustomUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Long userId = principal.getUserId();
        List<ChatMessageResponse> responses = chatService.getMessages(roomId, userId, lastMessageId);
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    @Operation(summary = "첫 채팅방 메시지 조회", description = "채팅방에 처음 입장 시 가장 최근 메시지를 조회합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "채팅방 또는 유저를 찾을 수 없음")
    })
    @GetMapping("/rooms/{roomId}/first_messages")
    public ResponseEntity<ApiResponse<List<ChatMessageFirstResponse>>> getFirstMessages(
            @PathVariable Long roomId
    ) {
        CustomUserDetails principal = (CustomUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Long userId = principal.getUserId();
        List<ChatMessageFirstResponse> responses = chatService.getFirstMessages(roomId, userId);
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    @Operation(summary = "메시지 키워드 검색", description = "메시지 내용을 키워드로 검색합니다.")
    @GetMapping("/search")
    public ResponseEntity<ApiResponse<List<ChatMessageResponse>>> searchMessages(
            @RequestParam Long roomId,
            @RequestParam String keyword
    ) {
        CustomUserDetails principal = (CustomUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Long userId = principal.getUserId();
        List<ChatMessageResponse> responses = chatService.searchMessages(roomId, userId, keyword);
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    @Operation(summary = "특정 메시지 주변의 채팅 내용 조회", description = "검색 등에서 특정 메시지로 이동할 때 사용합니다.")
    @GetMapping("/rooms/{roomId}/messages/around")
    public ResponseEntity<ApiResponse<List<ChatMessageResponse>>> getMessagesAround(
            @PathVariable Long roomId,
            @RequestParam Long messageId
    ) {
        CustomUserDetails principal = (CustomUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Long userId = principal.getUserId();
        List<ChatMessageResponse> responses = chatService.getMessagesAround(roomId, userId, messageId);
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    @Operation(summary = "채팅방 메시지 모두 읽음 처리")
    @PostMapping("/rooms/{roomId}/read-all")
    public ResponseEntity<ApiResponse<Void>> markAllAsRead(@PathVariable Long roomId) {
        CustomUserDetails principal = (CustomUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Long userId = principal.getUserId();
        chatService.markAllMessagesAsReadInRoom(roomId, userId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}