package core.domain.chat.controller;


import core.domain.chat.dto.CreateGroupChatRequest;
import core.domain.chat.dto.GroupChatDetailResponse;
import core.domain.chat.dto.GroupChatMainResponse;
import core.domain.chat.dto.GroupChatSearchResponse;
import core.domain.chat.service.GroupChatService;
import core.global.config.CustomUserDetails;
import core.global.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "그룹 채팅 API", description = "그룹 채팅 참여, 검색, 조회 등 그룹 채팅 관련 API")
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class GroupChatController {

    private final GroupChatService chatService;

    @Operation(summary = "그룹 채팅 참여")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "이미 참여 중이거나 그룹 채팅방이 아님"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 채팅방 또는 유저")
    })
    @PostMapping("/rooms/group/{roomId}/join")
    public ResponseEntity<ApiResponse<Void>> joinGroupChat(@PathVariable Long roomId) {
        CustomUserDetails principal = (CustomUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Long userId = principal.getUserId();
        chatService.joinGroupChat(roomId, userId);
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @Operation(summary = "그룹 채팅 상세 정보 조회")
    @GetMapping("/rooms/group/{roomId}")
    public ResponseEntity<ApiResponse<GroupChatDetailResponse>> getGroupChatDetails(
            @PathVariable Long roomId) {
        GroupChatDetailResponse response = chatService.getGroupChatDetails(roomId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "그룹 채팅방 검색", description = "채팅방 이름 키워드를 통해 그룹 채팅방을 검색합니다.")
    @GetMapping("/rooms/group/search")
    public ResponseEntity<ApiResponse<List<GroupChatSearchResponse>>> searchGroupChats(@RequestParam String keyword) {
        List<GroupChatSearchResponse> response = chatService.searchGroupChatRooms(keyword);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "최신 그룹 채팅방 조회", description = "가장 최근에 생성된 그룹 채팅방을 조회합니다.")
    @GetMapping("/group/latest")
    public ResponseEntity<ApiResponse<List<GroupChatMainResponse>>> getLatestGroupChats(
            @RequestParam(required = false) Long lastChatRoomId) {
        List<GroupChatMainResponse> response = chatService.getLatestGroupChats(lastChatRoomId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "인기 그룹 채팅방 조회", description = "참여자가 가장 많은 그룹 채팅방을 조회합니다.")
    @GetMapping("/group/popular")
    public ResponseEntity<ApiResponse<List<GroupChatMainResponse>>> getPopularGroupChats() {
        List<GroupChatMainResponse> response = chatService.getPopularGroupChats(10);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "그룹 채팅방 생성")
    @PostMapping("/group")
    public ResponseEntity<ApiResponse<Void>> createGroupChat(
            @Valid @RequestBody CreateGroupChatRequest request
    ) {
        CustomUserDetails principal = (CustomUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Long userId = principal.getUserId();
        chatService.createGroupChatRoom(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(null));
    }
}