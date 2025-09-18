package core.domain.chat.service;


import core.domain.chat.dto.ChatUserProfileResponse;
import core.domain.user.entity.User;
import core.global.enums.ErrorCode;
import core.global.enums.ImageType;
import core.global.exception.BusinessException;
import core.global.image.entity.Image;
import core.global.image.repository.ImageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChatUserService {

    private final ImageRepository imageRepository;

    public ChatUserProfileResponse getUserProfile(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(ErrorCode.USER_NOT_FOUND));
        List<Image> images = imageRepository.findByImageTypeAndRelatedIdOrderByOrderIndexAsc(ImageType.USER, userId);
        String imageUrl = images.stream().findFirst().map(Image::getUrl).orElse(null);
        return ChatUserProfileResponse.from(user, imageUrl);
    }

}