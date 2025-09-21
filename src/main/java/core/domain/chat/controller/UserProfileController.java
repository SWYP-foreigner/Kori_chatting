package core.domain.chat.controller;


import core.domain.chat.dto.ChatUserProfileResponse;
import core.domain.chat.service.ChatUserService;
import core.global.config.CustomUserDetails;
import core.global.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@Tag(name = "채팅 유저 API", description = "채팅 내 유저 프로필 조회, 차단 등 관련 API")
@RestController
@RequestMapping("/chat/v1/users")
@RequiredArgsConstructor
public class UserProfileController {

    private final ChatUserService chatService;

    @Operation(summary = "유저 프로필 조회", description = "userId를 통해 유저의 상세 프로필 정보를 조회합니다.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "성공"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "존재하지 않는 유저")
    })
    @GetMapping("/{userId}/profile")
    public ResponseEntity<ApiResponse<ChatUserProfileResponse>> getUserProfile(@PathVariable Long userId) {
        ChatUserProfileResponse response = chatService.getUserProfile(userId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @Operation(summary = "유저 차단")
    @PostMapping("/block/{targetUserId}")
    public ResponseEntity<ApiResponse<Void>> blockUser(@PathVariable Long targetUserId) {
        CustomUserDetails principal = (CustomUserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        Long userId = principal.getUserId();
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}