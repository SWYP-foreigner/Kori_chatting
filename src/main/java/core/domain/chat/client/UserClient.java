package core.domain.chat.client;

import core.domain.chat.dto.ImageDto;
import core.domain.chat.dto.UserResponseDto;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@FeignClient(name = "main-service", url = "${main.service.url}")
public interface UserClient {

    @GetMapping("/api/v1/users/{userId}/info")
    UserResponseDto getUserProfile(@PathVariable("userId") Long userId);

    @GetMapping("/api/v1/users/infos")
    List<UserResponseDto> getUsersInfo(@RequestParam("userIds") List<Long> userIds);

    @GetMapping("/api/v1/images/chat-rooms")
    List<ImageDto> getImagesForChatRooms(@RequestParam("roomIds") List<Long> roomIds);
}