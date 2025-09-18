package core.domain.chat.controller;

import core.domain.chat.dto.*;
import core.domain.chat.entity.ChatRoom;
import core.domain.chat.service.ChatRoomService;
import core.global.config.CustomUserDetails;
import core.global.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "채팅방 API", description = "채팅방 생성, 조회, 참여자 관리 등 채팅방 관련 API")
@RestController
@RequestMapping("/api/v1/chat/rooms")
@RequiredArgsConstructor
public class ChatRoomController {

    private final ChatRoomService chatService;
    private final ChatRoomService chatRoomService;

    @Operation(summary = "1:1 새로운 채팅방 생성", description = "1:1 채팅방을 생성합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공",
                    content = @Content(schema = @Schema(implementation = ChatRoomResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "잘못된 요청",
                    content = @Content(schema = @Schema(implementation = Object.class))
            )
    })
    @PostMapping("/oneTone")
    public ResponseEntity<ApiResponse<ChatRoomResponse>> createRoom(
            @RequestBody CreateRoomRequest request
    ) {
        CustomUserDetails principal = (CustomUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Long userId = principal.getUserId();
        ChatRoomResponse response = chatRoomService.createRoom(userId, request.otherUserId());

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "자신의 채팅방 리스트 조회")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공",
                    content = @Content(schema = @Schema(implementation = ChatRoomSummaryResponse.class))
            )
    })
    @GetMapping
    public ResponseEntity<ApiResponse<List<ChatRoomSummaryResponse>>> getMyChatRooms() {
        CustomUserDetails principal = (CustomUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Long userId = principal.getUserId();
        List<ChatRoomSummaryResponse> responses = chatService.getMyAllChatRoomSummaries(userId);
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    @Operation(summary = "채팅방 나가기")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 채팅방 또는 유저")
    })
    @DeleteMapping("/{roomId}/leave")
    public ResponseEntity<ApiResponse<Void>> leaveChatRoom(@PathVariable Long roomId) {
        CustomUserDetails principal = (CustomUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Long userId = principal.getUserId();
        chatService.leaveRoom(roomId, userId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Operation(summary = "채팅 참여자 조회")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공",
                    content = @Content(schema = @Schema(implementation = ChatRoomParticipantsResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 채팅방")
    })
    @GetMapping("/{roomId}/participants")
    public ResponseEntity<ApiResponse<List<ChatRoomParticipantsResponse>>> getParticipants(@PathVariable Long roomId) {
        List<ChatRoomParticipantsResponse> responses = chatService.getRoomParticipants(roomId);
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    @Operation(summary = "채팅방 이름 검색", description = "사용자가 참여 중인 채팅방을 이름 키워드로 검색합니다. 1:1, 그룹 채팅 모두 포함됩니다.")
    @GetMapping("/search")
    public ResponseEntity<ApiResponse<List<ChatRoomSummaryResponse>>> searchMyRooms(
            @RequestParam("roomName") String roomName
    ) {
        CustomUserDetails principal = (CustomUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        List<ChatRoomSummaryResponse> responses = chatService.searchRoomsByRoomName(principal.getUserId(), roomName);
        return ResponseEntity.ok(ApiResponse.success(responses));
    }

    @Operation(summary = "채팅방 번역 기능 설정", description = "특정 채팅방의 메시지 번역 기능을 켜거나 끕니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "설정 변경 성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "채팅방 또는 참여자를 찾을 수 없음")
    })
    @PostMapping("/{roomId}/translation")
    public ResponseEntity<ApiResponse<Void>> toggleTranslation(
            @PathVariable Long roomId,
            @RequestBody ToggleTranslationRequest request
    ) {
        CustomUserDetails principal = (CustomUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Long userId = principal.getUserId();
        chatService.toggleTranslation(roomId, userId, request.translateEnabled());
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}