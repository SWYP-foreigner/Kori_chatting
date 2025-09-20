package core.domain.chat.service;


import core.domain.chat.client.UserClient;
import core.domain.chat.dto.ChatUserProfileResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatUserService {

    UserClient userClient;
    public ChatUserProfileResponse getUserProfile(Long userId) {
        return userClient.getUserChatProfile(userId);
    }

}